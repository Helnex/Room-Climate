# -*- coding: utf-8 -*-
"""Проверки буфера показаний (связь пропала → досылка): python test_buffer.py"""
from __future__ import annotations

import os
import tempfile
import unittest
import urllib.error
from datetime import datetime, timezone

import buffer


def body(n: int) -> dict:
    return {"device_id": "pi-1", "readings": [{"type": "temperature", "value": float(n), "unit": "C"}]}


class BufferTests(unittest.TestCase):
    def setUp(self):
        self.path = os.path.join(tempfile.mkdtemp(), "state", "buffer.jsonl")
        self.buf = buffer.Buffer(self.path, max_items=5)

    def test_add_and_flush_in_order(self):
        for n in range(3):
            self.buf.add(body(n))
        self.assertEqual(len(self.buf), 3)
        sent = []
        self.assertEqual(self.buf.flush(sent.append, limit=2), 2)
        self.assertEqual([b["readings"][0]["value"] for b in sent], [0.0, 1.0])
        self.assertTrue(all("measured_at" in b for b in sent))
        self.assertEqual(len(self.buf), 1)

    def test_stops_on_network_error(self):
        for n in range(3):
            self.buf.add(body(n))
        calls = []

        def send(b):
            calls.append(b)
            if len(calls) == 2:
                raise urllib.error.URLError("нет сети")

        self.assertEqual(self.buf.flush(send), 1)
        self.assertEqual(len(self.buf), 2, "неотправленное остаётся")

    def test_drops_rejected(self):
        self.buf.add(body(1))
        self.buf.add(body(2))

        def send(b):
            if b["readings"][0]["value"] == 1.0:
                raise urllib.error.HTTPError("u", 422, "старше недели", {}, None)

        self.assertEqual(self.buf.flush(send), 1)
        self.assertEqual(len(self.buf), 0, "отвергнутое сервером не застревает в буфере")

    def test_keeps_newest_when_full(self):
        for n in range(8):
            self.buf.add(body(n))
        sent = []
        self.buf.flush(sent.append, limit=10)
        self.assertEqual([b["readings"][0]["value"] for b in sent], [3.0, 4.0, 5.0, 6.0, 7.0])

    def test_survives_broken_line(self):
        self.buf.add(body(1))
        with open(self.path, "a", encoding="utf-8") as f:
            f.write('{"body": {"device_id"')  # питание пропало посреди записи
        self.assertEqual(len(self.buf), 1)


class MeasuredAtTests(unittest.TestCase):
    def test_same_boot_uses_monotonic_clock(self):
        # Плата включилась без интернета: системное время неверное (1970), но в ту же загрузку
        # время замера считается от «сейчас» назад по монотонным часам.
        item = {"wall": "1970-01-01T00:05:00+00:00", "mono": 100.0, "boot": "b1"}
        now = datetime(2026, 11, 15, 12, 0, tzinfo=timezone.utc)
        self.assertEqual(buffer.measured_at(item, now, 700.0, "b1"), "2026-11-15T11:50:00+00:00")

    def test_other_boot_uses_wall_clock(self):
        item = {"wall": "2026-11-15T09:00:00+00:00", "mono": 100.0, "boot": "b1"}
        now = datetime(2026, 11, 15, 12, 0, tzinfo=timezone.utc)
        self.assertEqual(buffer.measured_at(item, now, 50.0, "b2"), "2026-11-15T09:00:00+00:00")


if __name__ == "__main__":
    unittest.main(verbosity=2)
