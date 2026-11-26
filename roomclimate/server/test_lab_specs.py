# -*- coding: utf-8 -*-
"""Автотесты по спецификациям unit-тестов из Docs/testing.md (лабораторные № 5 и № 6).

Каждый тест называется по номеру спецификации UT-xx и построен по схеме
«подготовить — выполнить — проверить». Временная SQLite, без Docker и без Redis
(Redis намеренно недоступен: так проверяется и реакция на его отказ).

Учебный PR лабораторной № 6 показывает на этих тестах переход «зелёный → красный → зелёный».

Запуск одной командой (из roomclimate/server):
    python -m unittest -v test_lab_specs
"""
import os
import tempfile
import unittest
from datetime import datetime, timedelta, timezone

os.environ["SQLITE_PATH"] = os.path.join(tempfile.mkdtemp(), "lab_specs.db")
os.environ["REDIS_URL"] = "redis://127.0.0.1:1/0"  # Redis недоступен
os.environ["ALERT_DELAY_SECONDS"] = "60"
os.environ["NTFY_URL"] = ""  # без отправки push
os.environ["SMTP_HOST"] = ""
os.environ["INGEST_TOKEN"] = "lab-test-device-token"  # тестовый секрет платы, не настоящий
DEVICE = {"X-Device-Token": "lab-test-device-token"}

from fastapi.testclient import TestClient  # noqa: E402

from app import alerts, db, worker  # noqa: E402
from app.main import app  # noqa: E402

T0 = datetime(2026, 11, 20, 12, 0, tzinfo=timezone.utc)


def at(seconds: int) -> str:
    return (T0 + timedelta(seconds=seconds)).isoformat()


class _ApiCase(unittest.TestCase):
    """Общий клиент и пользователи для тестов через HTTP-интерфейс модулей."""

    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(app)
        cls.client.__enter__()  # запускает lifespan: создание таблиц и миграции
        prefix = cls.__name__.lower()
        cls.owner_email = f"owner-{prefix}@example.com"
        cls.owner = cls._register(cls.owner_email)
        cls.stranger = cls._register(f"stranger-{prefix}@example.com")

    @classmethod
    def tearDownClass(cls):
        cls.client.__exit__(None, None, None)

    @classmethod
    def _register(cls, email: str) -> dict:
        r = cls.client.post("/auth/register", json={
            "name": "Тест", "surname": "Лабораторный", "email": email, "password": "secret12"})
        assert r.status_code == 200, r.text
        return {"Authorization": f"Bearer {r.json()['token']}"}


class ThresholdsSpecs(_ApiCase):
    """Модуль «Пороги» (US-03, FR-05)."""

    def test_ut01_save_valid_thresholds(self):
        # Подготовить: новая плата без сохранённых порогов
        device = "lab-ut01"
        # Выполнить
        r = self.client.put(f"/settings?device_id={device}", headers=self.owner,
                            json={"temp_min": 20, "temp_max": 24})
        # Проверить: сохранено и при повторном чтении те же значения
        self.assertEqual(r.status_code, 200, r.text)
        self.assertTrue(r.json()["saved"])
        again = self.client.get(f"/settings?device_id={device}", headers=self.owner).json()
        self.assertEqual((again["temp_min"], again["temp_max"], again["saved"]), (20, 24, True))

    def test_ut02_min_greater_than_max_rejected(self):
        device = "lab-ut02"
        self.client.put(f"/settings?device_id={device}", headers=self.owner, json={"temp_min": 20, "temp_max": 24})
        r = self.client.put(f"/settings?device_id={device}", headers=self.owner, json={"temp_min": 25, "temp_max": 24})
        self.assertEqual(r.status_code, 400)
        self.assertEqual(r.json(), {"detail": "Минимальная температура должна быть меньше максимальной"})
        kept = self.client.get(f"/settings?device_id={device}", headers=self.owner).json()
        self.assertEqual((kept["temp_min"], kept["temp_max"]), (20, 24), "сохранённые пороги изменились")

    def test_ut03_equal_min_and_max_rejected(self):
        r = self.client.put("/settings?device_id=lab-ut03", headers=self.owner, json={"temp_min": 24, "temp_max": 24})
        self.assertEqual(r.status_code, 400)
        self.assertFalse(self.client.get("/settings?device_id=lab-ut03", headers=self.owner).json()["saved"])

    def test_ut04_out_of_range_rejected(self):
        r = self.client.put("/settings?device_id=lab-ut04", headers=self.owner, json={"temp_min": 18, "temp_max": 300})
        self.assertEqual(r.status_code, 422)
        self.assertIn("Максимальная температура", r.json()["detail"])
        self.assertIn("200", r.json()["detail"])

    def test_ut05_range_edges_accepted(self):
        r = self.client.put("/settings?device_id=lab-ut05", headers=self.owner, json={"temp_min": -100, "temp_max": 200})
        self.assertEqual(r.status_code, 200, r.text)

    def test_ut06_defaults_for_new_device(self):
        r = self.client.get("/settings?device_id=lab-ut06", headers=self.owner)
        self.assertEqual(r.status_code, 200)
        body = r.json()
        self.assertEqual((body["temp_min"], body["temp_max"], body["saved"]), (18.0, 26.0, False))


class AlertSpecs(unittest.TestCase):
    """Модуль «Обработчик» → «Проверка порогов» (US-04, FR-07). Порог 18…26 °C, гистерезис 0,5."""

    def test_ut07_value_above_max_goes_above(self):
        self.assertEqual(alerts.next_status("ok", 26.2, 18, 26, 0.5), "above")

    def test_ut08_inside_hysteresis_stays_above(self):
        self.assertEqual(alerts.next_status("above", 25.8, 18, 26, 0.5), "above")

    def test_ut09_back_with_margin_goes_ok(self):
        self.assertEqual(alerts.next_status("above", 25.5, 18, 26, 0.5), "ok")

    def _open_alerts(self, conn, device):
        return conn.execute("SELECT * FROM alerts WHERE device_id = ? AND resolved_at IS NULL", (device,)).fetchall()

    def test_ut10_short_spike_no_alert(self):
        db.init_db()
        conn = db.connect()
        try:
            alerts.evaluate(conn, "lab-ut10", "temperature", 27.0, at(0))
            alerts.evaluate(conn, "lab-ut10", "temperature", 27.0, at(30))
            alerts.evaluate(conn, "lab-ut10", "temperature", 24.0, at(40))
            self.assertEqual(self._open_alerts(conn, "lab-ut10"), [])
        finally:
            conn.close()

    def test_ut11_long_violation_one_alert(self):
        db.init_db()
        conn = db.connect()
        try:
            for s in range(0, 130, 10):  # 27 °C держится 2 минуты
                alerts.evaluate(conn, "lab-ut11", "temperature", 27.0, at(s))
            opened = self._open_alerts(conn, "lab-ut11")
            self.assertEqual(len(opened), 1)
            self.assertIn("выше порога 26,0", opened[0]["message"])
        finally:
            conn.close()

    def test_ut12_late_measurement_no_alert(self):
        db.init_db()
        # Досланные из буфера показания 30 °C: в историю пишутся, тревог не открывают
        for s in range(0, 130, 10):
            worker.process_message({"device_id": "lab-ut12", "type": "temperature", "value": "30.0",
                                    "unit": "C", "measured_at": at(s), "late": "1"})
        conn = db.connect()
        try:
            self.assertEqual(self._open_alerts(conn, "lab-ut12"), [])
            stored = conn.execute(
                "SELECT COUNT(*) FROM measurements m JOIN sensors s ON s.id = m.sensor_id WHERE s.device_id = ?",
                ("lab-ut12",)).fetchone()[0]
            self.assertEqual(stored, 13)
        finally:
            conn.close()


class IngestSpecs(_ApiCase):
    """Модуль «Приём телеметрии» (FR-02)."""

    def test_ut13_unknown_metric_rejected(self):
        r = self.client.post("/telemetry", headers=DEVICE, json={"device_id": "pi-1", "readings": [
            {"type": "temperature", "value": 22.4, "unit": "C"},
            {"type": "radiation", "value": 1, "unit": "uSv"}]})
        self.assertEqual(r.status_code, 422)
        self.assertIn("Неизвестный показатель: radiation", r.json()["detail"])

    def test_ut14_empty_readings_rejected(self):
        r = self.client.post("/telemetry", headers=DEVICE, json={"device_id": "pi-1", "readings": []})
        self.assertEqual(r.status_code, 422)

    def test_ut15_redis_down_returns_503(self):
        r = self.client.post("/telemetry", headers=DEVICE, json={"device_id": "pi-1", "readings": [
            {"type": "temperature", "value": 22.4, "unit": "C"}]})
        self.assertEqual(r.status_code, 503)
        self.assertEqual(r.json()["detail"], "Redis недоступен")

    def test_ut19_device_token_required(self):
        pack = {"device_id": "pi-1", "readings": [{"type": "temperature", "value": 22.4, "unit": "C"}]}
        r = self.client.post("/telemetry", json=pack)
        self.assertEqual(r.status_code, 401)
        r = self.client.post("/telemetry", headers={"X-Device-Token": "wrong"}, json=pack)
        self.assertEqual(r.status_code, 401)
        self.assertEqual(r.json()["detail"], "Неверный токен устройства")


class AccessSpecs(_ApiCase):
    """Модуль «Аккаунты и доступ» (US-08, NFR-03)."""

    def test_ut16_wrong_password(self):
        r = self.client.post("/auth/login", json={"email": self.owner_email, "password": "wrong-pass"})
        self.assertEqual(r.status_code, 401)
        self.assertEqual(r.json()["detail"], "Неверный email или пароль")

    def test_ut17_no_token(self):
        r = self.client.get("/settings?device_id=pi-1")
        self.assertEqual(r.status_code, 401)

    def test_ut18_foreign_device_hidden(self):
        # Владелец закрепляет плату; посторонний не видит её пороги
        self.assertEqual(self.client.post("/devices/lab-ut18/claim", headers=self.owner).status_code, 200)
        r = self.client.get("/settings?device_id=lab-ut18", headers=self.stranger)
        self.assertEqual(r.status_code, 404)
        self.assertEqual(r.json()["detail"], "Устройство не найдено")


if __name__ == "__main__":
    unittest.main(verbosity=2)
