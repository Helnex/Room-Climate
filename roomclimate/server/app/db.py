from __future__ import annotations

import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

from app.config import settings


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def connect() -> sqlite3.Connection:
    path = Path(settings.sqlite_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, check_same_thread=False, timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    # WAL: чтение API не блокирует запись воркера и наоборот (раньше возможен «database is locked»).
    conn.execute("PRAGMA journal_mode = WAL")
    conn.execute("PRAGMA synchronous = NORMAL")
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
                humidity_min REAL NOT NULL DEFAULT 30,
                humidity_max REAL NOT NULL DEFAULT 70,
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
        _ensure_column(conn, "settings", "humidity_min", "REAL NOT NULL DEFAULT 30")
        _ensure_column(conn, "settings", "humidity_max", "REAL NOT NULL DEFAULT 70")
        from app import access, alerts  # таблицы владельцев устройств и журнала тревог

        access.init(conn)
        alerts.init(conn)
        conn.commit()
    finally:
        conn.close()


def _ensure_column(conn: sqlite3.Connection, table: str, name: str, declaration: str) -> None:
    columns = {row[1] for row in conn.execute(f"PRAGMA table_info({table})")}
    if name not in columns:
        conn.execute(f"ALTER TABLE {table} ADD COLUMN {name} {declaration}")


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


# В колонке sessions.token хранится SHA-256 токена (см. auth.hash_token), а не сам токен.
# Старые сессии с открытыми токенами просто перестанут совпадать — пользователю нужно войти заново.


def create_session(conn: sqlite3.Connection, user_id: int, token_hash: str) -> None:
    conn.execute(
        "INSERT INTO sessions(token, user_id, created_at) VALUES (?, ?, ?)",
        (token_hash, user_id, utc_now()),
    )


def _session_cutoff(ttl_days: int) -> str:
    return (datetime.now(timezone.utc) - timedelta(days=ttl_days)).replace(microsecond=0).isoformat()


def get_user_by_token(conn: sqlite3.Connection, token_hash: str, ttl_days: int) -> sqlite3.Row | None:
    return conn.execute(
        """
        SELECT u.id, u.email, u.name, u.surname, u.created_at
        FROM sessions s
        JOIN users u ON u.id = s.user_id
        WHERE s.token = ? AND s.created_at >= ?
        """,
        (token_hash, _session_cutoff(ttl_days)),
    ).fetchone()


def delete_session(conn: sqlite3.Connection, token_hash: str) -> None:
    conn.execute("DELETE FROM sessions WHERE token = ?", (token_hash,))


def delete_expired_sessions(conn: sqlite3.Connection, ttl_days: int) -> None:
    conn.execute("DELETE FROM sessions WHERE created_at < ?", (_session_cutoff(ttl_days),))


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
    # Самое свежее по времени измерения, а не последнее вставленное (MAX(id)): при досылке
    # накопленных показаний или заливке истории старые строки получают большие id.
    # Подзапрос идёт по индексу idx_meas_sensor_time.
    return conn.execute(
        """
        SELECT s.type, s.unit, m.value, m.measured_at
        FROM sensors s
        JOIN measurements m ON m.id = (
            SELECT m2.id FROM measurements m2
            WHERE m2.sensor_id = s.id
            ORDER BY m2.measured_at DESC, m2.id DESC
            LIMIT 1
        )
        WHERE s.device_id = ?
        """,
        (device_id,),
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


def history_compact(
    conn: sqlite3.Connection,
    device_id: str,
    date_from: str | None,
    date_to: str | None,
    sensor_type: str | None,
    max_points: int,
) -> list[sqlite3.Row]:
    """История не длиннее max_points точек на датчик: при большем числе строк значения
    усредняются по равным интервалам времени прямо в SQLite (форма ответа та же).

    Плата шлёт данные раз в 10 с: за 30 дней это ~520 тыс. строк — без сжатия ответ
    весит десятки мегабайт, и телефон не успевает его разобрать.
    """
    where = " WHERE s.device_id = ?"
    args: list[object] = [device_id]
    if sensor_type:
        where += " AND s.type = ?"
        args.append(sensor_type)
    if date_from:
        where += " AND m.measured_at >= ?"
        args.append(date_from)
    if date_to:
        where += " AND m.measured_at <= ?"
        args.append(date_to)
    base = " FROM measurements m JOIN sensors s ON s.id = m.sensor_id" + where

    info = conn.execute(
        "SELECT MAX(cnt) AS per_sensor, MIN(t0) AS t0, MAX(t1) AS t1 FROM ("
        " SELECT COUNT(*) AS cnt, MIN(CAST(strftime('%s', m.measured_at) AS INTEGER)) AS t0,"
        " MAX(CAST(strftime('%s', m.measured_at) AS INTEGER)) AS t1" + base + " GROUP BY s.id)",
        args,
    ).fetchone()
    if info is None or info["per_sensor"] is None or info["per_sensor"] <= max_points:
        return history(conn, device_id, date_from, date_to, sensor_type)

    span = max(1, (info["t1"] or 0) - (info["t0"] or 0))
    bucket = max(1, -(-span // max_points))  # ceil
    return conn.execute(
        "SELECT s.type AS type, s.unit AS unit, ROUND(AVG(m.value), 2) AS value,"
        " MIN(m.measured_at) AS measured_at" + base +
        " GROUP BY s.id, CAST(strftime('%s', m.measured_at) AS INTEGER) / ?"
        " ORDER BY measured_at ASC",
        [*args, bucket],
    ).fetchall()


def period_stats(conn: sqlite3.Connection, device_id: str, date_from: str) -> list[sqlite3.Row]:
    """Мин/макс/среднее по каждому датчику за период одним запросом (без выгрузки строк)."""
    return conn.execute(
        """
        SELECT s.type AS type, MIN(m.value) AS min, MAX(m.value) AS max,
               AVG(m.value) AS avg, COUNT(*) AS count
        FROM measurements m
        JOIN sensors s ON s.id = m.sensor_id
        WHERE s.device_id = ? AND m.measured_at >= ?
        GROUP BY s.type
        """,
        (device_id, date_from),
    ).fetchall()


def get_settings(conn: sqlite3.Connection, device_id: str) -> sqlite3.Row | None:
    return conn.execute(
        """
        SELECT device_id, temp_min, temp_max, humidity_min, humidity_max
        FROM settings WHERE device_id = ?
        """,
        (device_id,),
    ).fetchone()


def put_settings(
    conn: sqlite3.Connection,
    device_id: str,
    temp_min: float,
    temp_max: float,
    humidity_min: float,
    humidity_max: float,
) -> None:
    conn.execute(
        "INSERT OR IGNORE INTO devices(id, name, created_at) VALUES (?, ?, ?)",
        (device_id, device_id, utc_now()),
    )
    conn.execute(
        """
        INSERT INTO settings(device_id, temp_min, temp_max, humidity_min, humidity_max)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(device_id) DO UPDATE SET
            temp_min = excluded.temp_min,
            temp_max = excluded.temp_max,
            humidity_min = excluded.humidity_min,
            humidity_max = excluded.humidity_max
        """,
        (device_id, temp_min, temp_max, humidity_min, humidity_max),
    )
