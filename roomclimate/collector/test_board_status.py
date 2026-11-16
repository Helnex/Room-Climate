# -*- coding: utf-8 -*-
"""Проверки сбора состояния платы без Raspberry Pi: python test_board_status.py"""
from __future__ import annotations

import os
import tempfile
import unittest

import board_status

WIRELESS = """Inter-| sta-|   Quality        |   Discarded packets               | Missed | WE
 face | tus | link level noise |  nwid  crypt   frag  retry   misc | beacon | 22
 wlan0: 0000   56.  -54.  -256        0      0      0      0     12        0
"""


class StatusTests(unittest.TestCase):
    def file(self, text):
        path = os.path.join(tempfile.mkdtemp(), "f")
        with open(path, "w", encoding="ascii") as f:
            f.write(text)
        return path

    def test_cpu_temp(self):
        self.assertEqual(board_status.cpu_temp(self.file("48312\n")), 48.3)
        self.assertIsNone(board_status.cpu_temp("/нет/такого/файла"))

    def test_wifi_signal(self):
        self.assertEqual(board_status.wifi_signal(WIRELESS), -54.0)
        self.assertIsNone(board_status.wifi_signal(WIRELESS, "eth0"))
        self.assertIsNone(board_status.wifi_signal(""))

    def test_uptime(self):
        self.assertEqual(board_status.uptime(self.file("3725.41 7000.00\n")), 3725)

    def test_collect_skips_missing(self):
        data = board_status.collect("http://127.0.0.1:8000", 3, ["dht22"], False)
        self.assertEqual(data["buffered"], 3)
        self.assertEqual(data["sensors"], ["dht22"])
        self.assertNotIn(None, data.values())
        self.assertEqual(data.get("ip"), "127.0.0.1")


if __name__ == "__main__":
    unittest.main(verbosity=2)
