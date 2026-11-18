# -*- coding: utf-8 -*-
"""Имитация платы (генератор данных): POST раз в 10 с.

    python simulate_collector.py                                # плата pi-1, локальный сервер
    python simulate_collector.py http://127.0.0.1:8000 --device pi-2 --temp 18

Платы pi-2 и pi-3 заведены в базу миграцией 002 («Плата № 0002/0003») — так можно
проверить два помещения без второй Raspberry Pi."""
from __future__ import annotations

import json
import os
import random
import sys
import time
import urllib.request


def _arg(name: str, default: str) -> str:
    if name in sys.argv:
        i = sys.argv.index(name)
        if i + 1 < len(sys.argv):
            return sys.argv[i + 1]
    return default


_positional = [a for i, a in enumerate(sys.argv[1:], 1)
               if not a.startswith("--") and not sys.argv[i - 1].startswith("--")]
BASE = _positional[0] if _positional else "http://127.0.0.1:8000"
DEVICE_ID = _arg("--device", "pi-1")
START_TEMP = float(_arg("--temp", "23.5"))
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
        "device_id": DEVICE_ID,
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
    print(f"Имитация DHT22, плата {DEVICE_ID} ->", BASE)
    temp, hum = START_TEMP, 42.0
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
