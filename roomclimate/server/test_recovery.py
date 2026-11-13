# -*- coding: utf-8 -*-
"""Тесты восстановления пароля на временной SQLite, без Docker: python test_recovery.py"""
import contextlib
import io
import os
import re
import tempfile

os.environ["SQLITE_PATH"] = os.path.join(tempfile.mkdtemp(), "recovery.db")
os.environ["REDIS_URL"] = "redis://127.0.0.1:1/0"  # Redis не нужен: лимиты без него пропускаются
os.environ["SMTP_HOST"] = ""

from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402

EMAIL = "reset@example.com"


def _request_code(client) -> str:
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        r = client.post("/auth/password/forgot", json={"email": EMAIL})
    assert r.status_code == 200, r.text
    found = re.search(r"Код восстановления пароля для \S+: (\d{6})", out.getvalue())
    assert found, out.getvalue()
    return found.group(1)


def main():
    with TestClient(app) as client:
        r = client.post("/auth/register", json={"name": "Ира", "surname": "Тест", "email": EMAIL, "password": "old-pass"})
        assert r.status_code == 200, r.text
        old_token = r.json()["token"]

        # Неизвестный email — тот же ответ, без кода в журнале
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            r = client.post("/auth/password/forgot", json={"email": "nobody@example.com"})
        assert r.status_code == 200 and "Код восстановления" not in out.getvalue()
        print("OK  неизвестный email не раскрывается")

        code = _request_code(client)
        wrong = "000000" if code != "000000" else "111111"
        r = client.post("/auth/password/reset", json={"email": EMAIL, "code": wrong, "password": "new-pass"})
        assert r.status_code == 400 and "код" in r.json()["detail"].lower(), r.text
        r = client.post("/auth/password/reset", json={"email": EMAIL, "code": "12ab", "password": "new-pass"})
        assert r.status_code == 422 and "6 цифр" in r.json()["detail"], r.text
        print("OK  неверный код отклоняется, формат кода проверяется по-русски")

        r = client.post("/auth/password/reset", json={"email": EMAIL, "code": code, "password": "new-pass"})
        assert r.status_code == 200, r.text
        assert client.get("/auth/me", headers={"Authorization": f"Bearer {old_token}"}).status_code == 401
        assert client.post("/auth/login", json={"email": EMAIL, "password": "old-pass"}).status_code == 401
        assert client.post("/auth/login", json={"email": EMAIL, "password": "new-pass"}).status_code == 200
        r = client.post("/auth/password/reset", json={"email": EMAIL, "code": code, "password": "x-pass-2"})
        assert r.status_code == 400, "код одноразовый"
        print("OK  пароль сменён, старые сессии закрыты, код одноразовый")

        code = _request_code(client)
        for _ in range(5):
            client.post("/auth/password/reset", json={"email": EMAIL, "code": wrong, "password": "brute-1"})
        r = client.post("/auth/password/reset", json={"email": EMAIL, "code": code, "password": "brute-ok"})
        assert r.status_code == 400, "после 5 неверных попыток код сгорает"
        print("OK  перебор кода: после 5 ошибок код недействителен")
    print("Все проверки восстановления пароля пройдены")


if __name__ == "__main__":
    main()
