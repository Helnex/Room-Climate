# -*- coding: utf-8 -*-
"""Демо-история для проверки графиков: показания за прошлые N дней.

Запускается внутри контейнера api (там SQLite и app.db):
    docker compose -p roomclimate cp seed_history.py api:/tmp/seed_history.py
    docker compose -p roomclimate exec api python /tmp/seed_history.py --days 30
Удалить демо-данные этого устройства:  ... python /tmp/seed_history.py --clear
"""
from __future__ import annotations

import argparse
import math
import random
import sys
from datetime import datetime, timedelta, timezone

sys.path.insert(0, "/app")

from app import db  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default="pi-1")
    parser.add_argument("--days", type=int, default=30)
    parser.add_argument("--step-min", type=int, default=10, help="шаг между показаниями, минут")
    parser.add_argument("--clear", action="store_true", help="удалить все показания устройства")
    args = parser.parse_args()

    db.init_db()
    conn = db.connect()
    if args.clear:
        conn.execute(
            "DELETE FROM measurements WHERE sensor_id IN (SELECT id FROM sensors WHERE device_id = ?)",
            (args.device,),
        )
        conn.commit()
        print(f"Показания {args.device} удалены")
        return

    t_id = db.ensure_device_and_sensor(conn, args.device, "temperature", "C")
    h_id = db.ensure_device_and_sensor(conn, args.device, "humidity", "%")
    now = datetime.now(timezone.utc).replace(second=0, microsecond=0)
    start = now - timedelta(days=args.days)
    oldest = conn.execute(
        "SELECT MIN(m.measured_at) FROM measurements m JOIN sensors s ON s.id = m.sensor_id WHERE s.device_id = ?",
        (args.device,),
    ).fetchone()[0]
    # Пишем только до самых ранних существующих показаний, чтобы не перемешать живые данные.
    end = datetime.fromisoformat(oldest) if oldest else now
    rng = random.Random(42)
    count = 0
    ts = start
    while ts < end:
        hours = ts.hour + ts.minute / 60
        day = (ts - start).days
        # Суточный ход: теплее и суше днём, плюс медленный дрейф по дням и шум.
        temp = 22.5 + 2.5 * math.sin((hours - 9) / 24 * 2 * math.pi) + 1.5 * math.sin(day / 5) + rng.uniform(-0.3, 0.3)
        hum = 45 - 8 * math.sin((hours - 9) / 24 * 2 * math.pi) + 6 * math.cos(day / 4) + rng.uniform(-1.5, 1.5)
        stamp = ts.isoformat()
        db.insert_measurement(conn, t_id, round(temp, 1), stamp)
        db.insert_measurement(conn, h_id, round(max(5.0, min(95.0, hum)), 1), stamp)
        count += 1
        ts += timedelta(minutes=args.step_min)
    conn.commit()
    conn.close()
    print(f"{args.device}: добавлено {count} точек x 2 датчика за {args.days} дн. (до {end.isoformat()})")


if __name__ == "__main__":
    main()
