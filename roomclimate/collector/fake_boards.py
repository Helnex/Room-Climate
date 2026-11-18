# -*- coding: utf-8 -*-
"""Генератор данных: несколько «плат» без железа — для проверки помещений, датчиков и графиков.

Каждая плата шлёт правдоподобные показания (суточный ход температуры, влажность в обратную
сторону, CO₂ растёт днём, свет днём, иногда движение) в тот же POST /telemetry, что и настоящий
сборщик, и раз в минуту — «состояние платы». Платы pi-2 и pi-3 заведены в базу сервера
(«Плата № 0002/0003»), так что в приложении их можно взять для помещений «Склад1 / Склад2».

    python fake_boards.py http://127.0.0.1:8000 pi-2 pi-3
    python fake_boards.py https://api.climate-room.online pi-2:18 pi-3:24 --extra co2,light
    python fake_boards.py http://127.0.0.1:8000 pi-2 --backfill 6 --once   # история за 6 часов

Аргументы платы: id[:температура] — средняя температура (по умолчанию 22, 18, 25, …).
--extra    — доп. показатели: co2, light, motion (через запятую);
--backfill — сначала дослать историю за N часов (с measured_at, раз в --step секунд), чтобы
             графики сразу были не пустыми (сервер принимает досылку до 7 дней назад);
--once     — только история, без бесконечного цикла; --period — пауза между отправками.
INGEST_TOKEN — из окружения или server/.env рядом.
"""
from __future__ import annotations

import argparse
import math
import os
import random
import time
from datetime import datetime, timedelta, timezone

import collector
import extra_sensors

DEFAULT_TEMPS = (22.0, 18.0, 25.0, 20.0)


def read_token() -> str:
    token = os.environ.get("INGEST_TOKEN", "")
    if token:
        return token
    env = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "server", ".env")
    if os.path.exists(env):
        for line in open(env, encoding="utf-8"):
            if line.startswith("INGEST_TOKEN="):
                return line.split("=", 1)[1].strip()
    return ""


class FakeBoard:
    """Одна «плата»: показания зависят от времени суток, плюс небольшой шум."""

    def __init__(self, device_id: str, base_temp: float, extra: list[str], rng: random.Random | None = None):
        self.device_id = device_id
        self.base_temp = base_temp
        self.extra = extra
        self.rng = rng or random.Random(device_id)

    def readings(self, when: datetime) -> list[dict]:
        hour = when.astimezone().hour + when.astimezone().minute / 60
        day = math.sin((hour - 9) / 24 * 2 * math.pi)  # максимум около 15:00, минимум около 03:00
        noise = self.rng.uniform
        t = round(self.base_temp + 1.8 * day + noise(-0.3, 0.3), 1)
        h = round(min(95.0, max(15.0, 45.0 - 6 * day + noise(-1.5, 1.5))), 1)
        out = collector.build_body(t, h, self.device_id)["readings"]
        if "co2" in self.extra:
            out.append(extra_sensors.reading("co2", int(650 + 250 * max(day, 0) + noise(-40, 40))))
        if "light" in self.extra:
            lux = 0.0 if not 7 <= hour <= 21 else 350 * math.sin((hour - 7) / 14 * math.pi) + noise(-20, 20)
            out.append(extra_sensors.reading("light", round(max(lux, 0.0), 1)))
        if "motion" in self.extra:
            out.append(extra_sensors.reading("motion", 1 if self.rng.random() < (0.35 if 8 <= hour <= 22 else 0.05) else 0))
        return out

    def body(self, when: datetime, measured_at: bool = False) -> dict:
        body = {"device_id": self.device_id, "readings": self.readings(when)}
        if measured_at:
            body["measured_at"] = when.replace(microsecond=0).isoformat()
        return body

    def status(self) -> dict:
        return {"version": "генератор", "sensors": ["fake-dht22"] + [f"{k}-test" for k in self.extra],
                "uptime_sec": int(time.monotonic()), "buffered": 0, "alarm": False}


def parse_board(arg: str, index: int) -> tuple[str, float]:
    device_id, _, temp = arg.partition(":")
    return device_id, float(temp) if temp else DEFAULT_TEMPS[index % len(DEFAULT_TEMPS)]


def backfill_times(hours: float, step: float, now: datetime) -> list[datetime]:
    """Моменты от «now − hours» до «now» с шагом step секунд (старые — первыми)."""
    count = int(hours * 3600 // step)
    return [now - timedelta(seconds=step * i) for i in range(count, 0, -1)]


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description="Генератор данных для нескольких плат без железа")
    ap.add_argument("server", help="адрес сервера, например http://127.0.0.1:8000")
    ap.add_argument("boards", nargs="+", help="платы: id[:средняя температура], например pi-2 pi-3:24")
    ap.add_argument("--extra", default="", help="доп. показатели: co2, light, motion")
    ap.add_argument("--period", type=float, default=10.0, help="пауза между отправками, с")
    ap.add_argument("--backfill", type=float, default=0.0, help="дослать историю за N часов")
    ap.add_argument("--step", type=float, default=300.0, help="шаг истории, с (по умолчанию 5 мин)")
    ap.add_argument("--once", action="store_true", help="только история, без цикла")
    args = ap.parse_args(argv)

    server = args.server.rstrip("/")
    token = read_token()
    extra = [k for k in extra_sensors.parse_kinds(args.extra) if k != extra_sensors.AUTO]
    boards = [FakeBoard(*parse_board(b, i), extra) for i, b in enumerate(args.boards)]
    send = collector.Sender(lambda body: collector.post(body, server, token))
    print(f"генератор -> {server}: " + ", ".join(f"{b.device_id} (~{b.base_temp} °C)" for b in boards), flush=True)

    if args.backfill > 0:
        times = backfill_times(args.backfill, args.step, datetime.now(timezone.utc))
        for b in boards:
            for when in times:
                send(b.body(when, measured_at=True))
            print(f"{b.device_id}: история за {args.backfill:g} ч — {len(times)} точек", flush=True)
    if args.once:
        return

    last_status = 0.0
    while True:
        now = datetime.now(timezone.utc)
        for b in boards:
            try:
                body = b.body(now)
                print(f"{b.device_id}: {collector.describe(body['readings'][0]['value'], body['readings'][1]['value'], body['readings'][2:])}"
                      f" -> {send(body)}", flush=True)
            except Exception as exc:  # noqa: BLE001 — сервер перезапускается: ждём следующий цикл
                print(f"{b.device_id}: не отправлено: {exc!r}", flush=True)
        if time.monotonic() - last_status >= 60:
            last_status = time.monotonic()
            for b in boards:
                try:
                    collector.board_status.send(server, b.device_id, token, b.status())
                except Exception as exc:  # noqa: BLE001
                    print(f"{b.device_id}: состояние не отправлено: {exc!r}", flush=True)
        time.sleep(args.period)


if __name__ == "__main__":
    main()
