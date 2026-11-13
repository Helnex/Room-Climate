# -*- coding: utf-8 -*-
"""Тесты «Мой дом»: помещения, датчик, профиль, безопасность, семья, помощь.
Временная SQLite, без Docker: python test_home.py"""
import os
import tempfile

os.environ["SQLITE_PATH"] = os.path.join(tempfile.mkdtemp(), "home.db")
os.environ["REDIS_URL"] = "redis://127.0.0.1:1/0"
os.environ["SMTP_HOST"] = ""

from fastapi.testclient import TestClient  # noqa: E402

from app import db  # noqa: E402
from app.main import app  # noqa: E402


def register(client, email, name="Дина", password="secret12"):
    r = client.post("/auth/register", json={"name": name, "surname": "Тест", "email": email, "password": password},
                    headers={"User-Agent": "okhttp/4.12.0"})
    assert r.status_code == 200, r.text
    return {"Authorization": f"Bearer {r.json()['token']}"}


def main():
    with TestClient(app) as c:
        a = register(c, "owner@example.com")
        b = register(c, "member@example.com", name="Максим")

        # Помещения
        r = c.post("/rooms", json={"name": "  Гостиная "}, headers=a)
        assert r.status_code == 200 and r.json()["name"] == "Гостиная", r.text
        room = r.json()["id"]
        assert c.post("/rooms", json={"name": "Гостиная"}, headers=a).status_code == 409
        assert c.post("/rooms", json={"name": "   "}, headers=a).status_code == 422
        bedroom = c.post("/rooms", json={"name": "Спальня"}, headers=a).json()["id"]
        assert c.patch(f"/rooms/{bedroom}", json={"name": "Детская"}, headers=a).status_code == 200
        assert c.patch(f"/rooms/{bedroom}", json={"name": "Чужая"}, headers=b).status_code == 404
        print("OK  помещения: создание, уникальность, переименование, чужое не трогается")

        # Добавление датчика (до первых показаний) — закрепляется за пользователем
        r = c.post("/devices/pi-7/setup", json={"name": "Датчик в гостиной", "room_id": room}, headers=a)
        assert r.status_code == 200, r.text
        assert c.post("/devices/pi-7/setup", json={"name": "Моё"}, headers=b).status_code == 404
        assert c.post(f"/devices/pi-8/setup", json={"name": "X", "room_id": room}, headers=b).status_code == 404
        conn = db.connect()
        sid = db.ensure_device_and_sensor(conn, "pi-7", "temperature", "C")
        db.insert_measurement(conn, sid, 23.4, db.utc_now())
        conn.commit()
        conn.close()
        rooms = c.get("/rooms", headers=a).json()
        living = next(x for x in rooms["rooms"] if x["id"] == room)
        assert living["devices"][0]["name"] == "Датчик в гостиной" and living["devices"][0]["temperature"] == 23.4, rooms
        assert c.get("/rooms", headers=b).json()["rooms"] == [], "чужие помещения не видны"
        print("OK  датчик: закреплён, назван, в помещении; чужим недоступен")

        # Семейный доступ
        assert c.post("/family", json={"email": "nobody@example.com"}, headers=a).status_code == 404
        assert c.post("/family", json={"email": "member@example.com"}, headers=b).status_code == 400  # нет своих плат
        assert c.post("/family", json={"email": "member@example.com"}, headers=a).status_code == 200
        fam = c.get("/family", headers=a).json()
        assert [p["role"] for p in fam["people"]] == ["owner", "member"], fam
        assert any(x["id"] == room for x in c.get("/rooms", headers=b).json()["rooms"]), "участник видит помещение"
        assert c.get("/settings?device_id=pi-7", headers=b).status_code == 200
        assert c.delete("/family?email=member@example.com", headers=a).status_code == 200
        assert c.get("/settings?device_id=pi-7", headers=b).status_code == 404
        print("OK  семья: приглашение на все платы владельца и отзыв")

        # Профиль
        r = c.patch("/auth/me", json={"name": "Дина", "surname": "Мейрон", "email": "Dina@Example.com"}, headers=a)
        assert r.status_code == 200 and r.json()["email"] == "dina@example.com", r.text
        assert c.patch("/auth/me", json={"name": "Д", "surname": "М", "email": "member@example.com"}, headers=a).status_code == 409
        print("OK  профиль: изменение и защита от чужого email")

        # Безопасность: история входов и смена пароля
        a2 = {"Authorization": "Bearer " + c.post("/auth/login", json={"email": "dina@example.com", "password": "secret12"}).json()["token"]}
        sessions = c.get("/auth/sessions", headers=a).json()["sessions"]
        assert len(sessions) == 2 and sum(s["current"] for s in sessions) == 1, sessions
        assert any(s["client"].startswith("Приложение") for s in sessions), sessions
        assert c.post("/auth/password/change", json={"old_password": "wrong", "new_password": "newpass1"}, headers=a).status_code == 400
        assert c.post("/auth/password/change", json={"old_password": "secret12", "new_password": "newpass1"}, headers=a).status_code == 200
        assert c.get("/auth/me", headers=a).status_code == 200, "текущий вход остаётся"
        assert c.get("/auth/me", headers=a2).status_code == 401, "другие входы закрыты"
        assert c.post("/auth/login", json={"email": "dina@example.com", "password": "newpass1"}).status_code == 200
        print("OK  безопасность: история входов, смена пароля закрывает другие входы")

        # Помощь
        r = c.post("/support", json={"topic": "Уведомления", "message": "Не приходят push"}, headers=a)
        assert r.status_code == 200 and r.json()["emailed"] is False, r.text
        assert c.post("/support", json={"topic": " ", "message": "x"}, headers=a).status_code == 422
        print("OK  помощь: сообщение сохраняется (без почты — без письма)")

        # Удаление помещения отвязывает платы
        assert c.delete(f"/rooms/{room}", headers=a).status_code == 200
        assert c.get("/rooms", headers=a).json()["unassigned"][0]["device_id"] == "pi-7"
        print("OK  удаление помещения: плата остаётся без помещения")
    print("Все проверки «Мой дом» пройдены")


if __name__ == "__main__":
    main()
