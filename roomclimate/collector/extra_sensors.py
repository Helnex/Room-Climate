# -*- coding: utf-8 -*-
"""Дополнительные датчики сборщика: углекислый газ, освещённость, движение.

Включаются переменной EXTRA_SENSORS (через запятую): co2, light, motion.
Каждый отдаёт одно показание {"type", "value", "unit"} в том же POST /telemetry, что и DHT22.

  co2    — MH-Z19B по UART (/dev/serial0, 9600 бод), ppm.  Нужен pyserial и включённый UART.
  light  — BH1750 по I2C (шина 1, адрес 0x23), лк.          Нужен smbus2 и включённый I2C.
  motion — PIR HC-SR501 на GPIO (MOTION_PIN, по умолчанию D17), 1 — есть движение, 0 — нет.

В режиме SENSOR=fake (или auto без библиотек/железа) — правдоподобные тестовые значения.
"""
from __future__ import annotations

import os
import random
import time

EXTRA_SENSORS = os.environ.get("EXTRA_SENSORS", "")
CO2_PORT = os.environ.get("CO2_PORT", "/dev/serial0")
LIGHT_BUS = int(os.environ.get("LIGHT_BUS", "1"))
LIGHT_ADDR = int(os.environ.get("LIGHT_ADDR", "0x23"), 16)
MOTION_PIN = os.environ.get("MOTION_PIN", "D17").strip().upper()
# Движение «держится» столько секунд после срабатывания: PIR даёт короткий импульс,
# а сборщик опрашивает раз в PERIOD_SEC и иначе пропустил бы его.
MOTION_HOLD_SEC = float(os.environ.get("MOTION_HOLD_SEC", "60"))

KINDS = ("co2", "light", "motion")


class ExtraSensorError(Exception):
    """Датчик не ответил или ответ не прошёл проверку."""


def parse_kinds(raw: str) -> list[str]:
    kinds = []
    for item in raw.replace(";", ",").split(","):
        kind = item.strip().lower()
        if not kind:
            continue
        if kind not in KINDS:
            raise SystemExit(f"EXTRA_SENSORS: неизвестный датчик «{kind}», можно: {', '.join(KINDS)}")
        if kind not in kinds:
            kinds.append(kind)
    return kinds


def reading(kind: str, value: float) -> dict:
    unit = {"co2": "ppm", "light": "lx", "motion": "bool"}[kind]
    return {"type": kind, "value": value, "unit": unit}


# ---------- CO2: MH-Z19B ----------

MHZ19_READ = bytes([0xFF, 0x01, 0x86, 0x00, 0x00, 0x00, 0x00, 0x00, 0x79])


def mhz19_checksum(packet: bytes) -> int:
    return (0xFF - (sum(packet[1:8]) & 0xFF) + 1) & 0xFF


def mhz19_ppm(packet: bytes) -> int:
    """Ответ MH-Z19 на команду 0x86 → ppm. Бросает ExtraSensorError при битом пакете."""
    if len(packet) != 9 or packet[0] != 0xFF or packet[1] != 0x86:
        raise ExtraSensorError(f"MH-Z19: неожиданный ответ {packet.hex()}")
    if mhz19_checksum(packet) != packet[8]:
        raise ExtraSensorError("MH-Z19: не сошлась контрольная сумма")
    ppm = packet[2] * 256 + packet[3]
    if not 300 <= ppm <= 10000:
        raise ExtraSensorError(f"MH-Z19: неправдоподобно {ppm} ppm (датчик ещё греется?)")
    return ppm


class Co2Sensor:
    kind = "co2"
    name = "CO₂ MH-Z19"

    def __init__(self, port: str = CO2_PORT, serial_port=None):
        if serial_port is None:
            import serial  # noqa: PLC0415 — pyserial есть только на плате

            serial_port = serial.Serial(port, 9600, timeout=2)
        self._port = serial_port

    def read(self) -> dict:
        self._port.reset_input_buffer()
        self._port.write(MHZ19_READ)
        return reading("co2", mhz19_ppm(self._port.read(9)))

    def close(self) -> None:
        try:
            self._port.close()
        except Exception:  # noqa: BLE001
            pass


# ---------- Освещённость: BH1750 ----------

BH1750_ONE_TIME_HIGH_RES = 0x20


def bh1750_lux(raw: bytes) -> float:
    if len(raw) != 2:
        raise ExtraSensorError("BH1750: нет ответа")
    return round((raw[0] << 8 | raw[1]) / 1.2, 1)


class LightSensor:
    kind = "light"
    name = "свет BH1750"

    def __init__(self, bus_number: int = LIGHT_BUS, address: int = LIGHT_ADDR, bus=None, sleep=time.sleep):
        if bus is None:
            from smbus2 import SMBus  # noqa: PLC0415 — есть только на плате

            bus = SMBus(bus_number)
        self._bus = bus
        self._address = address
        self._sleep = sleep

    def read(self) -> dict:
        from smbus2 import i2c_msg  # noqa: PLC0415

        self._bus.write_byte(self._address, BH1750_ONE_TIME_HIGH_RES)
        self._sleep(0.18)  # измерение в режиме высокой точности — до 180 мс
        msg = i2c_msg.read(self._address, 2)
        self._bus.i2c_rdwr(msg)
        return reading("light", bh1750_lux(bytes(msg)))

    def close(self) -> None:
        try:
            self._bus.close()
        except Exception:  # noqa: BLE001
            pass


# ---------- Движение: PIR HC-SR501 ----------

class MotionSensor:
    """PIR: высокий уровень на выходе — движение. Помним последнее срабатывание MOTION_HOLD_SEC."""

    kind = "motion"
    name = "движение PIR"

    def __init__(self, pin_name: str = MOTION_PIN, pin=None, clock=time.monotonic):
        if pin is None:
            import board  # noqa: PLC0415
            import digitalio  # noqa: PLC0415

            board_pin = getattr(board, pin_name, None)
            if board_pin is None:
                raise ExtraSensorError(f"нет вывода {pin_name} (MOTION_PIN), пример: D17")
            pin = digitalio.DigitalInOut(board_pin)
            pin.direction = digitalio.Direction.INPUT
        self._pin = pin
        self._clock = clock
        self._last_seen: float | None = None

    def poll(self) -> None:
        """Опросить вывод; вызывается часто (между отправками), чтобы не пропустить импульс."""
        if self._pin.value:
            self._last_seen = self._clock()

    def read(self) -> dict:
        self.poll()
        moving = self._last_seen is not None and self._clock() - self._last_seen <= MOTION_HOLD_SEC
        return reading("motion", 1 if moving else 0)

    def close(self) -> None:
        try:
            self._pin.deinit()
        except Exception:  # noqa: BLE001
            pass


# ---------- Тестовые значения ----------

class FakeExtra:
    def __init__(self, kind: str):
        self.kind = kind
        self.name = f"{kind} (тестовые значения)"

    def read(self) -> dict:
        if self.kind == "co2":
            return reading("co2", random.randint(550, 900))
        if self.kind == "light":
            return reading("light", round(random.uniform(250, 450), 1))
        return reading("motion", 1 if random.random() < 0.3 else 0)

    def poll(self) -> None:
        pass

    def close(self) -> None:
        pass


REAL = {"co2": Co2Sensor, "light": LightSensor, "motion": MotionSensor}


def make_extras(kinds: list[str], mode: str) -> list:
    """mode — как SENSOR у DHT22: fake, auto (настоящий, иначе тестовый) или dht22 (только настоящий)."""
    sensors = []
    for kind in kinds:
        if mode == "fake":
            sensors.append(FakeExtra(kind))
            continue
        try:
            sensors.append(REAL[kind]())
        except Exception as exc:  # noqa: BLE001 — нет библиотеки, UART/I2C выключены, ПК
            if mode != "auto":
                raise SystemExit(f"датчик {kind} недоступен: {exc}") from exc
            print(f"датчик {kind} недоступен ({exc}) — отправляю тестовые значения", flush=True)
            sensors.append(FakeExtra(kind))
    return sensors


def read_all(sensors: list) -> list[dict]:
    """Показания всех доп. датчиков; сломанный датчик пропускается и пишется в журнал."""
    readings = []
    for sensor in sensors:
        try:
            readings.append(sensor.read())
        except Exception as exc:  # noqa: BLE001 — один датчик не должен останавливать остальные
            print(f"ошибка датчика {sensor.kind}: {exc}", flush=True)
    return readings
