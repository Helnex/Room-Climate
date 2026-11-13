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

from fastapi import APIRouter, Depends, HTTPException, Path, Query, Request
from fastapi.responses import HTMLResponse, Response
from pydantic import BaseModel, Field, field_validator
from redis.exceptions import RedisError

from app import access, alerts, db, insights, proxy, tls_cert
from app.redis_store import read_current, redis_client
from app.schemas import SAFE_ID, normalize_email

# Через сколько секунд без новых показаний датчик считается «молчащим».
STALE_AFTER_SECONDS = int(os.environ.get("STALE_AFTER_SECONDS", "180"))
# Внешние порты (как их видит телефон): HTTPS-сервис и сервер уведомлений ntfy.
TLS_PUBLIC_PORT = os.environ.get("TLS_PUBLIC_PORT", "8443")
NTFY_PUBLIC_PORT = os.environ.get("NTFY_PUBLIC_PORT", "8090")
APP_PACKAGE = "com.example.roomclimate"
_HOST_RE = re.compile(r"^[A-Za-z0-9.\-]+(:\d{1,5})?$|^\[[0-9A-Fa-f:]+\](:\d{1,5})?$")
_SENSOR_LABELS = {"temperature": "Температура", "humidity": "Влажность"}


class MemberIn(BaseModel):
    email: str = Field(min_length=3, max_length=128)

    @field_validator("email")
    @classmethod
    def _clean(cls, value: str) -> str:
        return normalize_email(value)


def _safe_host(request: Request) -> str:
    """Host из запроса, только если он похож на адрес (защита от XSS через заголовок Host)."""
    host = request.headers.get("host", "")
    return host if _HOST_RE.match(host) else "127.0.0.1:8000"


PRIVACY_HTML = """<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>RoomClimate — персональные данные</title>
<style>body{font-family:system-ui,sans-serif;max-width:640px;margin:24px auto;padding:0 16px;line-height:1.5}</style>
</head><body>
<h1>Политика обработки персональных данных</h1>
<p>Система RoomClimate (учебный проект) обрабатывает персональные данные в соответствии
с Федеральным законом № 152-ФЗ «О персональных данных».</p>
<h2>Какие данные</h2>
<ul><li>имя, фамилия и email — для входа и доступа к устройствам;</li>
<li>хэш пароля (сам пароль не хранится);</li>
<li>показания датчиков температуры и влажности и пороги уведомлений.</li></ul>
<h2>Где и как хранятся</h2>
<p>Только на сервере стенда в локальной сети, в базе SQLite. Третьим лицам не передаются,
в облако не отправляются. Пароли хранятся как PBKDF2-SHA256 (600 000 итераций),
токены сессий — только в виде SHA-256, сессия действует 30 дней.</p>
<h2>Ваши права</h2>
<p>Вы можете в любой момент удалить аккаунт: «Аккаунт → Удалить аккаунт» в приложении
(или запрос DELETE /auth/me). Удаляются профиль, сессии и доступы к устройствам.</p>
<h2>Согласие</h2>
<p>Регистрируясь, вы соглашаетесь на обработку указанных данных для работы системы.</p>
</body></html>"""


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

    @router.get("/telemetry/insights")
    def telemetry_insights(
        device_id: str = Query(default="pi-1", max_length=64),
        hours: int = Query(default=24, ge=1, le=24 * 31),
        user=Depends(current_user),
    ):
        access.check(device_id, user)
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

    @router.get("/devices")
    def devices(user=Depends(current_user)):
        uid = int(user["id"])
        conn = db.connect()
        try:
            # Только свои платы и ещё никем не закреплённые (чужие не показываем вовсе).
            rows = conn.execute(
                """
                SELECT s.device_id AS device_id, MAX(m.measured_at) AS last_seen,
                       COUNT(DISTINCT s.type) AS sensors,
                       (SELECT dm.role FROM device_members dm
                        WHERE dm.device_id = s.device_id AND dm.user_id = ?) AS role,
                       EXISTS(SELECT 1 FROM device_members dm2 WHERE dm2.device_id = s.device_id) AS claimed
                FROM sensors s
                LEFT JOIN measurements m ON m.sensor_id = s.id
                GROUP BY s.device_id
                ORDER BY s.device_id
                """,
                (uid,),
            ).fetchall()
        finally:
            conn.close()
        result = []
        for row in rows:
            if row["claimed"] and row["role"] is None:
                continue
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
                "role": row["role"],  # owner | member | None (не закреплена)
            })
        return {"count": len(result), "devices": result, "stale_after_seconds": STALE_AFTER_SECONDS}

    # ---------- Владельцы и доступ ----------

    @router.post("/devices/{device_id}/claim")
    def claim_device(device_id: str = Path(max_length=64, pattern=SAFE_ID), user=Depends(current_user)):
        uid = int(user["id"])
        conn = db.connect()
        try:
            current = access.role(conn, device_id, uid)
            if current == "owner":
                return {"device_id": device_id, "role": "owner"}
            if access.is_claimed(conn, device_id):
                raise HTTPException(status_code=409, detail="Устройство уже закреплено за другим пользователем")
            conn.execute(
                "INSERT INTO device_members(device_id, user_id, role, created_at) VALUES (?, ?, 'owner', ?)",
                (device_id, uid, db.utc_now()),
            )
            conn.commit()
        finally:
            conn.close()
        return {"device_id": device_id, "role": "owner"}

    @router.get("/devices/{device_id}/members")
    def list_members(device_id: str = Path(max_length=64, pattern=SAFE_ID), user=Depends(current_user)):
        conn = db.connect()
        try:
            access.require(conn, device_id, user)
            rows = conn.execute(
                "SELECT u.email, u.name, u.surname, dm.role FROM device_members dm"
                " JOIN users u ON u.id = dm.user_id WHERE dm.device_id = ? ORDER BY dm.role DESC, u.email",
                (device_id,),
            ).fetchall()
        finally:
            conn.close()
        return {"device_id": device_id, "members": [dict(r) for r in rows]}

    @router.post("/devices/{device_id}/members")
    def add_member(body: MemberIn, device_id: str = Path(max_length=64, pattern=SAFE_ID), user=Depends(current_user)):
        conn = db.connect()
        try:
            access.require_owner(conn, device_id, user)
            target = db.get_user_by_email(conn, body.email)
            if target is None:
                raise HTTPException(status_code=404, detail="Пользователь с таким email не зарегистрирован")
            conn.execute(
                "INSERT OR IGNORE INTO device_members(device_id, user_id, role, created_at)"
                " VALUES (?, ?, 'member', ?)",
                (device_id, int(target["id"]), db.utc_now()),
            )
            conn.commit()
        finally:
            conn.close()
        return {"device_id": device_id, "email": body.email, "role": "member"}

    @router.delete("/devices/{device_id}/members")
    def remove_member(
        device_id: str = Path(max_length=64, pattern=SAFE_ID),
        email: str = Query(min_length=3, max_length=128),
        user=Depends(current_user),
    ):
        conn = db.connect()
        try:
            access.require_owner(conn, device_id, user)
            target = db.get_user_by_email(conn, normalize_email(email))
            if target is not None and int(target["id"]) != int(user["id"]):
                conn.execute(
                    "DELETE FROM device_members WHERE device_id = ? AND user_id = ? AND role = 'member'",
                    (device_id, int(target["id"])),
                )
                conn.commit()
        finally:
            conn.close()
        return {"status": "ok"}

    # ---------- Журнал тревог и push ----------

    @router.get("/alerts")
    def list_alerts(
        device_id: str = Query(default="pi-1", max_length=64),
        limit: int = Query(default=50, ge=1, le=500),
        user=Depends(current_user),
    ):
        conn = db.connect()
        try:
            access.require(conn, device_id, user)
            rows = conn.execute(
                "SELECT id, kind, sensor, value, threshold, message, created_at, resolved_at"
                " FROM alerts WHERE device_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                (device_id, limit),
            ).fetchall()
        finally:
            conn.close()
        return {"device_id": device_id, "alerts": [dict(r) for r in rows]}

    @router.get("/notify/info", dependencies=auth)
    def notify_info(request: Request):
        """Куда подписаться в приложении ntfy, чтобы получать push без фонового сервиса."""
        if not alerts.NTFY_TOPIC or not NTFY_PUBLIC_PORT:
            return {"enabled": False}
        if proxy.via_tunnel(request):
            # Сервер ntfy стенда доступен только в локальной сети: подписку оформляют дома.
            return {"enabled": False, "reason": "remote"}
        host = _safe_host(request).split(":")[0]
        server = f"http://{host}:{NTFY_PUBLIC_PORT}"
        return {
            "enabled": True,
            "server": server,
            "topic": alerts.NTFY_TOPIC,
            "subscribe_url": f"{server}/{alerts.NTFY_TOPIC}",
            "app_link": f"ntfy://{host}:{NTFY_PUBLIC_PORT}/{alerts.NTFY_TOPIC}?secure=false",
        }

    # ---------- Персональные данные (152-ФЗ) ----------

    @router.get("/privacy", response_class=HTMLResponse)
    def privacy():
        return HTMLResponse(PRIVACY_HTML)

    @router.delete("/auth/me")
    def delete_account(user=Depends(current_user)):
        """Удаление аккаунта и всех персональных данных (право на удаление, ст. 21 152-ФЗ)."""
        uid = int(user["id"])
        conn = db.connect()
        try:
            owned = [r["device_id"] for r in conn.execute(
                "SELECT device_id FROM device_members WHERE user_id = ? AND role = 'owner'", (uid,))]
            conn.execute("DELETE FROM sessions WHERE user_id = ?", (uid,))
            conn.execute("DELETE FROM device_members WHERE user_id = ?", (uid,))
            conn.execute("DELETE FROM users WHERE id = ?", (uid,))
            # Плата не должна остаться без владельца: передаём её самому давнему участнику.
            for device_id in owned:
                heir = conn.execute(
                    "SELECT user_id FROM device_members WHERE device_id = ? ORDER BY created_at LIMIT 1",
                    (device_id,),
                ).fetchone()
                if heir is not None:
                    conn.execute(
                        "UPDATE device_members SET role = 'owner' WHERE device_id = ? AND user_id = ?",
                        (device_id, heir["user_id"]),
                    )
            conn.commit()
        finally:
            conn.close()
        return {"status": "deleted"}

    @router.get("/telemetry/export.csv")
    def export_csv(
        device_id: str = Query(default="pi-1", max_length=64),
        hours: int = Query(default=24, ge=1, le=24 * 366),
        date_from: str | None = Query(default=None, alias="from", max_length=64),
        date_to: str | None = Query(default=None, alias="to", max_length=64),
        user=Depends(current_user),
    ):
        access.check(device_id, user)
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
        host = _safe_host(request)
        remote = proxy.via_tunnel(request)
        # Через Cloudflare Tunnel страница открыта по https с настоящим сертификатом.
        base = f"https://{host}" if remote else f"http://{host}"
        local_only = not remote and host.split(":")[0] in ("127.0.0.1", "localhost", "[::1]")
        page_url = f"{base}/connect"
        # В локальной сети, если есть HTTPS-сервис, — https-адрес и отпечаток ключа (pin):
        # дальше телефон общается с сервером шифрованно и только с этим ключом.
        # Через туннель pin не нужен: сертификат Cloudflare проверяется обычным способом.
        pin = None if remote else tls_cert.spki_pin()
        app_url = f"https://{host.rsplit(':', 1)[0]}:{TLS_PUBLIC_PORT}" if pin else base
        params = f"url={quote(app_url, safe='')}" + (f"&pin={quote(pin, safe='')}" if pin else "")
        intent_url = f"intent://connect?{params}#Intent;scheme=roomclimate;package={APP_PACKAGE};end"
        scheme_url = f"roomclimate://connect?{params}"
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
<p class="muted">{"Подключение из интернета через Cloudflare Tunnel (HTTPS). Регистрация — только в локальной сети стенда." if remote
 else "Шифрование: HTTPS, отпечаток ключа <code>" + html.escape(pin) + "</code>" if pin
 else "HTTPS-сервис не запущен — приложение подключится без шифрования (только в локальной сети)."}</p>
</body></html>"""
        return HTMLResponse(body)

    return router
