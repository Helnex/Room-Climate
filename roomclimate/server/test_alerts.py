# -*- coding: utf-8 -*-
"""Тесты тревог (гистерезис, задержка, «датчик молчит») на временной SQLite: python test_alerts.py"""
import os
import tempfile
from datetime import datetime, timedelta, timezone

os.environ["SQLITE_PATH"] = os.path.join(tempfile.mkdtemp(), "alerts.db")
os.environ["ALERT_DELAY_SECONDS"] = "60"
os.environ["NTFY_URL"] = ""  # без отправки push

from app import alerts, db  # noqa: E402

T0 = datetime(2026, 11, 12, 10, 0, tzinfo=timezone.utc)


def ts(seconds):
    return (T0 + timedelta(seconds=seconds)).isoformat()


def open_alerts(conn, kind="threshold"):
    return conn.execute(
        "SELECT * FROM alerts WHERE kind = ? AND resolved_at IS NULL", (kind,)
    ).fetchall()


def test_next_status_hysteresis():
    # Порог 26, гистерезис 0.5: тревога снимается только ниже 25.5
    assert alerts.next_status("ok", 26.2, 18, 26, 0.5) == "above"
    assert alerts.next_status("above", 25.8, 18, 26, 0.5) == "above"
    assert alerts.next_status("above", 25.5, 18, 26, 0.5) == "ok"
    assert alerts.next_status("below", 18.2, 18, 26, 0.5) == "below"
    assert alerts.next_status("below", 18.5, 18, 26, 0.5) == "ok"
    assert alerts.next_status("ok", 20, 18, 26, 0.5) == "ok"


def test_delay_and_flapping():
    db.init_db()
    conn = db.connect()
    # Короткий выброс (30 с) — тревоги нет
    alerts.evaluate(conn, "d1", "temperature", 27.0, ts(0))
    alerts.evaluate(conn, "d1", "temperature", 27.0, ts(30))
    alerts.evaluate(conn, "d1", "temperature", 24.0, ts(40))
    assert not open_alerts(conn), "тревога от короткого выброса"
    # Держится дольше 60 с — одна тревога
    for s in range(100, 200, 10):
        alerts.evaluate(conn, "d1", "temperature", 27.0, ts(s))
    assert len(open_alerts(conn)) == 1
    # Дребезг у порога (25.8–26.2) — новых тревог нет, текущая не снимается
    for s, v in zip(range(200, 300, 10), [25.8, 26.2] * 5):
        alerts.evaluate(conn, "d1", "temperature", v, ts(s))
    assert len(open_alerts(conn)) == 1
    total = conn.execute("SELECT COUNT(*) FROM alerts").fetchone()[0]
    assert total == 1, total
    # Вернулась в норму с запасом — тревога закрыта
    alerts.evaluate(conn, "d1", "temperature", 25.0, ts(310))
    assert not open_alerts(conn)
    conn.close()


def test_silent_sensor():
    conn = db.connect()
    sid = db.ensure_device_and_sensor(conn, "d2", "humidity", "%")
    db.insert_measurement(conn, sid, 40, ts(0))
    conn.commit()
    alerts.check_silent(conn, T0 + timedelta(seconds=100))
    assert not open_alerts(conn, "silent")
    alerts.check_silent(conn, T0 + timedelta(seconds=600))
    alerts.check_silent(conn, T0 + timedelta(seconds=700))
    assert len(open_alerts(conn, "silent")) == 1, "тревога «молчит» одна, без повторов"
    db.insert_measurement(conn, sid, 41, ts(710))
    conn.commit()
    alerts.check_silent(conn, T0 + timedelta(seconds=720))
    assert not open_alerts(conn, "silent")
    conn.close()


if __name__ == "__main__":
    tests = [fn for name, fn in sorted(globals().items()) if name.startswith("test_")]
    for fn in tests:
        fn()
        print(f"OK  {fn.__name__}")
    print(f"Все {len(tests)} тестов пройдены")
