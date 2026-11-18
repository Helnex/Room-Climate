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
import re
import time
import urllib.error
import urllib.request

import board_status
import extra_sensors
from alarm import Alarm
from buffer import Buffer

SERVER_URL = os.environ.get("SERVER_URL", "http://127.0.0.1:8000").rstrip("/")
DEVICE_ID = os.environ.get("DEVICE_ID", "pi-1")
# DHT22 нельзя опрашивать чаще раза в 2 секунды.
PERIOD_SEC = max(2.0, float(os.environ.get("PERIOD_SEC", "10")))
# Должен совпадать с INGEST_TOKEN на сервере (если он там задан).
INGEST_TOKEN = os.environ.get("INGEST_TOKEN", "")
SENSOR = os.environ.get("SENSOR", "auto").strip().lower()
# Вывод платы, к которому подключён DATA датчика (нумерация BCM): D4 = GPIO4, физический пин 7.
DHT_PIN = os.environ.get("DHT_PIN", "D4").strip().upper()

# Поправка датчика: если DHT22 врёт относительно градусника/гигрометра, например
# показывает на 0,8 °C больше — TEMP_OFFSET=-0.8. Применяется до отправки на сервер.
TEMP_OFFSET = float(os.environ.get("TEMP_OFFSET", "0"))
HUMIDITY_OFFSET = float(os.environ.get("HUMIDITY_OFFSET", "0"))

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


def calibrate(temperature: float, humidity: float,
              temp_offset: float = TEMP_OFFSET, humidity_offset: float = HUMIDITY_OFFSET) -> tuple[float, float]:
    """Применить поправку; влажность остаётся в пределах 0…100 %."""
    t = round(temperature + temp_offset, 1)
    h = round(min(100.0, max(0.0, humidity + humidity_offset)), 1)
    return t, h


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
    return post(build_body(temperature, humidity, device_id, extra), server_url, token)


def post(body: dict, server_url: str = SERVER_URL, token: str = INGEST_TOKEN) -> str:
    """POST /telemetry; ошибка связи или HTTP — исключение (HTTPError несёт .code)."""
    data = json.dumps(body).encode("utf-8")
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["X-Device-Token"] = token
    req = urllib.request.Request(server_url + "/telemetry", data=data, method="POST", headers=headers)
    with urllib.request.urlopen(req, timeout=10) as resp:
        return resp.read().decode("utf-8")


# Сервер принимает только показатели из своего справочника (metric_types, заводится миграцией)
# и на незнакомый отвечает 422 «Неизвестный показатель: x, y. …» — на весь запрос.
UNKNOWN_RE = re.compile(r"Неизвестный показатель:\s*([A-Za-z0-9_.\-, ]+)")
# Через сколько секунд снова пробовать отклонённый показатель (вдруг миграцию уже сделали).
RECHECK_UNKNOWN_SEC = float(os.environ.get("RECHECK_UNKNOWN_SEC", "600"))


def unknown_types(exc: Exception) -> set[str]:
    """Какие показатели сервер не знает — из ответа 422 (пусто, если 422 по другой причине)."""
    if getattr(exc, "code", None) != 422:
        return set()
    try:
        detail = json.loads(exc.read() or b"{}").get("detail", "")
    except Exception:  # noqa: BLE001
        return set()
    match = UNKNOWN_RE.search(str(detail))
    # Точка после списка («…: co2x. Добавьте…») — конец фразы, а не часть имени.
    return {t.strip().rstrip(".") for t in match.group(1).split(",") if t.strip().rstrip(".")} if match else set()


class Sender:
    """Отправка с учётом справочника сервера: незнакомый серверу показатель (новый датчик,
    который ещё не завели миграцией) не мешает остальным — он убирается из запроса,
    остальное уходит сразу, а незнакомый пробуется снова раз в RECHECK_UNKNOWN_SEC.
    Подходит и для досылки из буфера: Buffer.flush(sender)."""

    def __init__(self, post_fn=None, clock=time.monotonic, recheck: float = RECHECK_UNKNOWN_SEC, log=print):
        self.post_fn = post_fn or post
        self.clock = clock
        self.recheck = recheck
        self.log = log
        self.unknown: dict[str, float] = {}  # тип → когда пробовать снова

    def strip(self, body: dict) -> dict | None:
        now = self.clock()
        for kind in [k for k, until in self.unknown.items() if until <= now]:
            del self.unknown[kind]
        readings = [r for r in body["readings"] if r["type"] not in self.unknown]
        return {**body, "readings": readings} if readings else None

    def __call__(self, body: dict) -> str:
        body = self.strip(body)
        if body is None:
            return "пропущено: сервер пока не знает эти показатели"
        try:
            return self.post_fn(body)
        except urllib.error.HTTPError as exc:
            sent = {r["type"] for r in body["readings"]}
            unknown = unknown_types(exc) & sent
            if not unknown:
                raise
            for kind in unknown:
                self.unknown[kind] = self.clock() + self.recheck
            self.log(f"сервер не знает показатель {', '.join(sorted(unknown))} — датчик есть на плате, "
                     f"но его нет в базе сервера. Добавьте его миграцией (server/app/migrations). "
                     f"Остальные показания отправляю, этот попробую снова через {int(self.recheck)} с", flush=True)
            body = self.strip(body)
            return self.post_fn(body) if body is not None else "пропущено: сервер не знает эти показатели"


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


def rejected(exc: Exception) -> bool:
    """Сервер отверг само показание (4xx, кроме токена/перегрузки) — в буфер его класть незачем."""
    code = getattr(exc, "code", None)
    return isinstance(code, int) and 400 <= code < 500 and code not in (401, 408, 429)


def describe(t: float | None, h: float | None, extra: list[dict]) -> str:
    parts = [f"{t} °C, {h} %"] if t is not None else []
    parts += [f"{r['type']}={r['value']} {r['unit']}" for r in extra]
    return ", ".join(parts)


def send_status(sensor, extras: list, buffered: int, alarm_on: bool) -> None:
    """«Пульс» платы для приложения; без связи просто пропускается (не в буфер)."""
    names = ["dht22" if isinstance(sensor, Dht22Sensor) else "test"]
    names += [e.kind if not isinstance(e, extra_sensors.FakeExtra) else f"{e.kind}-test" for e in extras]
    try:
        data = board_status.collect(SERVER_URL, buffered, names, alarm_on)
        board_status.send(SERVER_URL, DEVICE_ID, INGEST_TOKEN, data)
    except Exception as exc:  # noqa: BLE001
        print(f"состояние платы не отправлено: {exc!r}", flush=True)


def main() -> None:
    sensor = make_sensor()
    kinds = extra_sensors.parse_kinds(extra_sensors.EXTRA_SENSORS)
    extras = extra_sensors.make_extras(kinds, SENSOR)
    # EXTRA_SENSORS=auto: подключённые CO₂ и датчик света находятся сами, и на ходу тоже.
    detector = extra_sensors.Detector(extra_sensors.AUTO in kinds and SENSOR != "fake")
    extras += detector.refresh(extras, force=True)
    names = ", ".join([sensor.name] + [e.name for e in extras])
    print(f"collector -> {SERVER_URL} device={DEVICE_ID} period={PERIOD_SEC}s датчики: {names}"
          + (" (+ поиск новых датчиков)" if detector.enabled else ""), flush=True)
    sender = Sender()
    failures = 0
    sensor_failures = 0
    next_try = 0.0  # когда снова пробовать связь после ошибки (пока ждём — копим в буфер)
    buf = Buffer()
    alarm = Alarm(SERVER_URL, DEVICE_ID, INGEST_TOKEN)
    last_status = None  # monotonic-время последней отправки состояния платы
    if alarm.enabled:
        print(f"сигнал на плате: светодиод {alarm.led.name or '—'}, пищалка {alarm.buzzer.name or '—'}", flush=True)
    if len(buf):
        print(f"в буфере {len(buf)} неотправленных показаний — дошлю, когда будет связь", flush=True)
    try:
        while True:
            t = h = None
            try:
                t, h = calibrate(*sensor.read())
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
            extras = detector.track(extras, extra)
            new = detector.refresh(extras)
            if new:
                extras += new
                last_status = None  # сразу сообщить серверу новый список датчиков
            if t is not None or extra:
                body = build_body(t, h, DEVICE_ID, extra)
                alarm.update(body["readings"])  # сигнал работает и без связи — по последним порогам
                if time.monotonic() < next_try:
                    buf.add(body)  # связи не было — не ждём таймаут, сразу в буфер
                else:
                    try:
                        print(f"{describe(t, h, extra)} -> {sender(body)}", flush=True)
                        failures = 0
                        sent = buf.flush(sender)
                        if sent:
                            print(f"дослано из буфера: {sent}, осталось {len(buf)}", flush=True)
                    except Exception as exc:  # noqa: BLE001 — сбор не должен останавливаться
                        if rejected(exc):
                            print(f"сервер отклонил показание: {exc!r}", flush=True)
                        else:
                            # Сервер перезапускается, пропал Wi-Fi — копим и пробуем реже.
                            failures += 1
                            next_try = time.monotonic() + next_delay(failures)
                            buf.add(body)
                            print(f"нет связи ({failures} подряд): {exc!r} — в буфере {len(buf)}", flush=True)
            if last_status is None or time.monotonic() - last_status >= board_status.STATUS_SEC:
                last_status = time.monotonic()
                send_status(sensor, extras, len(buf), alarm.active)
            wait(PERIOD_SEC, extras)
    finally:
        sensor.close()
        alarm.close()
        for extra_sensor in extras:
            extra_sensor.close()


if __name__ == "__main__":
    main()
