# -*- coding: utf-8 -*-
"""Эндпоинты для самой платы (Raspberry Pi) и её состояние для приложения.

Плата (заголовок X-Device-Token, как у POST /telemetry):
  GET  /devices/{id}/thresholds — пороги для светодиода/пищалки на плате;
  POST /devices/{id}/status     — «пульс»: температура процессора, Wi-Fi, аптайм, версия, буфер.
Приложение (пользователь с доступом к плате):
  GET  /devices/{id}/status     — последнее состояние и сколько секунд назад оно пришло.

Время замера для досылки накопленных показаний — parse_measured_at() (POST /telemetry).
"""
from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Path
from pydantic import BaseModel, ConfigDict, Field

from app import access, alerts, db
from app.schemas import SAFE_ID

# Плата копит показания без связи до недели; из будущего — допуск на неточные часы.
MAX_BUFFER_AGE = timedelta(days=7)
MAX_CLOCK_SKEW = timedelta(minutes=5)
# Старше этого показание считается досланным: не перезаписывает «текущее» и не будит тревоги.
LATE_AFTER = timedelta(minutes=2)
# Порог CO₂ для сигнала на плате (ppm): выше 1000 — «душно, проветрите» по общепринятой шкале.
CO2_MAX = 1000.0

SCHEMA = """
CREATE TABLE IF NOT EXISTS board_status (
    device_id TEXT PRIMARY KEY,
    data TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
"""


def ensure_schema(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA)


def parse_measured_at(value: datetime | None, now: datetime | None = None) -> tuple[str, bool]:
    """Время замера из запроса → (ISO UTC, досланное ли). None — «сейчас».

    Бросает HTTPException 422, если время из будущего или старше недели.
    """
    now = now or datetime.now(timezone.utc)
    if value is None:
        return now.replace(microsecond=0).isoformat(), False
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    value = value.astimezone(timezone.utc).replace(microsecond=0)
    if value > now + MAX_CLOCK_SKEW:
        raise HTTPException(status_code=422, detail="Время замера в будущем — проверьте часы платы")
    if value < now - MAX_BUFFER_AGE:
        raise HTTPException(status_code=422, detail="Показание старше 7 дней — не принимается")
    return value.isoformat(), now - value > LATE_AFTER


class StatusIn(BaseModel):
    model_config = ConfigDict(allow_inf_nan=False)

    cpu_temp: float | None = Field(default=None, ge=-40, le=150)
    wifi_ssid: str | None = Field(default=None, max_length=64)
    wifi_signal: float | None = Field(default=None, ge=-120, le=0)  # дБм
    ip: str | None = Field(default=None, max_length=64)
    uptime_sec: int | None = Field(default=None, ge=0)
    version: str | None = Field(default=None, max_length=64)
    buffered: int | None = Field(default=None, ge=0)
    sensors: list[str] | None = Field(default=None, max_length=16)
    alarm: bool | None = None


def build_router(current_user, device_access) -> APIRouter:
    router = APIRouter()

    @router.get("/devices/{device_id}/thresholds", dependencies=[Depends(device_access)])
    def board_thresholds(device_id: str = Path(max_length=64, pattern=SAFE_ID)):
        """Пороги для сигнала на плате — те же, что пользователь задал в приложении."""
        conn = db.connect()
        try:
            row = conn.execute(
                "SELECT temp_min, temp_max, humidity_min, humidity_max FROM settings WHERE device_id = ?",
                (device_id,),
            ).fetchone()
        finally:
            conn.close()
        t_min, t_max = alerts.DEFAULTS["temperature"]
        h_min, h_max = alerts.DEFAULTS["humidity"]
        if row is not None:
            t_min, t_max = row["temp_min"], row["temp_max"]
            h_min = row["humidity_min"] if row["humidity_min"] is not None else h_min
            h_max = row["humidity_max"] if row["humidity_max"] is not None else h_max
        return {
            "device_id": device_id,
            "temperature": {"min": t_min, "max": t_max},
            "humidity": {"min": h_min, "max": h_max},
            "co2": {"max": CO2_MAX},
        }

    @router.post("/devices/{device_id}/status", dependencies=[Depends(device_access)])
    def post_status(body: StatusIn, device_id: str = Path(max_length=64, pattern=SAFE_ID)):
        now = db.utc_now()
        conn = db.connect()
        try:
            ensure_schema(conn)
            conn.execute(
                "INSERT INTO board_status(device_id, data, updated_at) VALUES (?, ?, ?)"
                " ON CONFLICT(device_id) DO UPDATE SET data = excluded.data, updated_at = excluded.updated_at",
                (device_id, body.model_dump_json(exclude_none=True), now),
            )
            conn.commit()
        finally:
            conn.close()
        return {"status": "ok", "device_id": device_id, "updated_at": now}

    @router.get("/devices/{device_id}/status")
    def get_status(device_id: str = Path(max_length=64, pattern=SAFE_ID), user=Depends(current_user)):
        access.check(device_id, user)
        conn = db.connect()
        try:
            ensure_schema(conn)
            row = conn.execute(
                "SELECT data, updated_at FROM board_status WHERE device_id = ?", (device_id,)
            ).fetchone()
        finally:
            conn.close()
        if row is None:
            raise HTTPException(status_code=404, detail="Плата ещё не присылала своё состояние")
        updated = datetime.fromisoformat(row["updated_at"])
        age = int((datetime.now(timezone.utc) - updated).total_seconds())
        return {"device_id": device_id, "updated_at": row["updated_at"], "age_seconds": max(age, 0),
                **json.loads(row["data"])}

    return router
