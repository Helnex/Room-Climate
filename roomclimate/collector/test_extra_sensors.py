# -*- coding: utf-8 -*-
"""Проверки доп. датчиков без железа: python test_extra_sensors.py"""
from __future__ import annotations

import unittest

import collector
import extra_sensors as ex


def mhz19_packet(ppm: int) -> bytes:
    body = bytes([0xFF, 0x86, ppm // 256, ppm % 256, 0x40, 0x00, 0x00, 0x00])
    return body + bytes([ex.mhz19_checksum(body + b"\x00")])


class FakeSerial:
    def __init__(self, answer: bytes):
        self.answer = answer
        self.written = b""

    def reset_input_buffer(self):
        pass

    def write(self, data):
        self.written += data

    def read(self, n):
        return self.answer[:n]

    def close(self):
        pass


class FakePin:
    def __init__(self):
        self.value = False

    def deinit(self):
        pass


class Co2Tests(unittest.TestCase):
    def test_reads_ppm(self):
        port = FakeSerial(mhz19_packet(812))
        self.assertEqual(ex.Co2Sensor(serial_port=port).read(), {"type": "co2", "value": 812, "unit": "ppm"})
        self.assertEqual(port.written, ex.MHZ19_READ)

    def test_request_checksum(self):
        self.assertEqual(ex.mhz19_checksum(ex.MHZ19_READ), ex.MHZ19_READ[8])

    def test_bad_checksum(self):
        packet = bytearray(mhz19_packet(700))
        packet[8] ^= 0xFF
        with self.assertRaises(ex.ExtraSensorError):
            ex.mhz19_ppm(bytes(packet))

    def test_no_answer_and_warmup(self):
        with self.assertRaises(ex.ExtraSensorError):
            ex.mhz19_ppm(b"")
        with self.assertRaises(ex.ExtraSensorError):
            ex.mhz19_ppm(mhz19_packet(100))


class LightTests(unittest.TestCase):
    def test_lux(self):
        self.assertEqual(ex.bh1750_lux(bytes([0x01, 0xE0])), 400.0)  # 480 / 1.2
        with self.assertRaises(ex.ExtraSensorError):
            ex.bh1750_lux(b"\x01")


class MotionTests(unittest.TestCase):
    def test_hold_after_trigger(self):
        now = [1000.0]
        pin = FakePin()
        sensor = ex.MotionSensor(pin=pin, clock=lambda: now[0])
        self.assertEqual(sensor.read()["value"], 0)
        pin.value = True
        sensor.poll()          # короткий импульс между отправками
        pin.value = False
        now[0] += 30
        self.assertEqual(sensor.read()["value"], 1)
        now[0] += ex.MOTION_HOLD_SEC
        self.assertEqual(sensor.read()["value"], 0)


class ConfigTests(unittest.TestCase):
    def test_parse_kinds(self):
        self.assertEqual(ex.parse_kinds(""), [])
        self.assertEqual(ex.parse_kinds(" CO2, light,co2 "), ["co2", "light"])
        with self.assertRaises(SystemExit):
            ex.parse_kinds("radar")

    def test_auto_falls_back_to_fake_on_pc(self):
        sensors = ex.make_extras(["co2", "light", "motion"], "auto")
        self.assertTrue(all(isinstance(s, ex.FakeExtra) for s in sensors))
        for r in ex.read_all(sensors):
            self.assertIn(r["type"], ex.KINDS)

    def test_broken_sensor_is_skipped(self):
        class Broken:
            kind = "co2"

            def read(self):
                raise ex.ExtraSensorError("нет ответа")

        good = ex.FakeExtra("light")
        self.assertEqual([r["type"] for r in ex.read_all([Broken(), good])], ["light"])

    def test_body_with_extras_and_without_dht(self):
        extra = [ex.reading("co2", 640)]
        body = collector.build_body(22.0, 40.0, "pi-1", extra)
        self.assertEqual([r["type"] for r in body["readings"]], ["temperature", "humidity", "co2"])
        body = collector.build_body(None, None, "pi-1", extra)
        self.assertEqual(body["readings"], [{"type": "co2", "value": 640, "unit": "ppm"}])


if __name__ == "__main__":
    unittest.main(verbosity=2)
