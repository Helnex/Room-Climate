# -*- coding: utf-8 -*-
"""Спецификации UT-20…UT-23 из Docs/testing.md: фильтр неправдоподобных показаний DHT22.

Запуск: python test_lab_specs.py
"""
from __future__ import annotations

import unittest

import collector


class PlausibleReadingSpecs(unittest.TestCase):
    """Модуль «Сборщик показаний»: на сервер уходят только значения в пределах DHT22
    (−40…80 °C, 0…100 %), всё остальное — сбой чтения."""

    def test_ut20_normal_values_accepted(self):
        self.assertTrue(collector.valid(22.4, 45.0))

    def test_ut21_range_edges_accepted(self):
        self.assertTrue(collector.valid(-40.0, 0.0))
        self.assertTrue(collector.valid(80.0, 100.0))

    def test_ut22_out_of_range_rejected(self):
        self.assertFalse(collector.valid(80.1, 45.0))
        self.assertFalse(collector.valid(-40.1, 45.0))
        self.assertFalse(collector.valid(22.4, 100.1))

    def test_ut23_missing_value_rejected(self):
        self.assertFalse(collector.valid(None, 45.0))
        self.assertFalse(collector.valid(22.4, None))


if __name__ == "__main__":
    unittest.main(verbosity=2)
