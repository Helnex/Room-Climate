# -*- coding: utf-8 -*-
"""Генератор данных для плат без железа: python test_fake_boards.py"""
from __future__ import annotations

import unittest
from datetime import datetime, timezone

import fake_boards


class FakeBoardTests(unittest.TestCase):
    def test_readings_plausible(self):
        board = fake_boards.FakeBoard("pi-2", 18.0, ["co2", "light", "motion"])
        for hour in range(24):
            body = board.body(datetime(2026, 11, 18, hour, 0, tzinfo=timezone.utc))
            types = [r["type"] for r in body["readings"]]
            self.assertEqual(types, ["temperature", "humidity", "co2", "light", "motion"])
            values = {r["type"]: r["value"] for r in body["readings"]}
            self.assertTrue(15.0 <= values["temperature"] <= 21.0, values)
            self.assertTrue(15.0 <= values["humidity"] <= 95.0, values)
            self.assertTrue(300 <= values["co2"] <= 1000, values)
            self.assertGreaterEqual(values["light"], 0)
            self.assertIn(values["motion"], (0, 1))
            self.assertEqual(body["device_id"], "pi-2")
            self.assertNotIn("measured_at", body)

    def test_backfill(self):
        now = datetime(2026, 11, 18, 12, 0, tzinfo=timezone.utc)
        times = fake_boards.backfill_times(1, 300, now)
        self.assertEqual(len(times), 12)
        self.assertEqual(times[0].isoformat(), "2026-11-18T11:00:00+00:00")
        self.assertLess(times[-1], now)
        body = fake_boards.FakeBoard("pi-3", 24.0, []).body(times[0], measured_at=True)
        self.assertEqual(body["measured_at"], "2026-11-18T11:00:00+00:00")

    def test_parse_board(self):
        self.assertEqual(fake_boards.parse_board("pi-2", 0), ("pi-2", 22.0))
        self.assertEqual(fake_boards.parse_board("pi-3", 1), ("pi-3", 18.0))
        self.assertEqual(fake_boards.parse_board("pi-3:24.5", 1), ("pi-3", 24.5))


if __name__ == "__main__":
    unittest.main(verbosity=2)
