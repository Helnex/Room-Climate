# -*- coding: utf-8 -*-
"""Имитация платы: POST раз в 10 с. python simulate_collector.py"""
from __future__ import annotations

import json
import os
import random
import sys
import time
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8000"
INGEST_TOKEN = os.environ.get("INGEST_TOKEN", "")
if not INGEST_TOKEN:
    # Секрет стенда, который создаёт scripts/start.ps1 в server/.env.
    _env = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
    if os.path.exists(_env):
        for _line in open(_env, encoding="utf-8"):
            if _line.startswith("INGEST_TOKEN="):
                INGEST_TOKEN = _line.split("=", 1)[1].strip()


def post(temp: float, hum: float) -> None:
    body = {
        "device_id": "pi-1",
        "readings": [
            {"type": "temperature", "value": round(temp, 1), "unit": "C"},
            {"type": "humidity", "value": round(hum, 1), "unit": "%"},
        ],
    }
    data = json.dumps(body).encode("utf-8")
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if INGEST_TOKEN:
        headers["X-Device-Token"] = INGEST_TOKEN
    req = urllib.request.Request(
        BASE + "/telemetry",
        data=data,
        method="POST",
        headers=headers,
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        print(resp.read().decode("utf-8"))


def main() -> None:
    print("Имитация DHT22 ->", BASE)
    temp, hum = 23.5, 42.0
    while True:
        temp += random.uniform(-0.4, 0.4)
        hum += random.uniform(-1.0, 1.0)
        try:
            post(temp, hum)
        except Exception as exc:  # сервер перезапускается — не падаем, ждём следующий цикл
            print(f"ошибка отправки: {exc!r}", flush=True)
        time.sleep(10)


if __name__ == "__main__":
    main()
