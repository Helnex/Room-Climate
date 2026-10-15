# -*- coding: utf-8 -*-
"""Проверка API с хоста: python smoke_test.py"""
from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8000"


def req(method: str, path: str, body: dict | None = None, timeout: int = 10) -> tuple[int, dict]:
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8")
        try:
            payload = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            payload = {"raw": raw}
        return exc.code, payload


def wait_health() -> None:
    for _ in range(30):
        try:
            code, body = req("GET", "/health")
            if code == 200 and body.get("redis") and body.get("sqlite"):
                return
        except OSError:
            pass
        time.sleep(1)
    raise SystemExit("API не ответил на /health за 30 с. Запущен ли docker compose?")


def check(title: str, ok: bool, detail: str = "") -> None:
    mark = "OK" if ok else "FAIL"
    print(f"[{mark}] {title}" + (f" — {detail}" if detail else ""))
    if not ok:
        raise SystemExit(1)


def main() -> None:
    print("База:", BASE)
    wait_health()
    code, health = req("GET", "/health")
    check("health", code == 200 and health.get("status") == "ok", str(health))

    payload = {
        "device_id": "pi-1",
        "readings": [
            {"type": "temperature", "value": 24.1, "unit": "C"},
            {"type": "humidity", "value": 41.0, "unit": "%"},
        ],
    }
    code, posted = req("POST", "/telemetry", payload)
    check("POST /telemetry", code == 200 and posted.get("status") == "ok", str(posted))

    code, current = req("GET", "/telemetry/current?device_id=pi-1")
    readings = current.get("readings") or {}
    check(
        "GET /telemetry/current из Redis",
        code == 200 and "temperature" in readings and "humidity" in readings,
        str(current),
    )

    history = {"count": 0}
    for _ in range(15):
        code, history = req("GET", "/telemetry/history?device_id=pi-1")
        if code == 200 and history.get("count", 0) >= 2:
            break
        time.sleep(0.5)
    check(
        "обработчик записал историю в SQLite",
        code == 200 and history.get("count", 0) >= 2,
        f"count={history.get('count')}",
    )

    code, bad = req("PUT", "/settings?device_id=pi-1", {"temp_min": 30, "temp_max": 20})
    check("PUT порогов: минимум >= максимума отклоняется", code == 400, str(bad))

    code, saved = req("PUT", "/settings?device_id=pi-1", {"temp_min": 18, "temp_max": 26})
    check("PUT /settings", code == 200 and saved.get("saved") is True, str(saved))

    code, got = req("GET", "/settings?device_id=pi-1")
    check(
        "GET /settings",
        code == 200 and got.get("temp_min") == 18 and got.get("temp_max") == 26,
        str(got),
    )
    print("Локальная проверка прошла.")


if __name__ == "__main__":
    main()
