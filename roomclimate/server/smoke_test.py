# -*- coding: utf-8 -*-
"""Проверка API с хоста: python smoke_test.py"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8000"
# Если на сервере задан INGEST_TOKEN, передайте его и сюда (та же переменная окружения).
INGEST_TOKEN = os.environ.get("INGEST_TOKEN", "")


def req(
    method: str,
    path: str,
    body: dict | None = None,
    timeout: int = 10,
    token: str | None = None,
    raw: bytes | None = None,
    headers: dict | None = None,
) -> tuple[int, dict]:
    data = raw
    headers = dict(headers or {})
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    if data is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
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


def is_russian(text: object) -> bool:
    return isinstance(text, str) and any("а" <= ch.lower() <= "я" or ch in "ёЁ" for ch in text)


def main() -> None:
    print("База:", BASE)
    wait_health()
    code, health = req("GET", "/health")
    check("health", code == 200 and health.get("status") == "ok", str(health))

    stamp = int(time.time() * 1000)
    email = f"smoke_{stamp}@example.com"
    user = {"name": "Иван", "surname": "Тестов", "email": email, "password": "secret12"}
    code, registered = req("POST", "/auth/register", user)
    check(
        "POST /auth/register",
        code == 200 and registered.get("token") and registered.get("email") == email,
        str(registered),
    )
    token = registered["token"]

    code, again = req("POST", "/auth/register", user)
    check("повторная регистрация отклоняется", code == 409, str(again))

    code, again = req("POST", "/auth/register", dict(user, email=email.upper()))
    check("email без учёта регистра: ВЕРХНИЙ регистр тоже занят", code == 409, str(again))

    code, logged = req("POST", "/auth/login", {"email": email, "password": "secret12"})
    check(
        "POST /auth/login",
        code == 200 and logged.get("token") and logged.get("name") == "Иван",
        str(logged),
    )

    code, bad_login = req("POST", "/auth/login", {"email": email, "password": "wrong"})
    check("неверный пароль отклоняется", code == 401 and is_russian(bad_login.get("detail")), str(bad_login))

    code, me = req("GET", "/auth/me", token=token)
    check("GET /auth/me с токеном", code == 200 and me.get("email") == email, str(me))

    # --- Русские сообщения об ошибках вместо английского JSON pydantic ---
    code, short = req("POST", "/auth/register", dict(user, email=f"short_{stamp}@example.com", password="123"))
    check(
        "короткий пароль: понятная ошибка по-русски",
        code == 422 and short.get("detail") == "Пароль должен быть не короче 6 символов",
        str(short),
    )
    code, missing = req("POST", "/auth/register", {"name": "Иван", "email": "a@b.ru", "password": "123456"})
    check("пропущенное поле: ошибка по-русски", code == 422 and is_russian(missing.get("detail")), str(missing))
    code, bad_email = req("POST", "/auth/register", dict(user, email="@@@"))
    check("некорректный email отклоняется", code == 422 and bad_email.get("detail") == "Некорректный email", str(bad_email))
    code, bad_json = req("POST", "/auth/register", raw=b"{oops")
    check("битый JSON: ошибка по-русски", code == 422 and is_russian(bad_json.get("detail")), str(bad_json))

    # --- Кириллица в email, имени и пароле ---
    ru_email = f"иван{stamp}@почта.рф"
    ru_user = {"name": "Пётр", "surname": "Йодов", "email": ru_email.upper(), "password": "пароль-йё"}
    code, ru_reg = req("POST", "/auth/register", ru_user)
    check("регистрация с кириллическим email", code == 200 and ru_reg.get("email") == ru_email, str(ru_reg))
    decomposed = "пароль-и\u0306е\u0308"  # «й» и «ё» как буква + диакритика (как на некоторых клавиатурах)
    code, ru_login = req("POST", "/auth/login", {"email": ru_email, "password": decomposed})
    check("вход: «й»/«ё» в разной Unicode-записи совпадают", code == 200, str(ru_login))

    # --- Данные доступны только вошедшему пользователю ---
    code, anon = req("GET", "/telemetry/current?device_id=pi-1")
    check("без токена /telemetry/current закрыт", code == 401, str(anon))
    code, anon = req("PUT", "/settings?device_id=pi-1", {"temp_min": -50, "temp_max": 150})
    check("без токена пороги менять нельзя", code == 401, str(anon))

    payload = {
        "device_id": "pi-1",
        "readings": [
            {"type": "temperature", "value": 24.1, "unit": "C"},
            {"type": "humidity", "value": 41.0, "unit": "%"},
        ],
    }
    device_headers = {"X-Device-Token": INGEST_TOKEN} if INGEST_TOKEN else None
    if INGEST_TOKEN:
        code, denied = req("POST", "/telemetry", payload, headers={"X-Device-Token": "wrong"})
        check("POST /telemetry с неверным токеном устройства отклоняется", code == 401, str(denied))
    code, posted = req("POST", "/telemetry", payload, headers=device_headers)
    check("POST /telemetry", code == 200 and posted.get("status") == "ok", str(posted))

    code, current = req("GET", "/telemetry/current?device_id=pi-1", token=token)
    readings = current.get("readings") or {}
    check(
        "GET /telemetry/current из Redis",
        code == 200 and "temperature" in readings and "humidity" in readings,
        str(current),
    )

    history = {"count": 0}
    for _ in range(15):
        code, history = req("GET", "/telemetry/history?device_id=pi-1", token=token)
        if code == 200 and history.get("count", 0) >= 2:
            break
        time.sleep(0.5)
    check(
        "обработчик записал историю в SQLite",
        code == 200 and history.get("count", 0) >= 2,
        f"count={history.get('count')}",
    )

    code, bad = req("PUT", "/settings?device_id=pi-1", {"temp_min": 30, "temp_max": 20}, token=token)
    check("PUT порогов: минимум >= максимума отклоняется", code == 400 and is_russian(bad.get("detail")), str(bad))

    code, nan = req("PUT", "/settings?device_id=pi-1", raw=b'{"temp_min": NaN, "temp_max": Infinity}', token=token)
    check("PUT порогов: NaN/Infinity не роняют сервер", code == 422, str(nan))

    code, saved = req("PUT", "/settings?device_id=pi-1", {"temp_min": 18, "temp_max": 26}, token=token)
    check("PUT /settings", code == 200 and saved.get("saved") is True, str(saved))

    code, got = req("GET", "/settings?device_id=pi-1", token=token)
    check(
        "GET /settings",
        code == 200
        and got.get("temp_min") == 18
        and got.get("temp_max") == 26
        and got.get("humidity_min") == 30
        and got.get("humidity_max") == 70,
        str(got),
    )

    # --- Выход делает токен недействительным ---
    code, _ = req("POST", "/auth/logout", token=token)
    code, me = req("GET", "/auth/me", token=token)
    check("после выхода токен недействителен", code == 401, str(me))

    # --- Защита от подбора пароля ---
    victim = f"brute_{stamp}@example.com"
    req("POST", "/auth/register", dict(user, email=victim))
    last = (0, {})
    for _ in range(12):
        last = req("POST", "/auth/login", {"email": victim, "password": "guess"})
        if last[0] == 429:
            break
    check("подбор пароля блокируется (429)", last[0] == 429 and is_russian(last[1].get("detail")), str(last))
    print("Локальная проверка прошла.")

if __name__ == "__main__":
    main()
