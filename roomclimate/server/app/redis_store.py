from __future__ import annotations

import json
import threading

from redis import Redis

from app.config import settings

CURRENT_KEY = "device:{device_id}:sensor:{sensor_type}:current"
DEVICE_HASH = "device:{device_id}:current"
# Поток — только очередь до записи в SQLite. Без ограничения он хранил бы в памяти Redis
# каждое показание навсегда (~17 тыс./сутки на плату). ~ (approximate) — дешёвая обрезка.
STREAM_MAXLEN = 100_000

_client: Redis | None = None
_client_lock = threading.Lock()


def redis_client() -> Redis:
    """Один клиент с общим пулом соединений на процесс (раньше — новый пул на каждый вызов)."""
    global _client
    if _client is None:
        with _client_lock:
            if _client is None:
                _client = Redis.from_url(
                    settings.redis_url,
                    decode_responses=True,
                    socket_connect_timeout=2,
                    # Больше, чем block=5000 мс в xreadgroup воркера, иначе чтение рвётся по таймауту.
                    socket_timeout=15,
                    health_check_interval=30,
                )
    return _client


def write_current(r: Redis, device_id: str, sensor_type: str, payload: dict) -> None:
    key = CURRENT_KEY.format(device_id=device_id, sensor_type=sensor_type)
    raw = json.dumps(payload, ensure_ascii=False)
    r.set(key, raw, ex=settings.current_ttl_seconds)
    r.hset(DEVICE_HASH.format(device_id=device_id), sensor_type, raw)
    r.expire(DEVICE_HASH.format(device_id=device_id), settings.current_ttl_seconds)


def read_current(r: Redis, device_id: str) -> dict[str, dict]:
    raw = r.hgetall(DEVICE_HASH.format(device_id=device_id))
    out: dict[str, dict] = {}
    for sensor_type, value in raw.items():
        out[sensor_type] = json.loads(value)
    return out


def enqueue(r: Redis, device_id: str, sensor_type: str, value: float, unit: str, measured_at: str) -> str:
    msg_id = r.xadd(
        settings.stream_name,
        {
            "device_id": device_id,
            "type": sensor_type,
            "value": str(value),
            "unit": unit,
            "measured_at": measured_at,
        },
        maxlen=STREAM_MAXLEN,
        approximate=True,
    )
    return str(msg_id)


def ensure_group(r: Redis) -> None:
    try:
        r.xgroup_create(settings.stream_name, settings.stream_group, id="0", mkstream=True)
    except Exception as exc:
        if "BUSYGROUP" not in str(exc):
            raise
