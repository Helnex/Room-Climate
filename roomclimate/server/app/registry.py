# -*- coding: utf-8 -*-
"""Реестр плат и датчиков для приложения: экраны «Устройства», «Новое помещение»,
«Добавление датчика», «Выбор датчика», «Выбор помещения» (макет Figma).

Платы и датчики заводятся в базу миграциями (app/migrations), приложение их только выбирает:
  GET    /registry/types                  — показатели и виды датчиков (справочники);
  GET    /registry/boards                 — зарегистрированные платы: свободна / моя / занята;
  GET    /registry/sensors                — зарегистрированные датчики, их показания и привязка;
  POST   /registry/sensors/{serial}/bind  — привязать датчик к плате помещения (или переименовать);
  DELETE /registry/sensors/{serial}/bind  — отвязать.
Помещение с платой создаётся через POST /rooms {"name", "board_id"} (app/home.py).
"""
from __future__ import annotations

import sqlite3
import time

from fastapi import APIRouter, Depends, HTTPException, Path
from pydantic import BaseModel, Field, field_validator

from app import access, db, insights
from app.schemas import normalize_text

STALE_AFTER_SECONDS = 180
SERIAL = r"^[A-Za-z0-9_.\-]{1,32}$"

_metric_cache: tuple[float, frozenset[str]] = (0.0, frozenset())


def known_metrics() -> frozenset[str]:
    """Коды показателей из metric_types (кэш 30 с — проверка на каждый POST /telemetry)."""
    global _metric_cache
    stamp, codes = _metric_cache
    if time.monotonic() - stamp < 30 and codes:
        return codes
    conn = db.connect()
    try:
        codes = frozenset(r["code"] for r in conn.execute("SELECT code FROM metric_types"))
    except sqlite3.OperationalError:  # миграции ещё не применены
        codes = frozenset()
    finally:
        conn.close()
    _metric_cache = (time.monotonic(), codes)
    return codes


def check_readings(types: list[str]) -> None:
    known = known_metrics()
    if not known:
        return
    unknown = sorted({t for t in types if t not in known})
    if unknown:
        raise HTTPException(
            status_code=422,
            detail=f"Неизвестный показатель: {', '.join(unknown)}. Добавьте его в metric_types миграцией",
        )


class BindIn(BaseModel):
    room_id: int = Field(ge=1)
    name: str | None = Field(default=None, max_length=40)

    @field_validator("name")
    @classmethod
    def _name(cls, value: str | None) -> str | None:
        value = normalize_text(value or "")
        return value or None


def _types(conn: sqlite3.Connection) -> dict[str, dict]:
    metrics = {r["code"]: dict(r) for r in conn.execute(
        "SELECT code, name, unit, position FROM metric_types ORDER BY position, code")}
    kinds = {r["code"]: {**dict(r), "metrics": []} for r in conn.execute(
        "SELECT code, name, short_name, default_name, model, icon FROM sensor_types ORDER BY code")}
    for r in conn.execute(
        "SELECT stm.sensor_type, stm.metric FROM sensor_type_metrics stm"
        " JOIN metric_types mt ON mt.code = stm.metric ORDER BY mt.position"
    ):
        if r["sensor_type"] in kinds and r["metric"] in metrics:
            m = metrics[r["metric"]]
            kinds[r["sensor_type"]]["metrics"].append({"code": m["code"], "name": m["name"], "unit": m["unit"]})
    return {"metrics": metrics, "kinds": kinds}


def _latest(conn: sqlite3.Connection, device_id: str | None) -> tuple[dict[str, float], str | None]:
    if not device_id:
        return {}, None
    values, last = {}, None
    for row in db.last_measurements(conn, device_id):
        values[row["type"]] = row["value"]
        last = max(last or "", row["measured_at"])
    return values, last


def _online(last_seen: str | None) -> tuple[int | None, bool]:
    age = insights.age_seconds(last_seen)
    return age, age is not None and age <= STALE_AFTER_SECONDS


def _board_rows(conn: sqlite3.Connection, uid: int) -> list[sqlite3.Row]:
    return conn.execute(
        """
        SELECT d.id AS device_id, d.serial, d.title, d.model, d.room_id,
               r.name AS room_name, r.owner_id AS room_owner,
               (SELECT role FROM device_members WHERE device_id = d.id AND user_id = ?) AS role,
               EXISTS(SELECT 1 FROM device_members WHERE device_id = d.id) AS claimed,
               (SELECT COUNT(*) FROM sensor_registry sr WHERE sr.device_id = d.id) AS sensors
        FROM devices d LEFT JOIN rooms r ON r.id = d.room_id
        WHERE d.serial IS NOT NULL
        ORDER BY d.serial
        """,
        (uid,),
    ).fetchall()


def board_title(row) -> str:
    return row["title"] or (f"Плата № {row['serial']}" if row["serial"] else row["device_id"])


def board_item(conn: sqlite3.Connection, row, uid: int) -> dict:
    mine = row["role"] is not None
    if mine:
        status = "mine"
    elif row["claimed"]:
        status = "taken"
    else:
        status = "free"
    _, last = _latest(conn, row["device_id"])
    age, online = _online(last)
    item = {
        "device_id": row["device_id"],
        "serial": row["serial"],
        "title": board_title(row),
        "model": row["model"],
        "status": status,
        # Для нового помещения можно взять свободную плату или свою, оставшуюся без помещения.
        "selectable": status == "free" or (row["role"] == "owner" and row["room_id"] is None),
        "room": None,
        "sensors": row["sensors"] if mine else None,
        "last_seen": last if mine or status == "free" else None,
        "age_seconds": age if mine or status == "free" else None,
        "online": online if mine or status == "free" else None,
    }
    if mine and row["room_id"] is not None:
        item["room"] = {"id": row["room_id"], "name": row["room_name"]}
    return item


def _sensor_rows(conn: sqlite3.Connection, uid: int) -> list[sqlite3.Row]:
    return conn.execute(
        """
        SELECT sr.serial, sr.sensor_type, sr.device_id, sr.name, sr.bound_at,
               d.serial AS board_serial, d.title AS board_title, d.room_id, r.name AS room_name,
               (SELECT role FROM device_members WHERE device_id = sr.device_id AND user_id = ?) AS role
        FROM sensor_registry sr
        LEFT JOIN devices d ON d.id = sr.device_id
        LEFT JOIN rooms r ON r.id = d.room_id
        ORDER BY (sr.device_id IS NOT NULL), sr.serial
        """,
        (uid,),
    ).fetchall()


def sensor_item(conn: sqlite3.Connection, row, kinds: dict[str, dict]) -> dict:
    kind = kinds.get(row["sensor_type"]) or {
        "code": row["sensor_type"], "name": row["sensor_type"], "short_name": row["sensor_type"],
        "default_name": row["sensor_type"], "model": None, "icon": "sensor", "metrics": []}
    if row["device_id"] is None:
        status = "free"
    elif row["role"] is not None:
        status = "mine"
    else:
        status = "taken"
    item = {
        "serial": row["serial"],
        "type": kind["code"],
        "type_name": kind["name"],
        "short_name": kind["short_name"],
        "default_name": kind["default_name"],
        "model": kind["model"],
        "icon": kind["icon"],
        "status": status,
        "name": None,
        "board": None,
        "room": None,
        "metrics": [{**m, "value": None} for m in kind["metrics"]],
        "last_seen": None,
        "online": None,
    }
    if status == "mine":
        rows = db.last_measurements(conn, row["device_id"])
        values = {r["type"]: r["value"] for r in rows}
        # Последнее время именно по показателям этого датчика, а не всей платы.
        codes = {m["code"] for m in kind["metrics"]}
        stamps = [r["measured_at"] for r in rows if r["type"] in codes]
        last = max(stamps) if stamps else None
        _, online = _online(last)
        item.update({
            "name": row["name"] or kind["default_name"],
            "board": {"device_id": row["device_id"], "serial": row["board_serial"],
                      "title": row["board_title"] or f"Плата № {row['board_serial']}"},
            "room": {"id": row["room_id"], "name": row["room_name"]} if row["room_id"] is not None else None,
            "metrics": [{**m, "value": values.get(m["code"])} for m in kind["metrics"]],
            "last_seen": last,
            "online": online,
        })
    return item


def build_router(current_user) -> APIRouter:
    router = APIRouter()

    @router.get("/registry/types")
    def registry_types(user=Depends(current_user)):
        conn = db.connect()
        try:
            t = _types(conn)
        finally:
            conn.close()
        return {"metrics": list(t["metrics"].values()), "sensor_types": list(t["kinds"].values())}

    @router.get("/registry/boards")
    def registry_boards(user=Depends(current_user)):
        uid = int(user["id"])
        conn = db.connect()
        try:
            boards = [board_item(conn, r, uid) for r in _board_rows(conn, uid)]
        finally:
            conn.close()
        return {
            "count": len(boards),
            "free": sum(1 for b in boards if b["selectable"]),
            "boards": boards,
        }

    @router.get("/registry/sensors")
    def registry_sensors(user=Depends(current_user)):
        uid = int(user["id"])
        conn = db.connect()
        try:
            kinds = _types(conn)["kinds"]
            sensors = [sensor_item(conn, r, kinds) for r in _sensor_rows(conn, uid)]
        finally:
            conn.close()
        return {
            "count": len(sensors),
            "free": sum(1 for s in sensors if s["status"] == "free"),
            "sensors": sensors,
        }

    @router.post("/registry/sensors/{serial}/bind")
    def bind_sensor(body: BindIn, serial: str = Path(pattern=SERIAL), user=Depends(current_user)):
        uid = int(user["id"])
        conn = db.connect()
        try:
            sensor = conn.execute("SELECT * FROM sensor_registry WHERE serial = ?", (serial,)).fetchone()
            if sensor is None:
                raise HTTPException(status_code=404, detail="Датчик не найден в базе сервера")
            room = conn.execute("SELECT id, owner_id, name FROM rooms WHERE id = ?", (body.room_id,)).fetchone()
            if room is None or room["owner_id"] != uid:
                raise HTTPException(status_code=404, detail="Помещение не найдено")
            board = conn.execute(
                "SELECT id, serial, title FROM devices WHERE room_id = ? AND serial IS NOT NULL ORDER BY serial LIMIT 1",
                (body.room_id,),
            ).fetchone()
            if board is None:
                raise HTTPException(status_code=409, detail="У помещения нет платы — выберите плату для помещения")
            access.require_owner(conn, board["id"], user)
            if sensor["device_id"] is not None and sensor["device_id"] != board["id"]:
                raise HTTPException(status_code=409, detail="Датчик уже привязан к другой плате")
            same = conn.execute(
                "SELECT serial FROM sensor_registry WHERE device_id = ? AND sensor_type = ? AND serial != ?",
                (board["id"], sensor["sensor_type"], serial),
            ).fetchone()
            if same is not None:
                # Показания приходят по показателям платы — два одинаковых датчика на одной плате
                # писали бы в одни и те же записи.
                raise HTTPException(status_code=409,
                                    detail=f"На плате уже есть такой датчик (№ {same['serial']})")
            conn.execute(
                "UPDATE sensor_registry SET device_id = ?, name = COALESCE(?, name),"
                " bound_at = COALESCE(bound_at, ?) WHERE serial = ?",
                (board["id"], body.name, db.utc_now(), serial),
            )
            conn.commit()
            kinds = _types(conn)["kinds"]
            row = next(r for r in _sensor_rows(conn, uid) if r["serial"] == serial)
            return sensor_item(conn, row, kinds)
        finally:
            conn.close()

    @router.delete("/registry/sensors/{serial}/bind")
    def unbind_sensor(serial: str = Path(pattern=SERIAL), user=Depends(current_user)):
        conn = db.connect()
        try:
            sensor = conn.execute("SELECT device_id FROM sensor_registry WHERE serial = ?", (serial,)).fetchone()
            if sensor is None:
                raise HTTPException(status_code=404, detail="Датчик не найден в базе сервера")
            if sensor["device_id"] is not None:
                access.require_owner(conn, sensor["device_id"], user)
                conn.execute("UPDATE sensor_registry SET device_id = NULL, name = NULL, bound_at = NULL"
                             " WHERE serial = ?", (serial,))
                conn.commit()
        finally:
            conn.close()
        return {"status": "ok"}

    return router
