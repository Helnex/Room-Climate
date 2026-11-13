from __future__ import annotations

import hashlib
import hmac
import logging
import os
import sqlite3
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from redis.exceptions import RedisError
from starlette.exceptions import HTTPException as StarletteHTTPException

from app import access, auth, db, extras, home, proxy, recovery
from app.config import settings
from app.db import utc_now
from app.errors import translate_http_detail, validation_message
from app.redis_store import enqueue, read_current, redis_client, write_current
from app.schemas import LoginIn, RegisterIn, SettingsIn, TelemetryIn

log = logging.getLogger("roomclimate")


@asynccontextmanager
async def lifespan(_app: FastAPI):
    db.init_db()
    if not settings.ingest_token:
        log.warning(
            "INGEST_TOKEN не задан: POST /telemetry принимает данные от любого устройства в сети"
        )
    yield


app = FastAPI(title="RoomClimate API", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# ---------- Ошибки на русском ----------

@app.exception_handler(RequestValidationError)
async def _validation_handler(_request: Request, exc: RequestValidationError):
    # Раньше клиент получал английский список pydantic-ошибок и показывал его сырым JSON.
    return JSONResponse(status_code=422, content={"detail": validation_message(exc.errors())})


@app.exception_handler(StarletteHTTPException)
async def _http_handler(_request: Request, exc: StarletteHTTPException):
    return JSONResponse(
        status_code=exc.status_code,
        content={"detail": translate_http_detail(exc.status_code, exc.detail)},
        headers=getattr(exc, "headers", None),
    )


@app.exception_handler(Exception)
async def _unhandled_handler(_request: Request, exc: Exception):
    log.exception("Необработанная ошибка: %s", exc)
    return JSONResponse(status_code=500, content={"detail": "Внутренняя ошибка сервера"})


# ---------- Авторизация ----------

def _bearer_token(authorization: str | None) -> str:
    if not authorization:
        raise HTTPException(status_code=401, detail="Нужен заголовок Authorization")
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise HTTPException(status_code=401, detail="Ожидается Bearer-токен")
    return token.strip()


def current_user(authorization: str | None = Header(default=None)):
    """Зависимость для эндпоинтов, доступных только вошедшему пользователю."""
    token = _bearer_token(authorization)
    conn = db.connect()
    try:
        user = db.get_user_by_token(conn, auth.hash_token(token), settings.session_ttl_days)
    finally:
        conn.close()
    if user is None:
        raise HTTPException(status_code=401, detail="Сессия недействительна, войдите заново")
    return user


def device_access(x_device_token: str | None = Header(default=None)) -> None:
    """Если на сервере задан INGEST_TOKEN, плата обязана прислать его в X-Device-Token."""
    expected = settings.ingest_token
    if not expected:
        return
    if not x_device_token or not hmac.compare_digest(x_device_token, expected):
        raise HTTPException(status_code=401, detail="Неверный токен устройства")


def _new_session(conn: sqlite3.Connection, user_id: int, request: Request | None = None) -> str:
    token = auth.new_token()
    db.create_session(conn, user_id, auth.hash_token(token))
    if request is not None:
        home.remember_session_client(conn, auth.hash_token(token), request)  # «История входов»
    return token


def _auth_response(user_row, token: str) -> dict:
    payload = auth.user_payload(user_row)
    payload["token"] = token
    return payload


# ---------- Ограничение попыток входа ----------

def _failures_key(email: str) -> str:
    digest = hashlib.sha256(email.encode("utf-8")).hexdigest()[:32]
    return f"login_fail:{digest}"


def _login_attempts_exceeded(email: str) -> bool:
    try:
        raw = redis_client().get(_failures_key(email))
    except RedisError:
        return False  # Redis упал — не блокируем вход полностью
    return raw is not None and int(raw) >= settings.login_max_failures_per_email


def _register_login_failure(email: str) -> None:
    try:
        r = redis_client()
        key = _failures_key(email)
        count = r.incr(key)
        if count == 1:
            r.expire(key, settings.login_failure_window_seconds)
    except RedisError:
        pass


IP_AUTH_LIMIT = 40          # попыток входа/регистрации
IP_AUTH_WINDOW_SECONDS = 60  # за минуту с одного адреса


def _check_ip_rate(request: Request) -> None:
    """Лимит по IP: лимит по email не спасает от перебора по разным адресам, а каждая
    попытка — это 600 000 итераций PBKDF2 (≈0,3 с процессора)."""
    # Через туннель все запросы приходят с адреса cloudflared — берём настоящий IP клиента.
    ip = proxy.client_ip(request)
    key = f"auth_ip:{hashlib.sha256(ip.encode('utf-8')).hexdigest()[:32]}"
    try:
        r = redis_client()
        count = r.incr(key)
        if count == 1:
            r.expire(key, IP_AUTH_WINDOW_SECONDS)
    except RedisError:
        return
    if count > IP_AUTH_LIMIT:
        raise HTTPException(status_code=429, detail="Слишком много запросов. Подождите минуту.")


def _reset_login_failures(email: str) -> None:
    try:
        redis_client().delete(_failures_key(email))
    except RedisError:
        pass


# ---------- Аккаунт ----------

def _check_invite(body: RegisterIn, request: Request) -> None:
    """Из интернета регистрируются только по коду приглашения (REGISTER_INVITE_CODE в .env),
    иначе аккаунт мог бы завести любой, кто нашёл адрес. В локальной сети стенда код не нужен."""
    if not proxy.via_tunnel(request):
        return
    expected = os.environ.get("REGISTER_INVITE_CODE", "").strip().upper()
    if not expected:
        raise HTTPException(status_code=403, detail="Регистрация из интернета закрыта. "
                                                    "Зарегистрируйтесь в домашней сети стенда.")
    given = (body.invite_code or "").strip().upper()
    if not hmac.compare_digest(given.encode("utf-8"), expected.encode("utf-8")):
        raise HTTPException(status_code=403, detail="Неверный код приглашения. "
                                                    "Спросите код у владельца стенда.")


@app.post("/auth/register")
def register(body: RegisterIn, request: Request):
    _check_ip_rate(request)  # заодно ограничивает перебор кода приглашения
    _check_invite(body, request)
    conn = db.connect()
    try:
        if db.get_user_by_email(conn, body.email) is not None:
            raise HTTPException(status_code=409, detail="Пользователь с таким email уже есть")
        user = db.create_user(conn, body.email, auth.hash_password(body.password), body.name, body.surname)
        token = _new_session(conn, int(user["id"]), request)
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
def login(body: LoginIn, request: Request):
    _check_ip_rate(request)
    email = body.email
    if _login_attempts_exceeded(email):
        minutes = max(1, settings.login_failure_window_seconds // 60)
        raise HTTPException(
            status_code=429,
            detail=f"Слишком много неудачных попыток входа. Попробуйте через {minutes} мин.",
        )
    conn = db.connect()
    try:
        user = db.get_user_by_email(conn, email)
        if user is None:
            auth.burn_password_check(body.password)
            ok = False
        else:
            ok = auth.verify_password(body.password, user["password_hash"])
        if not ok:
            _register_login_failure(email)
            raise HTTPException(status_code=401, detail="Неверный email или пароль")
        _reset_login_failures(email)
        db.delete_expired_sessions(conn, settings.session_ttl_days)
        token = _new_session(conn, int(user["id"]), request)
        conn.commit()
        return _auth_response(user, token)
    finally:
        conn.close()


@app.get("/auth/me")
def me(user=Depends(current_user)):
    return auth.user_payload(user)


@app.post("/auth/logout")
def logout(authorization: str | None = Header(default=None)):
    token = _bearer_token(authorization)
    conn = db.connect()
    try:
        db.delete_session(conn, auth.hash_token(token))
        conn.commit()
    finally:
        conn.close()
    return {"status": "ok"}


# ---------- Служебное ----------

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


# ---------- Телеметрия ----------

@app.post("/telemetry", dependencies=[Depends(device_access)])
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
def get_current(device_id: str = Query(default="pi-1", max_length=64), user=Depends(current_user)):
    access.check(device_id, user)
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
            try:
                write_current(r, device_id, row["type"], payload)
            except RedisError:
                r = None
    return {"device_id": device_id, "source": "sqlite", "readings": restored}


@app.get("/telemetry/history")
def get_history(
    user=Depends(current_user),
    device_id: str = Query(default="pi-1", max_length=64),
    date_from: str | None = Query(default=None, alias="from", max_length=64),
    date_to: str | None = Query(default=None, alias="to", max_length=64),
    type: str | None = Query(default=None, max_length=64),
    max_points: int = Query(default=2000, ge=10, le=20000),
):
    access.check(device_id, user)
    conn = db.connect()
    try:
        # Не больше max_points точек на датчик: длинные периоды усредняются по интервалам.
        rows = db.history_compact(conn, device_id, date_from, date_to, type, max_points)
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


# ---------- Пороги ----------

@app.get("/settings")
def get_settings(device_id: str = Query(default="pi-1", max_length=64), user=Depends(current_user)):
    access.check(device_id, user)
    conn = db.connect()
    try:
        row = db.get_settings(conn, device_id)
    finally:
        conn.close()
    if row is None:
        return {
            "device_id": device_id,
            "temp_min": 18.0,
            "temp_max": 26.0,
            "humidity_min": 30.0,
            "humidity_max": 70.0,
            "saved": False,
        }
    return {
        "device_id": row["device_id"],
        "temp_min": row["temp_min"],
        "temp_max": row["temp_max"],
        "humidity_min": row["humidity_min"] if row["humidity_min"] is not None else 30.0,
        "humidity_max": row["humidity_max"] if row["humidity_max"] is not None else 70.0,
        "saved": True,
    }


@app.put("/settings")
def put_settings(
    body: SettingsIn,
    device_id: str = Query(default="pi-1", min_length=1, max_length=64),
    user=Depends(current_user),
):
    access.check(device_id, user)
    if body.temp_min >= body.temp_max:
        raise HTTPException(
            status_code=400,
            detail="Минимальная температура должна быть меньше максимальной",
        )
    if body.humidity_min >= body.humidity_max:
        raise HTTPException(
            status_code=400,
            detail="Минимальная влажность должна быть меньше максимальной",
        )
    conn = db.connect()
    try:
        db.put_settings(
            conn,
            device_id,
            body.temp_min,
            body.temp_max,
            body.humidity_min,
            body.humidity_max,
        )
        conn.commit()
    finally:
        conn.close()
    return {
        "device_id": device_id,
        "temp_min": body.temp_min,
        "temp_max": body.temp_max,
        "humidity_min": body.humidity_min,
        "humidity_max": body.humidity_max,
        "saved": True,
    }


# Аналитика, устройства, экспорт CSV, страница подключения — app/extras.py
app.include_router(extras.build_router(current_user))
# Восстановление пароля по коду — app/recovery.py
app.include_router(recovery.build_router())
# Помещения, датчики, профиль, семья, безопасность, помощь — app/home.py
app.include_router(home.build_router(current_user))


@app.get("/")
def root():
    return {
        "name": "RoomClimate API",
        "docs": "/docs",
        "health": "/health",
        "ttl_seconds": settings.current_ttl_seconds,
    }
