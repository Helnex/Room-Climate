"""Тревоги на сервере: выход за пороги, «датчик молчит», журнал и push через ntfy.

Почему на сервере, а не только в приложении: телефону не нужно держать фоновый сервис
(Android 15+ ограничивает его 6 часами в сутки) — сервер сам присылает push через ntfy
(свой сервер уведомлений в docker-compose, без Google/Firebase).

Защита от «дребезга» (как у промышленных систем мониторинга):
- задержка ALERT_DELAY_SECONDS: тревога только если выход за порог держится дольше задержки;
- гистерезис: тревога снимается, только когда значение вернулось в норму с запасом
  (0.5 °C для температуры, 2 % для влажности), а не на границе порога.
"""
from __future__ import annotations

import json
import logging
import os
import sqlite3
import urllib.request
from datetime import datetime, timezone

from app import insights

log = logging.getLogger("roomclimate.alerts")

ALERT_DELAY_SECONDS = int(os.environ.get("ALERT_DELAY_SECONDS", "60"))
STALE_AFTER_SECONDS = int(os.environ.get("STALE_AFTER_SECONDS", "180"))
NTFY_URL = os.environ.get("NTFY_URL", "").rstrip("/")
NTFY_TOPIC = os.environ.get("NTFY_TOPIC", "")
HYSTERESIS = {"temperature": 0.5, "humidity": 2.0}
LABELS = {"temperature": "Температура", "humidity": "Влажность"}
UNITS = {"temperature": "°C", "humidity": "%"}
DEFAULTS = {"temperature": (18.0, 26.0), "humidity": (30.0, 70.0)}

SCHEMA = """
CREATE TABLE IF NOT EXISTS alert_state (
    device_id TEXT NOT NULL,
    sensor TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ok',
    candidate TEXT,
    candidate_since TEXT,
    PRIMARY KEY (device_id, sensor)
);
CREATE TABLE IF NOT EXISTS alerts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    sensor TEXT,
    value REAL,
    threshold REAL,
    message TEXT NOT NULL,
    created_at TEXT NOT NULL,
    resolved_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_alerts_device_time ON alerts(device_id, created_at);
"""


def init(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA)


# ---------- Чистая логика (тестируется без БД) ----------

def next_status(status: str, value: float, low: float, high: float, margin: float) -> str:
    """Новое состояние с гистерезисом. status: ok | above | below."""
    if status == "above":
        return "ok" if value <= high - margin else ("below" if value < low else "above")
    if status == "below":
        return "ok" if value >= low + margin else ("above" if value > high else "below")
    if value > high:
        return "above"
    if value < low:
        return "below"
    return "ok"


def _seconds_between(a: str | None, b: str | None) -> float:
    da, db_ = insights.parse_iso(a), insights.parse_iso(b)
    if da is None or db_ is None:
        return 0.0
    return (db_ - da).total_seconds()


# ---------- Уведомления ----------

def push(title: str, message: str, priority: int = 4, tags: str = "warning") -> None:
    """Отправить push в ntfy. Без NTFY_URL/NTFY_TOPIC — только журнал.

    Публикация JSON-ом в корень сервера: так заголовок может быть на русском
    (HTTP-заголовки Title/Tags допускают только latin-1)."""
    if not NTFY_URL or not NTFY_TOPIC:
        return
    body = json.dumps(
        {"topic": NTFY_TOPIC, "title": title, "message": message, "priority": priority, "tags": [tags]},
        ensure_ascii=False,
    ).encode("utf-8")
    req = urllib.request.Request(
        NTFY_URL + "/",
        data=body,
        method="POST",
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    try:
        with urllib.request.urlopen(req, timeout=5):
            pass
    except Exception as exc:  # noqa: BLE001 — недоставленный push не должен ронять воркер
        log.warning("ntfy недоступен: %r", exc)
        print(f"ntfy недоступен: {exc!r}", flush=True)


def _open_alert(conn, device_id, kind, sensor, value, threshold, message, at) -> None:
    conn.execute(
        "INSERT INTO alerts(device_id, kind, sensor, value, threshold, message, created_at)"
        " VALUES (?, ?, ?, ?, ?, ?, ?)",
        (device_id, kind, sensor, value, threshold, message, at),
    )


def _resolve(conn, device_id, kind, sensor, at) -> None:
    conn.execute(
        "UPDATE alerts SET resolved_at = ? WHERE device_id = ? AND kind = ? AND"
        " (sensor = ? OR (sensor IS NULL AND ? IS NULL)) AND resolved_at IS NULL",
        (at, device_id, kind, sensor, sensor),
    )


def _fmt(value: float) -> str:
    return f"{value:.1f}".replace(".", ",")


# ---------- Обработка показаний (вызывает воркер) ----------

def evaluate(conn: sqlite3.Connection, device_id: str, sensor: str, value: float, measured_at: str) -> None:
    if sensor not in DEFAULTS:
        return
    limits = conn.execute(
        "SELECT temp_min, temp_max, humidity_min, humidity_max FROM settings WHERE device_id = ?",
        (device_id,),
    ).fetchone()
    if limits is None:
        low, high = DEFAULTS[sensor]
    elif sensor == "temperature":
        low, high = limits["temp_min"], limits["temp_max"]
    else:
        low, high = limits["humidity_min"], limits["humidity_max"]

    row = conn.execute(
        "SELECT status, candidate, candidate_since FROM alert_state WHERE device_id = ? AND sensor = ?",
        (device_id, sensor),
    ).fetchone()
    status = row["status"] if row else "ok"
    candidate = row["candidate"] if row else None
    since = row["candidate_since"] if row else None

    raw = next_status(status, value, low, high, HYSTERESIS[sensor])
    label, unit = LABELS[sensor], UNITS[sensor]

    if raw == status:
        candidate, since = None, None
    elif status != "ok" and raw == "ok":
        _resolve(conn, device_id, "threshold", sensor, measured_at)
        push(f"{label}: снова в норме", f"Плата {device_id}: {label.lower()} {_fmt(value)} {unit}",
             priority=3, tags="white_check_mark")
        status, candidate, since = "ok", None, None
    else:
        if candidate != raw:
            candidate, since = raw, measured_at
        if _seconds_between(since, measured_at) >= ALERT_DELAY_SECONDS:
            if status != "ok":
                _resolve(conn, device_id, "threshold", sensor, measured_at)
            threshold = high if raw == "above" else low
            word = "выше" if raw == "above" else "ниже"
            message = f"Плата {device_id}: {label.lower()} {_fmt(value)} {unit} — {word} порога {_fmt(threshold)} {unit}"
            _open_alert(conn, device_id, "threshold", sensor, value, threshold, message, measured_at)
            push(f"{label} вне нормы", message)
            status, candidate, since = raw, None, None

    conn.execute(
        "INSERT INTO alert_state(device_id, sensor, status, candidate, candidate_since) VALUES (?, ?, ?, ?, ?)"
        " ON CONFLICT(device_id, sensor) DO UPDATE SET status = excluded.status,"
        " candidate = excluded.candidate, candidate_since = excluded.candidate_since",
        (device_id, sensor, status, candidate, since),
    )


def check_silent(conn: sqlite3.Connection, now: datetime | None = None) -> None:
    """Тревога «датчик молчит» для плат без показаний дольше STALE_AFTER_SECONDS."""
    now = now or datetime.now(timezone.utc)
    now_iso = now.replace(microsecond=0).isoformat()
    rows = conn.execute(
        "SELECT s.device_id AS device_id, MAX(m.measured_at) AS last_seen FROM sensors s"
        " JOIN measurements m ON m.sensor_id = s.id GROUP BY s.device_id"
    ).fetchall()
    for row in rows:
        age = insights.age_seconds(row["last_seen"], now)
        if age is None:
            continue
        open_alert = conn.execute(
            "SELECT 1 FROM alerts WHERE device_id = ? AND kind = 'silent' AND resolved_at IS NULL",
            (row["device_id"],),
        ).fetchone()
        if age > STALE_AFTER_SECONDS and not open_alert:
            minutes = max(1, age // 60)
            message = f"Плата {row['device_id']} не присылает показания {minutes} мин. Проверьте питание и Wi‑Fi."
            _open_alert(conn, row["device_id"], "silent", None, None, None, message, now_iso)
            push("Датчик молчит", message)
        elif age <= STALE_AFTER_SECONDS and open_alert:
            _resolve(conn, row["device_id"], "silent", None, now_iso)
            push("Датчик снова на связи", f"Плата {row['device_id']} снова присылает показания",
                 priority=3, tags="white_check_mark")
