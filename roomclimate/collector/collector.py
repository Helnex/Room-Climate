# -*- coding: utf-8 -*-
"""Сборщик RoomClimate: POST измерений на сервер.

На ПК без платы генерирует тестовые значения.
На Raspberry Pi заменить read_sensor() чтением DHT22 (GPIO4).
"""
from __future__ import annotations

import json
import os
import random
import time
import urllib.request

SERVER_URL = os.environ.get("SERVER_URL", "http://127.0.0.1:8000").rstrip("/")
DEVICE_ID = os.environ.get("DEVICE_ID", "pi-1")
PERIOD_SEC = float(os.environ.get("PERIOD_SEC", "10"))


def read_sensor() -> tuple[float, float]:
    # TODO(Хабарова): DHT22, GPIO4, вернуть (temperature_c, humidity_pct)
    return round(23.0 + random.uniform(-0.5, 0.5), 1), round(42.0 + random.uniform(-2, 2), 1)


def send(temperature: float, humidity: float) -> None:
    body = {
        "device_id": DEVICE_ID,
        "readings": [
            {"type": "temperature", "value": temperature, "unit": "C"},
            {"type": "humidity", "value": humidity, "unit": "%"},
        ],
    }
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        SERVER_URL + "/telemetry",
        data=data,
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        print(resp.read().decode("utf-8"))


def main() -> None:
    print(f"collector -> {SERVER_URL} device={DEVICE_ID} period={PERIOD_SEC}s")
    while True:
        t, h = read_sensor()
        send(t, h)
        time.sleep(PERIOD_SEC)


if __name__ == "__main__":
    main()
