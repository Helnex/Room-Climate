# -*- coding: utf-8 -*-
"""Поиск датчиков на ходу (EXTRA_SENSORS=auto) без железа: python test_autodetect.py"""
from __future__ import annotations

import unittest

import extra_sensors as ex
from test_extra_sensors import FakeSerial, mhz19_packet


class Probe:
    """Датчик-заглушка: отвечает, пока «воткнут»."""

    def __init__(self, kind, plugged):
        self.kind = kind
        self.name = f"{kind}-заглушка"
        self.plugged = plugged
        self.closed = False

    def probe(self):
        return self.plugged[self.kind]

    def read(self):
        if not self.plugged[self.kind]:
            raise ex.ExtraSensorError("нет ответа")
        return ex.reading(self.kind, 400)

    def close(self):
        self.closed = True


class DetectorTests(unittest.TestCase):
    def setUp(self):
        self.now = [0.0]
        self.plugged = {"co2": False, "light": False}
        factories = {k: (lambda k=k: Probe(k, self.plugged)) for k in ("co2", "light")}
        self.logs = []
        self.det = ex.Detector(True, factories, clock=lambda: self.now[0], interval=60, lost_after=3,
                               log=lambda *a, **k: self.logs.append(a[0]))

    def cycle(self, extras):
        readings = ex.read_all(extras)
        extras = self.det.track(extras, readings)
        return extras + self.det.refresh(extras), readings

    def test_plug_in_on_the_fly(self):
        extras = self.det.refresh([], force=True)
        self.assertEqual(extras, [], "ничего не подключено")
        self.plugged["light"] = True
        extras, _ = self.cycle(extras)
        self.assertEqual(extras, [], "ищем не чаще раза в interval")
        self.now[0] += 61
        extras, _ = self.cycle(extras)
        self.assertEqual([s.kind for s in extras], ["light"], "воткнули — нашёлся")
        self.assertIn("найден датчик", self.logs[-1])
        _, readings = self.cycle(extras)
        self.assertEqual([r["type"] for r in readings], ["light"])

    def test_unplug_and_find_again(self):
        self.plugged["co2"] = True
        extras = self.det.refresh([], force=True)
        first = extras[0]
        self.plugged["co2"] = False
        for _ in range(3):
            extras, _ = self.cycle(extras)
        self.assertEqual(extras, [], "3 раза не ответил — убран")
        self.assertTrue(first.closed)
        self.plugged["co2"] = True
        self.now[0] += 61
        extras, _ = self.cycle(extras)
        self.assertEqual([s.kind for s in extras], ["co2"], "воткнули обратно — снова найден")

    def test_explicit_sensors_untouched(self):
        explicit = ex.FakeExtra("light")
        extras = [explicit] + self.det.refresh([explicit], force=True)
        self.assertEqual(extras, [explicit], "light задан явно — второй не ищем")
        broken = Probe("co2", {"co2": False})
        for _ in range(5):
            self.assertIn(broken, self.det.track([broken], []), "явно заданный не выкидываем")

    def test_disabled(self):
        self.plugged["light"] = True
        det = ex.Detector(False, {"light": lambda: Probe("light", self.plugged)})
        self.assertEqual(det.refresh([], force=True), [])

    def test_no_libraries_on_pc(self):
        det = ex.Detector(True, log=lambda *a, **k: None)
        self.assertEqual(det.refresh([], force=True), [], "на ПК нет smbus2/pyserial — тихо ничего не находим")


class ProbeTests(unittest.TestCase):
    def test_mhz19_probe_accepts_warmup(self):
        self.assertTrue(ex.Co2Sensor(serial_port=FakeSerial(mhz19_packet(100))).probe(), "греется, но отвечает")
        self.assertFalse(ex.Co2Sensor(serial_port=FakeSerial(b"")).probe())
        self.assertFalse(ex.Co2Sensor(serial_port=FakeSerial(b"\x00" * 9)).probe())

    def test_parse_auto(self):
        self.assertEqual(ex.parse_kinds("auto, motion"), ["auto", "motion"])
        self.assertEqual(ex.make_extras(["auto"], "fake"), [], "auto сам по себе датчиков не создаёт")


if __name__ == "__main__":
    unittest.main(verbosity=2)
