# -*- coding: utf-8 -*-
"""Сборщик RoomClimate: читает DHT22 на Raspberry Pi и отправляет измерения на сервер.

Работает сам по себе (служба systemd), поэтому данные собираются и тогда, когда
мобильное приложение закрыто: телефон только читает то, что уже лежит на сервере.

Режим датчика задаётся переменной SENSOR:
  auto  — DHT22, если есть библиотека и GPIO, иначе тестовые значения (по умолчанию);
  dht22 — только настоящий датчик (без библиотеки сборщик не запустится);
  fake  — тестовые значения, для проверки с ПК без платы.

Дополнительные датчики (CO₂, свет, движение) — EXTRA_SENSORS, см. extra_sensors.py.
"""
from __future__ import annotations

import json
import os
import random
import time
import urllib.request

import extra_sensors

SERVER_URL = os.environ.get("SERVER_URL", "http://127.0.0.1:8000").rstrip("/")
DEVICE_ID = os.environ.get("DEVICE_ID", "pi-1")
# DHT22 нельзя опрашивать чаще раза в 2 секунды.
PERIOD_SEC = max(2.0, float(os.environ.get("PERIOD_SEC", "10")))
# Должен совпадать с INGEST_TOKEN на сервере (если он там задан).
INGEST_TOKEN = os.environ.get("INGEST_TOKEN", "")
SENSOR = os.environ.get("SENSOR", "auto").strip().lower()
# Вывод платы, к которому подключён DATA датчика (нумерация BCM): D4 = GPIO4, физический пин 7.
DHT_PIN = os.environ.get("DHT_PIN", "D4").strip().upper()

# Пределы DHT22 по документации. Всё, что за ними, — сбой чтения, а не погода.
TEMP_RANGE = (-40.0, 80.0)
HUMIDITY_RANGE = (0.0, 100.0)
READ_ATTEMPTS = 5
RETRY_DELAY_SEC = 2.0


class SensorError(Exception):
    """Датчик не дал правдоподобных показаний."""


def valid(temperature: float | None, humidity: float | None) -> bool:
    if temperature is None or humidity is None:
        return False
    return (TEMP_RANGE[0] <= temperature <= TEMP_RANGE[1]
            and HUMIDITY_RANGE[0] <= humidity <= HUMIDITY_RANGE[1])


class FakeSensor:
    """Тестовые значения около 23 °C и 42 % — для ПК без платы."""

    name = "тестовые значения"

    def read(self) -> tuple[float, float]:
        return round(23.0 + random.uniform(-0.5, 0.5), 1), round(42.0 + random.uniform(-2, 2), 1)

    def close(self) -> None:
        pass


class Dht22Sensor:
    """DHT22 через adafruit-circuitpython-dht.

    Датчик капризный: из-за жёстких таймингов однопроводного протокола часть чтений
    заканчивается RuntimeError («checksum did not validate» и т. п.). Это нормально —
    повторяем через 2 секунды, а не отправляем мусор.
    """

    def __init__(self, pin_name: str = DHT_PIN, device=None, sleep=time.sleep):
        self.pin_name = pin_name
        self._sleep = sleep
        self._device = device if device is not None else self._open(pin_name)
        self.name = f"DHT22 на {pin_name}"

    @staticmethod
    def _open(pin_name: str):
        import adafruit_dht  # noqa: PLC0415 — есть только на плате
        import board  # noqa: PLC0415

        pin = getattr(board, pin_name, None)
        if pin is None:
            raise SensorError(f"нет вывода {pin_name} (DHT_PIN), пример: D4")
        # use_pulseio=False: на Raspberry Pi стабильнее чтение через libgpiod.
        return adafruit_dht.DHT22(pin, use_pulseio=False)

    def read(self) -> tuple[float, float]:
        last = "нет данных"
        for attempt in range(READ_ATTEMPTS):
            try:
                temperature = self._device.temperature
                humidity = self._device.humidity
            except (RuntimeError, OSError) as exc:  # обычный сбой чтения DHT — повторить
                last = str(exc)
            else:
                if valid(temperature, humidity):
                    return round(float(temperature), 1), round(float(humidity), 1)
                last = f"неправдоподобно: {temperature} °C, {humidity} %"
            if attempt + 1 < READ_ATTEMPTS:
                self._sleep(RETRY_DELAY_SEC)
        raise SensorError(f"DHT22 не прочитан за {READ_ATTEMPTS} попыток: {last}")

    def close(self) -> None:
        try:
            self._device.exit()
        except Exception:  # noqa: BLE001
            pass


def make_sensor(mode: str = SENSOR):
    if mode == "fake":
        return FakeSensor()
    if mode == "dht22":
        return Dht22Sensor()
    if mode != "auto":
        raise SystemExit(f"SENSOR={mode}: ожидается auto, dht22 или fake")
    try:
        return Dht22Sensor()
    except ImportError:
        print("библиотека DHT22 не найдена — отправляю тестовые значения (SENSOR=fake)", flush=True)
        return FakeSensor()
    except Exception as exc:  # noqa: BLE001 — нет GPIO (ПК), занят вывод и т. п.
        print(f"DHT22 недоступен ({exc}) — отправляю тестовые значения", flush=True)
        return FakeSensor()


def build_body(temperature: float | None, humidity: float | None, device_id: str = DEVICE_ID,
               extra: list[dict] | None = None) -> dict:
    readings = []
    if temperature is not None and humidity is not None:
        readings += [
            {"type": "temperature", "value": temperature, "unit": "C"},
            {"type": "humidity", "value": humidity, "unit": "%"},
        ]
    readings += extra or []
    return {"device_id": device_id, "readings": readings}


def send(temperature: float | None, humidity: float | None, server_url: str = SERVER_URL,
         token: str = INGEST_TOKEN, device_id: str = DEVICE_ID, extra: list[dict] | None = None) -> str:
    data = json.dumps(build_body(temperature, humidity, device_id, extra)).encode("utf-8")
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["X-Device-Token"] = token
    req = urllib.request.Request(server_url + "/telemetry", data=data, method="POST", headers=headers)
    with urllib.request.urlopen(req, timeout=10) as resp:
        return resp.read().decode("utf-8")


def next_delay(failures: int, period: float = PERIOD_SEC) -> float:
    """При серии ошибок увеличиваем паузу (до 60 с), чтобы не заваливать сеть."""
    return period if failures == 0 else min(60.0, period * min(failures, 6))


# После стольких неудачных чтений подряд датчик открывается заново
# (помогает, если отошёл провод или libgpiod потерял линию).
REOPEN_AFTER = 5


def wait(seconds: float, extras: list) -> None:
    """Пауза между отправками; датчик движения тем временем опрашивается каждые 0,5 с."""
    deadline = time.monotonic() + seconds
    while True:
        for sensor in extras:
            sensor.poll()
        left = deadline - time.monotonic()
        if left <= 0:
            return
        time.sleep(min(0.5, left))


def describe(t: float | None, h: float | None, extra: list[dict]) -> str:
    parts = [f"{t} °C, {h} %"] if t is not None else []
    parts += [f"{r['type']}={r['value']} {r['unit']}" for r in extra]
    return ", ".join(parts)


def main() -> None:
    sensor = make_sensor()
    extras = extra_sensors.make_extras(extra_sensors.parse_kinds(extra_sensors.EXTRA_SENSORS), SENSOR)
    names = ", ".join([sensor.name] + [e.name for e in extras])
    print(f"collector -> {SERVER_URL} device={DEVICE_ID} period={PERIOD_SEC}s датчики: {names}", flush=True)
    failures = 0
    sensor_failures = 0
    try:
        while True:
            t = h = None
            try:
                t, h = sensor.read()
                sensor_failures = 0
            except SensorError as exc:
                # Сбой датчика — не повод молчать: пишем в журнал и пробуем в следующем цикле.
                sensor_failures += 1
                print(f"ошибка датчика ({sensor_failures} подряд): {exc}", flush=True)
                if sensor_failures % REOPEN_AFTER == 0 and isinstance(sensor, Dht22Sensor):
                    sensor.close()
                    try:
                        sensor = Dht22Sensor(sensor.pin_name)
                        print("датчик открыт заново", flush=True)
                    except Exception as reopen_exc:  # noqa: BLE001
                        print(f"не удалось открыть датчик заново: {reopen_exc}", flush=True)
            # Доп. датчики отправляются, даже если DHT22 в этот раз не прочитался.
            extra = extra_sensors.read_all(extras)
            if t is not None or extra:
                try:
                    print(f"{describe(t, h, extra)} -> {send(t, h, extra=extra)}", flush=True)
                    failures = 0
                except Exception as exc:  # noqa: BLE001 — сбор не должен останавливаться
                    # Сервер перезапускается, пропал Wi-Fi — повторяем с растущей паузой.
                    failures += 1
                    print(f"ошибка отправки ({failures} подряд): {exc!r}", flush=True)
            wait(next_delay(failures), extras)
    finally:
        sensor.close()
        for extra_sensor in extras:
            extra_sensor.close()


if __name__ == "__main__":
    main()
