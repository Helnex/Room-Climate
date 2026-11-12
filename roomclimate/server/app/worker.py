from __future__ import annotations

import time

from redis.exceptions import RedisError

from app import db
from app.config import settings
from app.redis_store import ensure_group, redis_client

CONSUMER = "worker-1"


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


def handle(r, msg_id: str, fields: dict) -> bool:
    """True — сообщение обработано (или выброшено как битое) и подтверждено.

    False — временная ошибка (SQLite занят, диск): сообщение остаётся в pending
    и будет перечитано. Раньше такое показание терялось навсегда: чтение с «>»
    отдаёт только новые сообщения, а pending никто не перечитывал.
    """
    try:
        process_message(fields)
    except (KeyError, ValueError, TypeError) as exc:
        # Битые данные не исправятся повтором — подтверждаем, иначе зациклимся.
        print(f"пропущено битое сообщение {msg_id}: {exc!r}", flush=True)
    except Exception as exc:
        print(f"ошибка записи {msg_id}, повторю: {exc!r}", flush=True)
        return False
    r.xack(settings.stream_name, settings.stream_group, msg_id)
    return True


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
    # При старте сначала дочитываем свои неподтверждённые сообщения ("0"),
    # затем новые (">"). После временной ошибки снова переходим к pending.
    read_from = "0"
    while True:
        try:
            r = redis_client()
            batches = r.xreadgroup(
                settings.stream_group,
                CONSUMER,
                {settings.stream_name: read_from},
                count=50,
                block=None if read_from == "0" else 5000,
            )
            messages = [m for _stream, msgs in (batches or []) for m in msgs]
            if read_from == "0" and not messages:
                read_from = ">"  # pending пуст — читаем новые
                continue
            failed = False
            for msg_id, fields in messages:
                if not fields:  # запись уже удалена обрезкой потока
                    r.xack(settings.stream_name, settings.stream_group, msg_id)
                    continue
                if not handle(r, msg_id, fields):
                    failed = True
                    break
            if failed:
                read_from = "0"
                time.sleep(2)
        except Exception as exc:
            print(f"ошибка обработчика: {exc}", flush=True)
            read_from = "0"
            time.sleep(2)


if __name__ == "__main__":
    run()
