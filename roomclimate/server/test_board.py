# -*- coding: utf-8 -*-
"""Тесты эндпоинтов платы: время замера (досылка буфера), пороги для сигнала, состояние.
Временная SQLite, без Docker и Redis: python test_board.py"""
import os
import tempfile
from datetime import datetime, timedelta, timezone

os.environ["SQLITE_PATH"] = os.path.join(tempfile.mkdtemp(), "board.db")
os.environ["REDIS_URL"] = "redis://127.0.0.1:1/0"
os.environ["SMTP_HOST"] = ""
os.environ["INGEST_TOKEN"] = "board-secret"

from fastapi import HTTPException  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app import board  # noqa: E402
from app.main import app  # noqa: E402

DEVICE = {"X-Device-Token": "board-secret"}


def test_measured_at():
    now = datetime(2026, 11, 15, 12, 0, tzinfo=timezone.utc)
    assert board.parse_measured_at(None, now) == ("2026-11-15T12:00:00+00:00", False)
    # Свежее (минута назад) — обычное показание, час назад — досланное.
    assert board.parse_measured_at(now - timedelta(minutes=1), now)[1] is False
    iso, late = board.parse_measured_at(datetime(2026, 11, 15, 14, 0, tzinfo=timezone(timedelta(hours=3))), now)
    assert iso == "2026-11-15T11:00:00+00:00" and late is True
    # Без часового пояса — считаем UTC.
    assert board.parse_measured_at(datetime(2026, 11, 15, 11, 59), now)[0] == "2026-11-15T11:59:00+00:00"
    for bad in (now + timedelta(minutes=10), now - timedelta(days=8)):
        try:
            board.parse_measured_at(bad, now)
        except HTTPException as exc:
            assert exc.status_code == 422
        else:
            raise AssertionError(f"{bad} должно отклоняться")
    print("OK  время замера: свежее, досланное, пояс, будущее и старше недели")


def register(client, email):
    r = client.post("/auth/register", json={"name": "Настя", "surname": "Тест", "email": email,
                                            "password": "secret12"}, headers={"User-Agent": "okhttp/4.12.0"})
    assert r.status_code == 200, r.text
    return {"Authorization": f"Bearer {r.json()['token']}"}


def test_endpoints():
    with TestClient(app) as c:
        user = register(c, "board@example.com")

        # Пороги: без токена платы — 401, по умолчанию — значения сервера, потом — из настроек.
        assert c.get("/devices/pi-1/thresholds").status_code == 401
        r = c.get("/devices/pi-1/thresholds", headers=DEVICE)
        assert r.status_code == 200, r.text
        assert r.json()["temperature"] == {"min": 18.0, "max": 26.0} and r.json()["co2"]["max"] == 1000.0
        r = c.put("/settings?device_id=pi-1", json={"temp_min": 20, "temp_max": 24, "humidity_min": 35, "humidity_max": 60},
                  headers=user)
        assert r.status_code == 200, r.text
        r = c.get("/devices/pi-1/thresholds", headers=DEVICE).json()
        assert r["temperature"] == {"min": 20.0, "max": 24.0} and r["humidity"] == {"min": 35.0, "max": 60.0}, r
        print("OK  пороги для платы: токен, значения по умолчанию и из настроек")

        # Состояние платы.
        assert c.get("/devices/pi-1/status", headers=user).status_code == 404
        status = {"cpu_temp": 48.3, "wifi_ssid": "Home", "wifi_signal": -55, "ip": "192.168.1.30",
                  "uptime_sec": 3600, "version": "abc1234", "buffered": 0, "sensors": ["dht22", "co2"],
                  "alarm": False}
        assert c.post("/devices/pi-1/status", json=status).status_code == 401
        assert c.post("/devices/pi-1/status", json={"cpu_temp": 500}, headers=DEVICE).status_code == 422
        assert c.post("/devices/pi-1/status", json=status, headers=DEVICE).status_code == 200
        r = c.get("/devices/pi-1/status", headers=user)
        assert r.status_code == 200, r.text
        body = r.json()
        assert body["cpu_temp"] == 48.3 and body["version"] == "abc1234" and body["age_seconds"] <= 5, body
        assert c.get("/devices/pi-1/status").status_code == 401
        print("OK  состояние платы: приём с токеном, проверка значений, чтение пользователем")


if __name__ == "__main__":
    test_measured_at()
    test_endpoints()
