from __future__ import annotations

import sqlite3
from datetime import datetime, timezone
from pathlib import Path

from app.config import settings


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def connect() -> sqlite3.Connection:
    path = Path(settings.sqlite_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def init_db() -> None:
    conn = connect()
    try:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS devices (
                id TEXT PRIMARY KEY,
                name TEXT,
                created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS sensors (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                type TEXT NOT NULL,
                unit TEXT,
                name TEXT,
                UNIQUE(device_id, type),
                FOREIGN KEY(device_id) REFERENCES devices(id)
            );
            CREATE TABLE IF NOT EXISTS measurements (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sensor_id INTEGER NOT NULL,
                value REAL NOT NULL,
                measured_at TEXT NOT NULL,
                FOREIGN KEY(sensor_id) REFERENCES sensors(id)
            );
            CREATE INDEX IF NOT EXISTS idx_meas_sensor_time
                ON measurements(sensor_id, measured_at);
            CREATE TABLE IF NOT EXISTS settings (
                device_id TEXT PRIMARY KEY,
                temp_min REAL NOT NULL,
                temp_max REAL NOT NULL,
                FOREIGN KEY(device_id) REFERENCES devices(id)
            );
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                email TEXT NOT NULL UNIQUE COLLATE NOCASE,
                password_hash TEXT NOT NULL,
                name TEXT NOT NULL,
                surname TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS sessions (
                token TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
            );
            CREATE INDEX IF NOT EXISTS idx_sessions_user
                ON sessions(user_id);
            """
        )
        conn.commit()
    finally:
        conn.close()


def create_user(
    conn: sqlite3.Connection,
    email: str,
    password_hash: str,
    name: str,
    surname: str,
) -> sqlite3.Row:
    conn.execute(
        """
        INSERT INTO users(email, password_hash, name, surname, created_at)
        VALUES (?, ?, ?, ?, ?)
        """,
        (email, password_hash, name, surname, utc_now()),
    )
    row = conn.execute(
        "SELECT id, email, name, surname, created_at FROM users WHERE email = ? COLLATE NOCASE",
        (email,),
    ).fetchone()
    assert row is not None
    return row


def get_user_by_email(conn: sqlite3.Connection, email: str) -> sqlite3.Row | None:
    return conn.execute(
        """
        SELECT id, email, password_hash, name, surname, created_at
        FROM users WHERE email = ? COLLATE NOCASE
        """,
        (email,),
    ).fetchone()


def get_user_by_id(conn: sqlite3.Connection, user_id: int) -> sqlite3.Row | None:
    return conn.execute(
        "SELECT id, email, name, surname, created_at FROM users WHERE id = ?",
        (user_id,),
    ).fetchone()


def create_session(conn: sqlite3.Connection, user_id: int, token: str) -> None:
    conn.execute(
        "INSERT INTO sessions(token, user_id, created_at) VALUES (?, ?, ?)",
        (token, user_id, utc_now()),
    )


def get_user_by_token(conn: sqlite3.Connection, token: str) -> sqlite3.Row | None:
    return conn.execute(
        """
        SELECT u.id, u.email, u.name, u.surname, u.created_at
        FROM sessions s
        JOIN users u ON u.id = s.user_id
        WHERE s.token = ?
        """,
        (token,),
    ).fetchone()


def delete_session(conn: sqlite3.Connection, token: str) -> None:
    conn.execute("DELETE FROM sessions WHERE token = ?", (token,))


def ensure_device_and_sensor(conn: sqlite3.Connection, device_id: str, sensor_type: str, unit: str) -> int:
    conn.execute(
        "INSERT OR IGNORE INTO devices(id, name, created_at) VALUES (?, ?, ?)",
        (device_id, device_id, utc_now()),
    )
    conn.execute(
        """
        INSERT OR IGNORE INTO sensors(device_id, type, unit, name)
        VALUES (?, ?, ?, ?)
        """,
        (device_id, sensor_type, unit, sensor_type),
    )
    if unit:
        conn.execute(
            "UPDATE sensors SET unit = ? WHERE device_id = ? AND type = ?",
            (unit, device_id, sensor_type),
        )
    row = conn.execute(
        "SELECT id FROM sensors WHERE device_id = ? AND type = ?",
        (device_id, sensor_type),
    ).fetchone()
    return int(row["id"])


def insert_measurement(conn: sqlite3.Connection, sensor_id: int, value: float, measured_at: str) -> None:
    conn.execute(
        "INSERT INTO measurements(sensor_id, value, measured_at) VALUES (?, ?, ?)",
        (sensor_id, value, measured_at),
    )


def last_measurements(conn: sqlite3.Connection, device_id: str) -> list[sqlite3.Row]:
    return conn.execute(
        """
        SELECT s.type, s.unit, m.value, m.measured_at
        FROM sensors s
        JOIN measurements m ON m.sensor_id = s.id
        WHERE s.device_id = ?
          AND m.id IN (
              SELECT MAX(m2.id) FROM measurements m2
              JOIN sensors s2 ON s2.id = m2.sensor_id
              WHERE s2.device_id = ?
              GROUP BY s2.type
          )
        """,
        (device_id, device_id),
    ).fetchall()


def history(
    conn: sqlite3.Connection,
    device_id: str,
    date_from: str | None,
    date_to: str | None,
    sensor_type: str | None,
) -> list[sqlite3.Row]:
    sql = """
        SELECT s.type, s.unit, m.value, m.measured_at
        FROM measurements m
        JOIN sensors s ON s.id = m.sensor_id
        WHERE s.device_id = ?
    """
    args: list[object] = [device_id]
    if sensor_type:
        sql += " AND s.type = ?"
        args.append(sensor_type)
    if date_from:
        sql += " AND m.measured_at >= ?"
        args.append(date_from)
    if date_to:
        sql += " AND m.measured_at <= ?"
        args.append(date_to)
    sql += " ORDER BY m.measured_at ASC"
    return conn.execute(sql, args).fetchall()


def get_settings(conn: sqlite3.Connection, device_id: str) -> sqlite3.Row | None:
    return conn.execute(
        "SELECT device_id, temp_min, temp_max FROM settings WHERE device_id = ?",
        (device_id,),
    ).fetchone()


def put_settings(conn: sqlite3.Connection, device_id: str, temp_min: float, temp_max: float) -> None:
    conn.execute(
        "INSERT OR IGNORE INTO devices(id, name, created_at) VALUES (?, ?, ?)",
        (device_id, device_id, utc_now()),
    )
    conn.execute(
        """
        INSERT INTO settings(device_id, temp_min, temp_max) VALUES (?, ?, ?)
        ON CONFLICT(device_id) DO UPDATE SET temp_min = excluded.temp_min, temp_max = excluded.temp_max
        """,
        (device_id, temp_min, temp_max),
    )
