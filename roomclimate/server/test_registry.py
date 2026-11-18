# -*- coding: utf-8 -*-
"""Тесты реестра плат и датчиков и миграций: python test_registry.py
Цепочка из задачи: датчик заведён миграцией → сервер его видит → приложение выбирает его
в списке и привязывает к плате помещения → показания платы видны у датчика."""
import os
import shutil
import tempfile
from pathlib import Path

os.environ["SQLITE_PATH"] = os.path.join(tempfile.mkdtemp(), "registry.db")
os.environ["REDIS_URL"] = "redis://127.0.0.1:1/0"
os.environ["SMTP_HOST"] = ""

from fastapi.testclient import TestClient  # noqa: E402

from app import db, migrate  # noqa: E402
from app.main import app  # noqa: E402


def register(client, email):
    r = client.post("/auth/register", json={"name": "Тест", "surname": "Тест", "email": email, "password": "secret12"})
    assert r.status_code == 200, r.text
    return {"Authorization": f"Bearer {r.json()['token']}"}


def by(items, key, value):
    return next(x for x in items if x[key] == value)


def measure(device_id, metric, unit, value):
    conn = db.connect()
    sid = db.ensure_device_and_sensor(conn, device_id, metric, unit)
    db.insert_measurement(conn, sid, value, db.utc_now())
    conn.commit()
    conn.close()


def test_statements():
    sql = "-- комментарий\nINSERT INTO t VALUES ('a;b');\n\nUPDATE t SET x = 1;\n-- конец\n"
    assert migrate.statements(sql) == ["-- комментарий\nINSERT INTO t VALUES ('a;b');", "UPDATE t SET x = 1;"]
    try:
        migrate.statements("INSERT INTO t VALUES (1)")
        raise AssertionError("незаконченная команда должна быть ошибкой")
    except RuntimeError:
        pass
    print("OK  миграции: разбор SQL-файла на команды")


def main():
    test_statements()
    with TestClient(app) as c:
        a = register(c, "owner@example.com")
        b = register(c, "other@example.com")

        # Справочники из миграции 002
        t = c.get("/registry/types", headers=a).json()
        assert [m["code"] for m in t["metrics"]] == ["temperature", "humidity", "co2", "light", "motion"], t
        climate = by(t["sensor_types"], "code", "climate")
        assert [m["code"] for m in climate["metrics"]] == ["temperature", "humidity"], climate
        assert c.get("/registry/types").status_code == 401
        print("OK  справочники показателей и видов датчиков")

        # Платы из базы: все свободны
        boards = c.get("/registry/boards", headers=a).json()
        assert boards["count"] == 3 and boards["free"] == 3, boards
        assert by(boards["boards"], "device_id", "pi-1")["title"] == "Плата № 0001"

        # Новое помещение с платой
        r = c.post("/rooms", json={"name": "Склад1", "board_id": "pi-1"}, headers=a)
        assert r.status_code == 200 and r.json()["board"]["title"] == "Плата № 0001", r.text
        sklad1 = r.json()["id"]
        assert c.post("/rooms", json={"name": "Чужой", "board_id": "pi-1"}, headers=b).status_code == 409
        assert c.post("/rooms", json={"name": "Ещё", "board_id": "pi-1"}, headers=a).status_code == 409
        assert c.post("/rooms", json={"name": "Нет", "board_id": "pi-404"}, headers=a).status_code == 404
        sklad2 = c.post("/rooms", json={"name": "Склад2", "board_id": "pi-2"}, headers=a).json()["id"]
        mine = by(c.get("/registry/boards", headers=a).json()["boards"], "device_id", "pi-1")
        assert mine["status"] == "mine" and mine["room"]["name"] == "Склад1" and not mine["selectable"], mine
        theirs = by(c.get("/registry/boards", headers=b).json()["boards"], "device_id", "pi-1")
        assert theirs["status"] == "taken" and theirs["room"] is None and not theirs["selectable"], theirs
        rooms = c.get("/rooms", headers=a).json()["rooms"]
        assert by(rooms, "id", sklad1)["devices"][0]["title"] == "Плата № 0001", rooms
        print("OK  помещение создаётся с платой из базы, занятую плату взять нельзя")

        # Датчики из базы: привязка к плате помещения
        sensors = c.get("/registry/sensors", headers=a).json()
        assert sensors["count"] == 6 and sensors["free"] == 6, sensors
        r = c.post("/registry/sensors/0142/bind", json={"room_id": sklad1, "name": "Датчик склад1"}, headers=a)
        assert r.status_code == 200, r.text
        s = r.json()
        assert s["status"] == "mine" and s["board"]["device_id"] == "pi-1" and s["room"]["name"] == "Склад1", s
        assert c.post("/registry/sensors/0143/bind", json={"room_id": sklad1}, headers=a).status_code == 409, \
            "второй датчик того же вида на плате"
        assert c.post("/registry/sensors/0142/bind", json={"room_id": sklad2}, headers=a).status_code == 409, \
            "датчик уже на другой плате"
        r = c.post("/registry/sensors/0143/bind", json={"room_id": sklad2}, headers=a)
        assert r.status_code == 200 and r.json()["name"] == "Климат", r.text
        assert c.post("/registry/sensors/0144/bind", json={"room_id": sklad1}, headers=b).status_code == 404
        assert c.post("/registry/sensors/9999/bind", json={"room_id": sklad1}, headers=a).status_code == 404
        assert c.post("/registry/sensors/0145/bind", json={"room_id": sklad1, "name": "Свет"}, headers=a).status_code == 200
        other = by(c.get("/registry/sensors", headers=b).json()["sensors"], "serial", "0142")
        assert other["status"] == "taken" and other["board"] is None and other["name"] is None, other
        print("OK  датчик привязывается к плате помещения; одинаковые на одной плате — нельзя")

        # Показания платы → у датчика
        measure("pi-1", "temperature", "C", 21.5)
        measure("pi-1", "humidity", "%", 44.0)
        sensors = c.get("/registry/sensors", headers=a).json()["sensors"]
        s1 = by(sensors, "serial", "0142")
        assert [m["value"] for m in s1["metrics"]] == [21.5, 44.0] and s1["online"] is True, s1
        s2 = by(sensors, "serial", "0143")
        assert s2["metrics"][0]["value"] is None and s2["online"] is False, "склад2 без данных — пусто"
        light = by(sensors, "serial", "0145")
        assert light["metrics"][0]["value"] is None and light["online"] is False, "свет на плате ещё не мерили"
        print("OK  показания платы видны у её датчиков, у другой платы — нет")

        # Неизвестный показатель не принимается (проверка до Redis)
        r = c.post("/telemetry", json={"device_id": "pi-1", "readings": [{"type": "temprature", "value": 1, "unit": "C"}]})
        assert r.status_code == 422 and "temprature" in r.json()["detail"], r.text
        print("OK  показание неизвестного типа — 422")

        # Новый датчик миграцией → появляется в списке
        tmp = Path(tempfile.mkdtemp())
        for f in migrate.MIGRATIONS_DIR.glob("*.sql"):
            shutil.copy(f, tmp / f.name)
        (tmp / "003_new_sensor.sql").write_text(
            "INSERT INTO sensor_registry(serial, sensor_type) VALUES ('0199', 'light');\n", encoding="utf-8")
        (tmp / "004_broken.sql").write_text(
            "INSERT INTO sensor_registry(serial, sensor_type) VALUES ('0200', 'light');\nSELECT * FROM nope;\n",
            encoding="utf-8")
        migrate.MIGRATIONS_DIR = tmp
        conn = db.connect()
        try:
            try:
                migrate.apply(conn)
                raise AssertionError("сломанная миграция должна упасть")
            except RuntimeError as exc:
                assert "004_broken" in str(exc), exc
            (tmp / "004_broken.sql").unlink()
            assert migrate.apply(conn) == [], "повторно ничего не применяется"
            versions = [r[0] for r in conn.execute("SELECT version FROM schema_migrations ORDER BY version")]
        finally:
            conn.close()
        assert versions == [1, 2, 3], versions
        serials = [s["serial"] for s in c.get("/registry/sensors", headers=a).json()["sensors"]]
        assert "0199" in serials and "0200" not in serials, serials
        print("OK  новый датчик из миграции 003 виден в списке; сломанная миграция откатилась целиком")

        # Отвязать датчик, удалить помещение — плата снова доступна для нового помещения
        assert c.delete("/registry/sensors/0142/bind", headers=b).status_code in (403, 404)
        assert c.delete("/registry/sensors/0142/bind", headers=a).status_code == 200
        assert by(c.get("/registry/sensors", headers=a).json()["sensors"], "serial", "0142")["status"] == "free"
        assert c.delete(f"/rooms/{sklad2}", headers=a).status_code == 200
        pi2 = by(c.get("/registry/boards", headers=a).json()["boards"], "device_id", "pi-2")
        assert pi2["status"] == "mine" and pi2["selectable"] and pi2["room"] is None, pi2
        assert c.post("/rooms", json={"name": "Склад2", "board_id": "pi-2"}, headers=a).status_code == 200
        print("OK  отвязка датчика, плата удалённого помещения снова выбирается")

    print("\nВсе проверки реестра пройдены")


if __name__ == "__main__":
    main()
