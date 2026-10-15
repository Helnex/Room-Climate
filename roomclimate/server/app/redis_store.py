from __future__ import annotations

import json

from redis import Redis

from app.config import settings

CURRENT_KEY = "device:{device_id}:sensor:{sensor_type}:current"
DEVICE_HASH = "device:{device_id}:current"


def redis_client() -> Redis:
    return Redis.from_url(settings.redis_url, decode_responses=True)


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
    )
    return str(msg_id)


def ensure_group(r: Redis) -> None:
    try:
        r.xgroup_create(settings.stream_name, settings.stream_group, id="0", mkstream=True)
    except Exception as exc:
        if "BUSYGROUP" not in str(exc):
            raise
