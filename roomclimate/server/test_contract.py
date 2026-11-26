# -*- coding: utf-8 -*-
"""Контрактный тест (лабораторная № 7): сервер соответствует Docs/api/openapi.yaml.

Проверяет, что каждая операция из контракта есть в приложении FastAPI, что обязательные поля
запросов совпадают со схемами pydantic, и что реальные ответы содержат обязательные поля
схем контракта, а ошибки — тело {"detail": "..."}.

Запуск (из roomclimate/server; нужен PyYAML):
    python -m unittest -v test_contract
"""
import os
import tempfile
import unittest

import yaml

os.environ["SQLITE_PATH"] = os.path.join(tempfile.mkdtemp(), "contract.db")
os.environ["REDIS_URL"] = "redis://127.0.0.1:1/0"
os.environ["SMTP_HOST"] = ""

from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402

SPEC_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "Docs", "api", "openapi.yaml")
METHODS = ("get", "post", "put", "patch", "delete")


def load_spec() -> dict:
    with open(SPEC_PATH, encoding="utf-8") as f:
        return yaml.safe_load(f)


def schema(spec: dict, ref_or_schema: dict) -> dict:
    """Разворачивает $ref и allOf в один объект с properties/required."""
    if "$ref" in ref_or_schema:
        name = ref_or_schema["$ref"].rsplit("/", 1)[-1]
        return schema(spec, spec["components"]["schemas"][name])
    if "allOf" in ref_or_schema:
        merged = {"properties": {}, "required": []}
        for part in ref_or_schema["allOf"]:
            s = schema(spec, part)
            merged["properties"].update(s.get("properties", {}))
            merged["required"] += s.get("required", [])
        return merged
    return ref_or_schema


class ContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()
        cls.impl = app.openapi()
        cls.client = TestClient(app)
        cls.client.__enter__()
        r = cls.client.post("/auth/register", json={
            "name": "Контракт", "surname": "Тест", "email": "contract@example.com", "password": "secret12"})
        assert r.status_code == 200, r.text
        cls.auth_body = r.json()
        cls.auth = {"Authorization": f"Bearer {cls.auth_body['token']}"}

    @classmethod
    def tearDownClass(cls):
        cls.client.__exit__(None, None, None)

    def test_every_operation_is_implemented(self):
        missing = []
        for path, item in self.spec["paths"].items():
            for method in METHODS:
                if method in item and method not in self.impl["paths"].get(path, {}):
                    missing.append(f"{method.upper()} {path}")
        self.assertEqual(missing, [], "операции контракта, которых нет на сервере")

    def test_request_required_fields_match(self):
        pairs = {"SettingsIn": "SettingsIn", "TelemetryIn": "TelemetryIn", "LoginRequest": "LoginIn",
                 "RegisterRequest": "RegisterIn", "RoomIn": "RoomIn"}
        impl_schemas = self.impl["components"]["schemas"]
        for contract_name, model_name in pairs.items():
            with self.subTest(schema=contract_name):
                contract = set(self.spec["components"]["schemas"][contract_name].get("required", []))
                actual = set(impl_schemas[model_name].get("required", []))
                self.assertEqual(contract, actual)

    def _assert_has_required(self, body: dict, schema_name: str):
        s = schema(self.spec, {"$ref": f"#/components/schemas/{schema_name}"})
        missing = [f for f in s.get("required", []) if f not in body]
        self.assertEqual(missing, [], f"в ответе нет обязательных полей {schema_name}")

    def test_auth_response_shape(self):
        self._assert_has_required(self.auth_body, "AuthResponse")

    def test_settings_response_shape(self):
        r = self.client.get("/settings?device_id=contract-1", headers=self.auth)
        self.assertEqual(r.status_code, 200)
        self._assert_has_required(r.json(), "Settings")
        r = self.client.put("/settings?device_id=contract-1", headers=self.auth, json={"temp_min": 19, "temp_max": 25})
        self.assertEqual(r.status_code, 200)
        self._assert_has_required(r.json(), "Settings")

    def test_rooms_shape(self):
        r = self.client.post("/rooms", headers=self.auth, json={"name": "Кухня"})
        self.assertEqual(r.status_code, 200)
        self._assert_has_required(r.json(), "RoomCreated")
        r = self.client.get("/rooms", headers=self.auth)
        self.assertEqual(r.status_code, 200)
        self._assert_has_required(r.json(), "RoomList")

    def test_errors_use_error_schema(self):
        cases = [
            self.client.put("/settings?device_id=contract-2", headers=self.auth, json={"temp_min": 30, "temp_max": 20}),
            self.client.get("/settings?device_id=contract-2"),
            self.client.delete("/rooms/999999", headers=self.auth),
            self.client.post("/auth/login", json={"email": "x@example.com"}),
        ]
        for r in cases:
            with self.subTest(url=str(r.request.url), status=r.status_code):
                self.assertGreaterEqual(r.status_code, 400)
                self._assert_has_required(r.json(), "Error")
                self.assertIsInstance(r.json()["detail"], str)


if __name__ == "__main__":
    unittest.main(verbosity=2)
