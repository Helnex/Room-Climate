# -*- coding: utf-8 -*-
"""Сигнал на самой плате: светодиод горит, а пищалка коротко пищит, когда показания вне порогов.

Пороги — те же, что в приложении: сборщик раз в THRESHOLDS_SEC берёт их с сервера
(GET /devices/{id}/thresholds); пока сервер не ответил — пороги по умолчанию.
Проверяются температура, влажность и CO₂ (если подключён датчик).

  ALARM_LED_PIN — вывод светодиода (BCM, например D27; через резистор 220–330 Ом на GND);
  BUZZER_PIN    — вывод активной пищалки (например D22).
Не задано ни одного — сигнал выключен. Без GPIO (ПК) состояние только пишется в журнал.
"""
from __future__ import annotations

import json
import os
import time
import urllib.request

ALARM_LED_PIN = os.environ.get("ALARM_LED_PIN", "").strip().upper()
BUZZER_PIN = os.environ.get("BUZZER_PIN", "").strip().upper()
THRESHOLDS_SEC = float(os.environ.get("THRESHOLDS_SEC", "300"))
BEEP_SEC = 0.15

DEFAULT_THRESHOLDS = {
    "temperature": {"min": 18.0, "max": 26.0},
    "humidity": {"min": 30.0, "max": 70.0},
    "co2": {"max": 1000.0},
}


def problems(readings: list[dict], thresholds: dict) -> list[str]:
    """Какие показания вне порогов: ["temperature>26.0", ...]; пусто — всё в норме."""
    out = []
    for r in readings:
        limits = thresholds.get(r.get("type"))
        if not limits:
            continue
        value = r.get("value")
        if value is None:
            continue
        low, high = limits.get("min"), limits.get("max")
        if low is not None and value < low:
            out.append(f"{r['type']}<{low}")
        elif high is not None and value > high:
            out.append(f"{r['type']}>{high}")
    return out


class Output:
    """Вывод GPIO (digitalio) или заглушка, если вывода нет / не на плате."""

    def __init__(self, pin_name: str, pin=None):
        self.name = pin_name
        self._pin = pin
        if pin is None and pin_name:
            try:
                import board  # noqa: PLC0415
                import digitalio  # noqa: PLC0415

                self._pin = digitalio.DigitalInOut(getattr(board, pin_name))
                self._pin.direction = digitalio.Direction.OUTPUT
            except Exception as exc:  # noqa: BLE001 — ПК, нет вывода
                print(f"вывод {pin_name} недоступен ({exc}) — сигнал только в журнале", flush=True)
                self._pin = None

    def set(self, on: bool) -> None:
        if self._pin is not None:
            self._pin.value = on

    def close(self) -> None:
        if self._pin is not None:
            try:
                self._pin.value = False
                self._pin.deinit()
            except Exception:  # noqa: BLE001
                pass


class Alarm:
    def __init__(self, server_url: str, device_id: str, token: str = "",
                 led: Output | None = None, buzzer: Output | None = None,
                 fetch=None, clock=time.monotonic, sleep=time.sleep):
        self.server_url = server_url
        self.device_id = device_id
        self.token = token
        self.led = led if led is not None else Output(ALARM_LED_PIN)
        self.buzzer = buzzer if buzzer is not None else Output(BUZZER_PIN)
        self.thresholds = DEFAULT_THRESHOLDS
        self.active = False
        self._fetch = fetch or self._fetch_thresholds
        self._clock = clock
        self._sleep = sleep
        self._fetched_at: float | None = None

    @property
    def enabled(self) -> bool:
        return bool(self.led.name or self.buzzer.name)

    def _fetch_thresholds(self) -> dict:
        req = urllib.request.Request(f"{self.server_url}/devices/{self.device_id}/thresholds",
                                     headers={"X-Device-Token": self.token} if self.token else {})
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read().decode("utf-8"))

    def refresh(self) -> None:
        now = self._clock()
        if self._fetched_at is not None and now - self._fetched_at < THRESHOLDS_SEC:
            return
        self._fetched_at = now
        try:
            data = self._fetch()
            self.thresholds = {k: data[k] for k in DEFAULT_THRESHOLDS if k in data} or DEFAULT_THRESHOLDS
        except Exception as exc:  # noqa: BLE001 — без связи работаем с прежними порогами
            print(f"пороги не получены ({exc!r}) — остаются прежние", flush=True)

    def update(self, readings: list[dict]) -> list[str]:
        """Проверить показания, зажечь/погасить светодиод; при новой тревоге — пискнуть."""
        if not self.enabled or not readings:
            return []
        self.refresh()
        bad = problems(readings, self.thresholds)
        alarm = bool(bad)
        self.led.set(alarm)
        if alarm and not self.active:
            print(f"сигнал: вне порогов {', '.join(bad)}", flush=True)
            for _ in range(2):
                self.buzzer.set(True)
                self._sleep(BEEP_SEC)
                self.buzzer.set(False)
                self._sleep(BEEP_SEC)
        elif not alarm and self.active:
            print("сигнал: снова в норме", flush=True)
        self.active = alarm
        return bad

    def close(self) -> None:
        self.led.close()
        self.buzzer.close()
