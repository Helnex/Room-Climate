from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from redis.exceptions import RedisError

from app import db
from app.config import settings
from app.db import utc_now
from app.redis_store import enqueue, read_current, redis_client, write_current
from app.schemas import SettingsIn, TelemetryIn


@asynccontextmanager
async def lifespan(_app: FastAPI):
    db.init_db()
    yield


app = FastAPI(title="RoomClimate API", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
def health():
    redis_ok = False
    try:
        redis_ok = bool(redis_client().ping())
    except RedisError:
        redis_ok = False
    sqlite_ok = False
    try:
        conn = db.connect()
        conn.execute("SELECT 1")
        conn.close()
        sqlite_ok = True
    except Exception:
        sqlite_ok = False
    status = "ok" if redis_ok and sqlite_ok else "degraded"
    return {"status": status, "redis": redis_ok, "sqlite": sqlite_ok}


@app.post("/telemetry")
def post_telemetry(body: TelemetryIn):
    measured_at = utc_now()
    try:
        r = redis_client()
        r.ping()
    except RedisError as exc:
        raise HTTPException(status_code=503, detail="Redis недоступен") from exc

    stored = []
    for item in body.readings:
        payload = {
            "value": item.value,
            "unit": item.unit,
            "measured_at": measured_at,
            "device_id": body.device_id,
        }
        write_current(r, body.device_id, item.type, payload)
        enqueue(r, body.device_id, item.type, item.value, item.unit, measured_at)
        stored.append({"type": item.type, "value": item.value, "unit": item.unit})
    return {"status": "ok", "device_id": body.device_id, "measured_at": measured_at, "stored": stored}


@app.get("/telemetry/current")
def get_current(device_id: str = Query(default="pi-1")):
    try:
        r = redis_client()
        current = read_current(r, device_id)
    except RedisError as exc:
        raise HTTPException(status_code=503, detail="Redis недоступен") from exc

    if current:
        return {"device_id": device_id, "source": "redis", "readings": current}

    conn = db.connect()
    try:
        rows = db.last_measurements(conn, device_id)
    finally:
        conn.close()
    if not rows:
        raise HTTPException(status_code=404, detail="Нет данных")

    restored = {}
    try:
        r = redis_client()
    except RedisError:
        r = None
    for row in rows:
        payload = {
            "value": row["value"],
            "unit": row["unit"],
            "measured_at": row["measured_at"],
            "device_id": device_id,
        }
        restored[row["type"]] = payload
        if r is not None:
            write_current(r, device_id, row["type"], payload)
    return {"device_id": device_id, "source": "sqlite", "readings": restored}


@app.get("/telemetry/history")
def get_history(
    device_id: str = Query(default="pi-1"),
    date_from: str | None = Query(default=None, alias="from"),
    date_to: str | None = Query(default=None, alias="to"),
    type: str | None = None,
):
    conn = db.connect()
    try:
        rows = db.history(conn, device_id, date_from, date_to, type)
        points = [
            {
                "type": row["type"],
                "unit": row["unit"],
                "value": row["value"],
                "measured_at": row["measured_at"],
            }
            for row in rows
        ]
    finally:
        conn.close()
    return {"device_id": device_id, "count": len(points), "points": points}


@app.get("/settings")
def get_settings(device_id: str = Query(default="pi-1")):
    conn = db.connect()
    try:
        row = db.get_settings(conn, device_id)
    finally:
        conn.close()
    if row is None:
        return {"device_id": device_id, "temp_min": 18.0, "temp_max": 26.0, "saved": False}
    return {
        "device_id": row["device_id"],
        "temp_min": row["temp_min"],
        "temp_max": row["temp_max"],
        "saved": True,
    }


@app.put("/settings")
def put_settings(body: SettingsIn, device_id: str = Query(default="pi-1")):
    if body.temp_min >= body.temp_max:
        raise HTTPException(status_code=400, detail="temp_min должен быть меньше temp_max")
    conn = db.connect()
    try:
        db.put_settings(conn, device_id, body.temp_min, body.temp_max)
        conn.commit()
    finally:
        conn.close()
    return {
        "device_id": device_id,
        "temp_min": body.temp_min,
        "temp_max": body.temp_max,
        "saved": True,
    }


@app.get("/")
def root():
    return {
        "name": "RoomClimate API",
        "docs": "/docs",
        "health": "/health",
        "ttl_seconds": settings.current_ttl_seconds,
    }
