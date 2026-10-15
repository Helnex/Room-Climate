from __future__ import annotations

import time

from redis.exceptions import RedisError

from app import db
from app.config import settings
from app.redis_store import ensure_group, redis_client


def process_message(fields: dict) -> None:
    device_id = fields["device_id"]
    sensor_type = fields["type"]
    value = float(fields["value"])
    unit = fields.get("unit") or ""
    measured_at = fields["measured_at"]
    conn = db.connect()
    try:
        sensor_id = db.ensure_device_and_sensor(conn, device_id, sensor_type, unit)
        db.insert_measurement(conn, sensor_id, value, measured_at)
        conn.commit()
    finally:
        conn.close()


def run() -> None:
    db.init_db()
    while True:
        try:
            r = redis_client()
            r.ping()
            ensure_group(r)
            break
        except RedisError:
            print("Redis недоступен, повтор через 2 с", flush=True)
            time.sleep(2)

    print("Обработчик запущен", flush=True)
    while True:
        try:
            r = redis_client()
            batches = r.xreadgroup(
                settings.stream_group,
                "worker-1",
                {settings.stream_name: ">"},
                count=50,
                block=5000,
            )
            if not batches:
                continue
            for _stream, messages in batches:
                for msg_id, fields in messages:
                    process_message(fields)
                    r.xack(settings.stream_name, settings.stream_group, msg_id)
        except Exception as exc:
            print(f"ошибка обработчика: {exc}", flush=True)
            time.sleep(2)


if __name__ == "__main__":
    run()
