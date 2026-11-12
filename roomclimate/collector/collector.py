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
# Должен совпадать с INGEST_TOKEN на сервере (если он там задан).
INGEST_TOKEN = os.environ.get("INGEST_TOKEN", "")


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
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if INGEST_TOKEN:
        headers["X-Device-Token"] = INGEST_TOKEN
    req = urllib.request.Request(
        SERVER_URL + "/telemetry",
        data=data,
        method="POST",
        headers=headers,
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        print(resp.read().decode("utf-8"))


def main() -> None:
    print(f"collector -> {SERVER_URL} device={DEVICE_ID} period={PERIOD_SEC}s")
    failures = 0
    while True:
        try:
            t, h = read_sensor()
            send(t, h)
            failures = 0
        except Exception as exc:  # noqa: BLE001 — сбор не должен останавливаться
            # Раньше любая ошибка (сервер перезапускается, Wi-Fi пропал, сбой датчика)
            # завершала процесс, и плата молчала до ручного перезапуска.
            failures += 1
            print(f"ошибка отправки ({failures} подряд): {exc!r}", flush=True)
        # При серии ошибок увеличиваем паузу (до 60 с), чтобы не заваливать сеть.
        delay = PERIOD_SEC if failures == 0 else min(60.0, PERIOD_SEC * min(failures, 6))
        time.sleep(delay)


if __name__ == "__main__":
    main()
