"""Доступ к устройствам: владелец и участники.

Правила:
- Плата, которую никто не закрепил за собой, видна всем вошедшим (как раньше) — чтобы
  стенд работал «из коробки».
- Первый, кто закрепит плату (POST /devices/{id}/claim), становится владельцем. После этого
  данные, пороги и выгрузки доступны только владельцу и тем, кому он дал доступ по email.
- Чужая плата отвечает 404, а не 403: так нельзя перебором узнать, какие платы существуют.
"""
from __future__ import annotations

import sqlite3

from fastapi import HTTPException

SCHEMA = """
CREATE TABLE IF NOT EXISTS device_members (
    device_id TEXT NOT NULL,
    user_id INTEGER NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('owner', 'member')),
    created_at TEXT NOT NULL,
    PRIMARY KEY (device_id, user_id),
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_device_members_user ON device_members(user_id);
"""


def init(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA)


def role(conn: sqlite3.Connection, device_id: str, user_id: int) -> str | None:
    row = conn.execute(
        "SELECT role FROM device_members WHERE device_id = ? AND user_id = ?",
        (device_id, user_id),
    ).fetchone()
    return row["role"] if row else None


def is_claimed(conn: sqlite3.Connection, device_id: str) -> bool:
    return conn.execute(
        "SELECT 1 FROM device_members WHERE device_id = ? LIMIT 1", (device_id,)
    ).fetchone() is not None


def can_access(conn: sqlite3.Connection, device_id: str, user_id: int) -> bool:
    return not is_claimed(conn, device_id) or role(conn, device_id, user_id) is not None


def require(conn: sqlite3.Connection, device_id: str, user) -> None:
    if not can_access(conn, device_id, int(user["id"])):
        raise HTTPException(status_code=404, detail="Устройство не найдено")


def require_owner(conn: sqlite3.Connection, device_id: str, user) -> None:
    if role(conn, device_id, int(user["id"])) != "owner":
        if not can_access(conn, device_id, int(user["id"])):
            raise HTTPException(status_code=404, detail="Устройство не найдено")
        raise HTTPException(status_code=403, detail="Это может сделать только владелец устройства")


def check(device_id: str, user) -> None:
    """Короткая форма для эндпоинтов: открыть БД, проверить доступ, закрыть."""
    from app import db

    conn = db.connect()
    try:
        require(conn, device_id, user)
    finally:
        conn.close()
