# -*- coding: utf-8 -*-
"""Незнакомый серверу показатель не блокирует остальные: python test_sender.py"""
from __future__ import annotations

import io
import json
import unittest
import urllib.error

import collector


def http_error(code: int, detail: str) -> urllib.error.HTTPError:
    return urllib.error.HTTPError("u", code, "x", {}, io.BytesIO(json.dumps({"detail": detail}).encode()))


class FakeServer:
    """Как сервер: показатель не из справочника → 422 на весь запрос."""

    def __init__(self, known):
        self.known = set(known)
        self.got = []

    def __call__(self, body):
        unknown = sorted({r["type"] for r in body["readings"]} - self.known)
        if unknown:
            raise http_error(422, f"Неизвестный показатель: {', '.join(unknown)}. Добавьте его в metric_types миграцией")
        self.got.append(body)
        return "ok"


def body(*types, measured_at=None):
    b = {"device_id": "pi-1", "readings": [{"type": t, "value": 1, "unit": "x"} for t in types]}
    if measured_at:
        b["measured_at"] = measured_at
    return b


def raise_(exc):
    raise exc


class SenderTests(unittest.TestCase):
    def setUp(self):
        self.now = [0.0]
        self.server = FakeServer({"temperature", "humidity"})
        self.logs = []
        self.send = collector.Sender(self.server, clock=lambda: self.now[0], recheck=600,
                                     log=lambda *a, **k: self.logs.append(a[0]))

    def test_unknown_dropped_rest_sent(self):
        self.assertEqual(self.send(body("temperature", "humidity", "pressure")), "ok")
        self.assertEqual([r["type"] for r in self.server.got[0]["readings"]], ["temperature", "humidity"])
        self.assertIn("pressure", self.logs[0])
        self.assertIn("миграцией", self.logs[0])

    def test_remembered_then_rechecked(self):
        self.send(body("temperature", "pressure"))
        self.send(body("temperature", "pressure"))
        self.assertEqual(len(self.logs), 1, "второй раз не спамим в журнал и не ловим 422")
        self.server.known.add("pressure")  # сделали миграцию
        self.now[0] += 601
        self.send(body("temperature", "pressure"))
        self.assertEqual([r["type"] for r in self.server.got[-1]["readings"]], ["temperature", "pressure"])

    def test_only_unknown_skipped(self):
        self.assertIn("пропущено", self.send(body("pressure")))
        self.assertIn("пропущено", self.send(body("pressure")))
        self.assertEqual(self.server.got, [])

    def test_measured_at_kept_for_buffer(self):
        self.send(body("temperature", "pressure", measured_at="2026-11-18T10:00:00+00:00"))
        self.assertEqual(self.server.got[0]["measured_at"], "2026-11-18T10:00:00+00:00")

    def test_other_errors_pass_through(self):
        with self.assertRaises(urllib.error.HTTPError):
            collector.Sender(lambda b: raise_(http_error(422, "Время замера в будущем — проверьте часы платы")))(
                body("temperature"))
        with self.assertRaises(urllib.error.HTTPError):
            collector.Sender(lambda b: raise_(http_error(401, "Нужен токен")))(body("temperature"))

    def test_parse_detail(self):
        exc = http_error(422, "Неизвестный показатель: co2x, light.v2. Добавьте его в metric_types миграцией")
        self.assertEqual(collector.unknown_types(exc), {"co2x", "light.v2"})


if __name__ == "__main__":
    unittest.main(verbosity=2)
