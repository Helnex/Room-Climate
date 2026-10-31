from __future__ import annotations

from contextlib import asynccontextmanager

import sqlite3

from fastapi import FastAPI, Header, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from redis.exceptions import RedisError

from app import auth, db
from app.config import settings
from app.db import utc_now
from app.redis_store import enqueue, read_current, redis_client, write_current
from app.schemas import LoginIn, RegisterIn, SettingsIn, TelemetryIn


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


def _normalize_email(email: str) -> str:
    return email.strip().lower()


def _bearer_token(authorization: str | None) -> str:
    if not authorization:
        raise HTTPException(status_code=401, detail="Нужен заголовок Authorization")
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise HTTPException(status_code=401, detail="Ожидается Bearer-токен")
    return token.strip()


def _auth_response(user_row, token: str) -> dict:
    payload = auth.user_payload(user_row)
    payload["token"] = token
    return payload


@app.post("/auth/register")
def register(body: RegisterIn):
    email = _normalize_email(body.email)
    if "@" not in email:
        raise HTTPException(status_code=400, detail="Некорректный email")
    name = body.name.strip()
    surname = body.surname.strip()
    if not name or not surname:
        raise HTTPException(status_code=400, detail="Укажите имя и фамилию")

    conn = db.connect()
    try:
        if db.get_user_by_email(conn, email) is not None:
            raise HTTPException(status_code=409, detail="Пользователь с таким email уже есть")
        user = db.create_user(conn, email, auth.hash_password(body.password), name, surname)
        token = auth.new_token()
        db.create_session(conn, int(user["id"]), token)
        conn.commit()
        return _auth_response(user, token)
    except HTTPException:
        conn.rollback()
        raise
    except sqlite3.IntegrityError as exc:
        conn.rollback()
        raise HTTPException(status_code=409, detail="Пользователь с таким email уже есть") from exc
    finally:
        conn.close()


@app.post("/auth/login")
def login(body: LoginIn):
    email = _normalize_email(body.email)
    conn = db.connect()
    try:
        user = db.get_user_by_email(conn, email)
        if user is None or not auth.verify_password(body.password, user["password_hash"]):
            raise HTTPException(status_code=401, detail="Неверный email или пароль")
        token = auth.new_token()
        db.create_session(conn, int(user["id"]), token)
        conn.commit()
        return _auth_response(user, token)
    finally:
        conn.close()


@app.get("/auth/me")
def me(authorization: str | None = Header(default=None)):
    token = _bearer_token(authorization)
    conn = db.connect()
    try:
        user = db.get_user_by_token(conn, token)
    finally:
        conn.close()
    if user is None:
        raise HTTPException(status_code=401, detail="Сессия недействительна")
    return auth.user_payload(user)


@app.post("/auth/logout")
def logout(authorization: str | None = Header(default=None)):
    token = _bearer_token(authorization)
    conn = db.connect()
    try:
        db.delete_session(conn, token)
        conn.commit()
    finally:
        conn.close()
    return {"status": "ok"}


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
