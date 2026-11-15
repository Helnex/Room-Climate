# -*- coding: utf-8 -*-
"""Проверки сборщика без платы: python test_collector.py

Настоящий DHT22 подменяется заглушкой, сервер — маленьким HTTP-сервером на 127.0.0.1.
"""
from __future__ import annotations

import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

import collector


class StubDevice:
    """Ведёт себя как adafruit_dht.DHT22: значения по очереди, исключение — если в списке."""

    def __init__(self, values):
        self.values = list(values)
        self.current = (None, None)
        self.closed = False

    def _next(self):
        item = self.values.pop(0)
        if isinstance(item, Exception):
            raise item
        self.current = item

    @property
    def temperature(self):
        self._next()
        return self.current[0]

    @property
    def humidity(self):
        return self.current[1]

    def exit(self):
        self.closed = True


def sensor_with(values):
    sleeps = []
    sensor = collector.Dht22Sensor("D4", device=StubDevice(values), sleep=sleeps.append)
    return sensor, sleeps


class SensorTests(unittest.TestCase):
    def test_reads_and_rounds(self):
        sensor, sleeps = sensor_with([(22.46, 41.04)])
        self.assertEqual(sensor.read(), (22.5, 41.0))
        self.assertEqual(sleeps, [])

    def test_retries_after_runtime_error(self):
        sensor, sleeps = sensor_with([RuntimeError("Checksum did not validate"), (21.0, 50.0)])
        self.assertEqual(sensor.read(), (21.0, 50.0))
        self.assertEqual(sleeps, [collector.RETRY_DELAY_SEC])

    def test_skips_implausible_values(self):
        sensor, _ = sensor_with([(None, None), (150.0, 40.0), (20.0, 120.0), (20.0, 40.0)])
        self.assertEqual(sensor.read(), (20.0, 40.0))

    def test_gives_up_without_sending_garbage(self):
        sensor, sleeps = sensor_with([RuntimeError("timeout")] * collector.READ_ATTEMPTS)
        with self.assertRaises(collector.SensorError):
            sensor.read()
        self.assertEqual(len(sleeps), collector.READ_ATTEMPTS - 1)

    def test_close(self):
        sensor, _ = sensor_with([])
        sensor.close()
        self.assertTrue(sensor._device.closed)

    def test_valid_limits(self):
        self.assertTrue(collector.valid(-40.0, 0.0))
        self.assertTrue(collector.valid(80.0, 100.0))
        self.assertFalse(collector.valid(-40.1, 50.0))
        self.assertFalse(collector.valid(20.0, None))

    def test_fake_sensor_in_range(self):
        for _ in range(50):
            self.assertTrue(collector.valid(*collector.FakeSensor().read()))

    def test_auto_without_library_falls_back_to_fake(self):
        # На ПК нет adafruit_dht/board — режим auto должен не падать, а слать тестовые значения.
        self.assertIsInstance(collector.make_sensor("auto"), collector.FakeSensor)
        self.assertIsInstance(collector.make_sensor("fake"), collector.FakeSensor)

    def test_bad_mode(self):
        with self.assertRaises(SystemExit):
            collector.make_sensor("bmp280")

    def test_backoff(self):
        self.assertEqual(collector.next_delay(0, 10), 10)
        self.assertEqual(collector.next_delay(2, 10), 20)
        self.assertEqual(collector.next_delay(20, 10), 60)


class Handler(BaseHTTPRequestHandler):
    received: list = []

    def do_POST(self):  # noqa: N802
        body = self.rfile.read(int(self.headers["Content-Length"]))
        Handler.received.append((self.path, self.headers.get("X-Device-Token"), json.loads(body)))
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"ok": true}')

    def log_message(self, *args):
        pass


class SendTests(unittest.TestCase):
    def test_posts_telemetry_with_token(self):
        server = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            url = f"http://127.0.0.1:{server.server_port}"
            answer = collector.send(22.5, 41.0, server_url=url, token="secret", device_id="pi-7")
        finally:
            server.shutdown()
            server.server_close()
        self.assertIn("ok", answer)
        path, token, body = Handler.received[-1]
        self.assertEqual(path, "/telemetry")
        self.assertEqual(token, "secret")
        self.assertEqual(body["device_id"], "pi-7")
        self.assertEqual(body["readings"], [
            {"type": "temperature", "value": 22.5, "unit": "C"},
            {"type": "humidity", "value": 41.0, "unit": "%"},
        ])


if __name__ == "__main__":
    unittest.main(verbosity=2)
