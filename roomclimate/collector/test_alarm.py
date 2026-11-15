# -*- coding: utf-8 -*-
"""Проверки сигнала на плате (светодиод/пищалка) без GPIO: python test_alarm.py"""
from __future__ import annotations

import unittest

import alarm


class FakeOut(alarm.Output):
    def __init__(self, name="D27"):
        super().__init__(name, pin=object())
        self.history = []

    def set(self, on):
        self.history.append(on)


def temp(v):
    return [{"type": "temperature", "value": v, "unit": "C"}, {"type": "humidity", "value": 45.0, "unit": "%"}]


THRESHOLDS = {"temperature": {"min": 20.0, "max": 24.0}, "humidity": {"min": 30.0, "max": 60.0},
              "co2": {"max": 1000.0}}


class ProblemsTests(unittest.TestCase):
    def test_limits(self):
        self.assertEqual(alarm.problems(temp(22.0), THRESHOLDS), [])
        self.assertEqual(alarm.problems(temp(25.0), THRESHOLDS), ["temperature>24.0"])
        self.assertEqual(alarm.problems(temp(19.5), THRESHOLDS), ["temperature<20.0"])
        self.assertEqual(alarm.problems([{"type": "co2", "value": 1400}], THRESHOLDS), ["co2>1000.0"])
        self.assertEqual(alarm.problems([{"type": "light", "value": 5}], THRESHOLDS), [], "без порога — не тревога")


class AlarmTests(unittest.TestCase):
    def make(self, fetch):
        self.led, self.buzzer = FakeOut("D27"), FakeOut("D22")
        now = [0.0]
        a = alarm.Alarm("http://srv", "pi-1", led=self.led, buzzer=self.buzzer, fetch=fetch,
                        clock=lambda: now[0], sleep=lambda s: None)
        return a, now

    def test_led_and_single_beep_on_new_alarm(self):
        a, _ = self.make(lambda: THRESHOLDS)
        a.update(temp(22.0))
        self.assertEqual(self.led.history, [False])
        a.update(temp(25.0))
        a.update(temp(25.5))  # тревога продолжается — пищалка молчит
        self.assertEqual(self.led.history, [False, True, True])
        self.assertEqual(self.buzzer.history.count(True), 2, "два коротких писка один раз")
        a.update(temp(23.0))
        self.assertEqual(self.led.history[-1], False)
        self.assertFalse(a.active)

    def test_thresholds_refresh_and_offline(self):
        calls = []

        def fetch():
            calls.append(1)
            if len(calls) > 1:
                raise OSError("нет сети")
            return THRESHOLDS

        a, now = self.make(fetch)
        a.update(temp(22.0))
        now[0] += 10
        a.update(temp(22.0))
        self.assertEqual(len(calls), 1, "пороги не запрашиваются каждый цикл")
        now[0] += alarm.THRESHOLDS_SEC + 1
        a.update(temp(25.0))  # сервер недоступен — работаем с прежними порогами
        self.assertEqual(len(calls), 2)
        self.assertTrue(a.active)

    def test_disabled_without_pins(self):
        a = alarm.Alarm("http://srv", "pi-1", led=alarm.Output(""), buzzer=alarm.Output(""),
                        fetch=lambda: THRESHOLDS)
        self.assertFalse(a.enabled)
        self.assertEqual(a.update(temp(30.0)), [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
