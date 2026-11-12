"""Дополнительные эндпоинты (Крючков К.): аналитика, устройства, экспорт CSV, страница подключения.

Подключаются в main.py одной строкой: app.include_router(extras.build_router(current_user)).
Логика расчётов — в app/insights.py.
"""
from __future__ import annotations

import csv
import html
import io
import os
import re
from datetime import datetime, timedelta, timezone
from urllib.parse import quote

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import HTMLResponse, Response
from redis.exceptions import RedisError

from app import db, insights
from app.redis_store import read_current, redis_client

# Через сколько секунд без новых показаний датчик считается «молчащим».
STALE_AFTER_SECONDS = int(os.environ.get("STALE_AFTER_SECONDS", "180"))
APP_PACKAGE = "com.example.roomclimate"
_HOST_RE = re.compile(r"^[A-Za-z0-9.\-]+(:\d{1,5})?$|^\[[0-9A-Fa-f:]+\](:\d{1,5})?$")
_SENSOR_LABELS = {"temperature": "Температура", "humidity": "Влажность"}


def _csv_text(value) -> str:
    """Защита от CSV/формульной инъекции (OWASP): Excel исполняет ячейки, начинающиеся
    с = + - @ или табуляции/перевода строки. Такие текстовые значения экранируем апострофом."""
    text = "" if value is None else str(value)
    if text and text[0] in ("=", "+", "-", "@", "\t", "\r", "\n"):
        return "'" + text
    return text


def _iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).replace(microsecond=0).isoformat()


def _latest(device_id: str) -> dict[str, dict]:
    """Последние показания: из Redis, если свежие, иначе из SQLite."""
    try:
        current = read_current(redis_client(), device_id)
    except RedisError:
        current = {}
    if current:
        return current
    conn = db.connect()
    try:
        rows = db.last_measurements(conn, device_id)
    finally:
        conn.close()
    return {
        row["type"]: {"value": row["value"], "unit": row["unit"], "measured_at": row["measured_at"]}
        for row in rows
    }


def build_router(current_user) -> APIRouter:
    router = APIRouter()
    auth = [Depends(current_user)]

    @router.get("/telemetry/insights", dependencies=auth)
    def telemetry_insights(
        device_id: str = Query(default="pi-1", max_length=64),
        hours: int = Query(default=24, ge=1, le=24 * 31),
    ):
        latest = _latest(device_id)
        temp = latest.get("temperature", {}).get("value")
        hum = latest.get("humidity", {}).get("value")
        stamps = [r.get("measured_at") for r in latest.values() if r.get("measured_at")]
        last_seen = max(stamps) if stamps else None
        age = insights.age_seconds(last_seen)

        since = _iso(datetime.now(timezone.utc) - timedelta(hours=hours))
        conn = db.connect()
        try:
            # Агрегаты считает SQLite — раньше все строки периода грузились в Python.
            aggregates = db.period_stats(conn, device_id, since)
            limits = db.get_settings(conn, device_id)
        finally:
            conn.close()
        stats = {
            row["type"]: {
                "min": round(row["min"], 1),
                "max": round(row["max"], 1),
                "avg": round(row["avg"], 1),
                "count": row["count"],
            }
            for row in aggregates
        }

        t_min = limits["temp_min"] if limits else 18.0
        t_max = limits["temp_max"] if limits else 26.0
        h_min = limits["humidity_min"] if limits and limits["humidity_min"] is not None else 30.0
        h_max = limits["humidity_max"] if limits and limits["humidity_max"] is not None else 70.0

        return {
            "device_id": device_id,
            "last_seen": last_seen,
            "age_seconds": age,
            "online": age is not None and age <= STALE_AFTER_SECONDS,
            "stale_after_seconds": STALE_AFTER_SECONDS,
            "temperature": temp,
            "humidity": hum,
            "dew_point": insights.dew_point(temp, hum) if temp is not None and hum is not None else None,
            "comfort": insights.comfort(temp, hum),
            "mold_risk": insights.mold_risk(temp, hum),
            "advice": insights.advice(temp, hum),
            "thresholds": {
                "temperature": insights.threshold_state(temp, t_min, t_max),
                "humidity": insights.threshold_state(hum, h_min, h_max),
            },
            "period_hours": hours,
            "stats": stats,
        }

    @router.get("/devices", dependencies=auth)
    def devices():
        conn = db.connect()
        try:
            rows = conn.execute(
                """
                SELECT s.device_id AS device_id, MAX(m.measured_at) AS last_seen,
                       COUNT(DISTINCT s.type) AS sensors
                FROM sensors s
                LEFT JOIN measurements m ON m.sensor_id = s.id
                GROUP BY s.device_id
                ORDER BY s.device_id
                """
            ).fetchall()
        finally:
            conn.close()
        result = []
        for row in rows:
            last_seen = row["last_seen"]
            try:
                fresh = read_current(redis_client(), row["device_id"])
                stamps = [r.get("measured_at") for r in fresh.values() if r.get("measured_at")]
                if stamps and (last_seen is None or max(stamps) > last_seen):
                    last_seen = max(stamps)
            except RedisError:
                pass
            age = insights.age_seconds(last_seen)
            result.append({
                "device_id": row["device_id"],
                "sensors": row["sensors"],
                "last_seen": last_seen,
                "age_seconds": age,
                "online": age is not None and age <= STALE_AFTER_SECONDS,
            })
        return {"count": len(result), "devices": result, "stale_after_seconds": STALE_AFTER_SECONDS}

    @router.get("/telemetry/export.csv", dependencies=auth)
    def export_csv(
        device_id: str = Query(default="pi-1", max_length=64),
        hours: int = Query(default=24, ge=1, le=24 * 366),
        date_from: str | None = Query(default=None, alias="from", max_length=64),
        date_to: str | None = Query(default=None, alias="to", max_length=64),
    ):
        since = date_from or _iso(datetime.now(timezone.utc) - timedelta(hours=hours))
        conn = db.connect()
        try:
            rows = db.history(conn, device_id, since, date_to, None)
        finally:
            conn.close()
        buffer = io.StringIO()
        # «;» и BOM — чтобы русский Excel открыл файл двойным щелчком без мастера импорта.
        writer = csv.writer(buffer, delimiter=";", lineterminator="\r\n")
        writer.writerow(["Время (UTC)", "Устройство", "Датчик", "Значение", "Ед."])
        for row in rows:
            unit = "°C" if row["unit"] in ("C", "c", "°C") else row["unit"]
            writer.writerow([
                _csv_text(row["measured_at"]),
                _csv_text(device_id),
                _csv_text(_SENSOR_LABELS.get(row["type"], row["type"])),
                str(row["value"]).replace(".", ","),
                _csv_text(unit),
            ])
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M")
        safe_device = re.sub(r"[^A-Za-z0-9_-]", "_", device_id)
        return Response(
            content="﻿" + buffer.getvalue(),
            media_type="text/csv; charset=utf-8",
            headers={"Content-Disposition": f'attachment; filename="roomclimate_{safe_device}_{stamp}.csv"'},
        )

    @router.get("/connect", response_class=HTMLResponse)
    def connect_page(request: Request):
        host = request.headers.get("host", "")
        if not _HOST_RE.match(host):
            host = "127.0.0.1:8000"
        base = f"http://{host}"
        local_only = host.split(":")[0] in ("127.0.0.1", "localhost", "[::1]")
        page_url = f"{base}/connect"
        intent_url = (
            f"intent://connect?url={quote(base, safe='')}"
            f"#Intent;scheme=roomclimate;package={APP_PACKAGE};end"
        )
        scheme_url = f"roomclimate://connect?url={quote(base, safe='')}"
        qr_svg = ""
        try:
            import segno

            qr_svg = segno.make(page_url, error="m").svg_inline(scale=6, dark="#1b5e20")
        except Exception:  # segno не установлен — страница работает и без QR
            qr_svg = ""
        warning = (
            "<p class='warn'>Страница открыта по адресу 127.0.0.1 — телефон его не увидит. "
            "Откройте её по адресу компьютера в сети (его печатает "
            "<code>scripts\\network-check.ps1</code>), тогда QR-код будет правильным.</p>"
            if local_only else ""
        )
        body = f"""<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>RoomClimate — подключение</title>
<style>
 body{{font-family:system-ui,sans-serif;max-width:520px;margin:24px auto;padding:0 16px;color:#1c1c1c}}
 h1{{font-size:22px}} .qr{{text-align:center;margin:16px 0}}
 .btn{{display:block;text-align:center;background:#2e7d32;color:#fff;padding:14px;border-radius:10px;
      text-decoration:none;font-size:18px;margin:12px 0}}
 .warn{{background:#fff3e0;border-left:4px solid #ef6c00;padding:10px}}
 code{{background:#f1f1f1;padding:2px 4px;border-radius:4px}} .muted{{color:#666;font-size:14px}}
</style></head><body>
<h1>Подключение к RoomClimate</h1>
<p>Сервер: <code>{html.escape(base)}</code></p>
{warning}
<div class="qr">{qr_svg}</div>
<p class="muted">1. Отсканируйте QR-код камерой телефона — откроется эта страница.<br>
2. На телефоне нажмите кнопку ниже — приложение сохранит адрес сервера.</p>
<a class="btn" href="{html.escape(intent_url)}">Открыть в приложении RoomClimate</a>
<p class="muted">Если кнопка не сработала: <a href="{html.escape(scheme_url)}">запасная ссылка</a>
или впишите адрес <code>{html.escape(base)}</code> в «Настройки → Подключение к серверу».</p>
<p class="muted">Раз эта страница открылась на телефоне — сеть между телефоном и компьютером работает.</p>
</body></html>"""
        return HTMLResponse(body)

    return router
