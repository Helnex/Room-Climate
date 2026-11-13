"""Восстановление пароля по одноразовому коду.

POST /auth/password/forgot {email}             -> всегда 200 (нельзя узнать, есть ли такой email)
POST /auth/password/reset  {email, code, password} -> новый пароль, все сессии пользователя закрыты

Код — 6 цифр, живёт 15 минут, не больше 5 попыток ввода, в БД хранится только хэш.
Доставка:
- задан SMTP_HOST (см. server/.env.example) — письмо на email пользователя;
- почты нет (учебный стенд в локальной сети) — код пишется в журнал сервера, его выдаёт
  администратор стенда: `docker logs roomclimate-api-1 | findstr "Код восстановления"`.
"""
from __future__ import annotations

import hashlib
import hmac
import logging
import os
import secrets
import smtplib
import sqlite3
from datetime import datetime, timedelta, timezone
from email.message import EmailMessage

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field, field_validator
from redis.exceptions import RedisError

from app import auth, db
from app.redis_store import redis_client
from app.schemas import normalize_email

log = logging.getLogger("roomclimate.recovery")

CODE_TTL_MINUTES = 15
MAX_ATTEMPTS = 5
MAX_CODES_PER_HOUR = 3

SCHEMA = """
CREATE TABLE IF NOT EXISTS password_resets (
    user_id INTEGER PRIMARY KEY,
    code_hash TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);
"""


def init(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA)


def _code_hash(user_id: int, code: str) -> str:
    return hashlib.sha256(f"{user_id}:{code}".encode("utf-8")).hexdigest()


def _now() -> datetime:
    return datetime.now(timezone.utc).replace(microsecond=0)


class ForgotIn(BaseModel):
    email: str = Field(min_length=3, max_length=128)

    @field_validator("email")
    @classmethod
    def _clean(cls, value: str) -> str:
        return normalize_email(value)


class ResetIn(BaseModel):
    email: str = Field(min_length=3, max_length=128)
    code: str = Field(pattern=r"^\d{6}$")
    password: str = Field(min_length=6, max_length=128)

    @field_validator("email")
    @classmethod
    def _clean(cls, value: str) -> str:
        return normalize_email(value)

    @field_validator("password")
    @classmethod
    def _check(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("Пароль не может состоять из одних пробелов")
        return value


def _too_many_codes(email: str) -> bool:
    """Не больше MAX_CODES_PER_HOUR кодов в час на email (защита от спама письмами)."""
    key = "pwreset:" + hashlib.sha256(email.encode("utf-8")).hexdigest()[:32]
    try:
        r = redis_client()
        count = r.incr(key)
        if count == 1:
            r.expire(key, 3600)
        return count > MAX_CODES_PER_HOUR
    except RedisError:
        return False


def _deliver(email: str, name: str, code: str) -> None:
    host = os.environ.get("SMTP_HOST", "")
    if not host:
        # Учебный стенд без почты: код видит только администратор в журнале сервера.
        print(f"Код восстановления пароля для {email}: {code} (действует {CODE_TTL_MINUTES} мин)", flush=True)
        return
    msg = EmailMessage()
    msg["Subject"] = "RoomClimate: код для смены пароля"
    # Яндекс принимает письмо, только если отправитель = логин ящика.
    msg["From"] = os.environ.get("SMTP_FROM") or os.environ.get("SMTP_USER", "roomclimate@localhost")
    msg["To"] = email
    msg.set_content(
        f"Здравствуйте, {name}!\n\n"
        f"Код для смены пароля в RoomClimate: {code}\n"
        f"Код действует {CODE_TTL_MINUTES} минут. Если вы не запрашивали смену пароля, "
        f"просто проигнорируйте письмо.\n"
    )
    try:
        send_mail(msg)
    except Exception as exc:  # noqa: BLE001 — ответ клиенту не должен раскрывать сбой почты
        log.warning("письмо с кодом не отправлено: %r", exc)
        print(f"Письмо не отправлено ({exc!r}). Код восстановления для {email}: {code}", flush=True)


def send_mail(msg: EmailMessage) -> None:
    """Отправка через SMTP: порт 465 — сразу SSL (Яндекс, Mail.ru), иначе STARTTLS (587)."""
    host = os.environ["SMTP_HOST"]
    port = int(os.environ.get("SMTP_PORT", "465"))
    user = os.environ.get("SMTP_USER", "")
    if port == 465:
        smtp = smtplib.SMTP_SSL(host, port, timeout=15)
    else:
        smtp = smtplib.SMTP(host, port, timeout=15)
        smtp.starttls()
    with smtp:
        if user:
            smtp.login(user, os.environ.get("SMTP_PASSWORD", ""))
        smtp.send_message(msg)


def _send_test(to: str) -> None:
    """Пробное письмо: docker exec roomclimate-api-1 python -m app.recovery --test адрес@почта"""
    if not os.environ.get("SMTP_HOST"):
        raise SystemExit("SMTP_HOST не задан — запустите scripts/set-smtp.ps1")
    msg = EmailMessage()
    msg["Subject"] = "RoomClimate: проверка почты"
    msg["From"] = os.environ.get("SMTP_FROM") or os.environ.get("SMTP_USER", "")
    msg["To"] = to
    msg.set_content("Почта стенда RoomClimate настроена: коды восстановления пароля будут приходить письмом.\n")
    send_mail(msg)
    print(f"Пробное письмо отправлено на {to}")


if __name__ == "__main__":
    import sys

    if len(sys.argv) == 3 and sys.argv[1] == "--test":
        _send_test(sys.argv[2])
    else:
        raise SystemExit("Использование: python -m app.recovery --test адрес@почта")


def build_router() -> APIRouter:
    router = APIRouter()

    @router.post("/auth/password/forgot")
    def forgot(body: ForgotIn):
        ok = {"status": "ok", "detail": "Если такой email зарегистрирован, код отправлен."}
        if _too_many_codes(body.email):
            return ok  # тот же ответ: не раскрываем, что лимит сработал именно для этого email
        conn = db.connect()
        try:
            user = db.get_user_by_email(conn, body.email)
            if user is None:
                return ok
            code = f"{secrets.randbelow(1_000_000):06d}"
            expires = (_now() + timedelta(minutes=CODE_TTL_MINUTES)).isoformat()
            conn.execute(
                "INSERT INTO password_resets(user_id, code_hash, expires_at, attempts) VALUES (?, ?, ?, 0)"
                " ON CONFLICT(user_id) DO UPDATE SET code_hash = excluded.code_hash,"
                " expires_at = excluded.expires_at, attempts = 0",
                (int(user["id"]), _code_hash(int(user["id"]), code), expires),
            )
            conn.commit()
        finally:
            conn.close()
        _deliver(body.email, user["name"], code)
        return ok

    @router.post("/auth/password/reset")
    def reset(body: ResetIn):
        bad = HTTPException(status_code=400, detail="Неверный или просроченный код")
        conn = db.connect()
        try:
            user = db.get_user_by_email(conn, body.email)
            row = None if user is None else conn.execute(
                "SELECT code_hash, expires_at, attempts FROM password_resets WHERE user_id = ?",
                (int(user["id"]),),
            ).fetchone()
            if user is None or row is None:
                raise bad
            uid = int(user["id"])
            if row["attempts"] >= MAX_ATTEMPTS or row["expires_at"] < _now().isoformat():
                conn.execute("DELETE FROM password_resets WHERE user_id = ?", (uid,))
                conn.commit()
                raise bad
            if not hmac.compare_digest(row["code_hash"], _code_hash(uid, body.code)):
                conn.execute("UPDATE password_resets SET attempts = attempts + 1 WHERE user_id = ?", (uid,))
                conn.commit()
                raise bad
            conn.execute("UPDATE users SET password_hash = ? WHERE id = ?", (auth.hash_password(body.password), uid))
            conn.execute("DELETE FROM password_resets WHERE user_id = ?", (uid,))
            # Пароль сменён — все старые входы (возможно, чужие) закрываем.
            conn.execute("DELETE FROM sessions WHERE user_id = ?", (uid,))
            conn.commit()
        finally:
            conn.close()
        return {"status": "ok", "detail": "Пароль изменён. Войдите с новым паролем."}

    return router
