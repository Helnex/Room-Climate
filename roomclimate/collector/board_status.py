# -*- coding: utf-8 -*-
"""Состояние самой платы для приложения: «жива ли Pi и что с ней».

Раз в STATUS_SEC (по умолчанию 60 с) сборщик отправляет POST /devices/{id}/status:
температура процессора, сеть Wi-Fi и сигнал, IP, время работы, версия сборщика (git),
сколько показаний ждёт в буфере, какие датчики подключены, горит ли сигнал.
Всё, чего нет (например, на ПК), просто не отправляется.
"""
from __future__ import annotations

import json
import os
import socket
import subprocess
import urllib.request

STATUS_SEC = float(os.environ.get("STATUS_SEC", "60"))
HERE = os.path.dirname(os.path.abspath(__file__))


def cpu_temp(path: str = "/sys/class/thermal/thermal_zone0/temp") -> float | None:
    try:
        with open(path, encoding="ascii") as f:
            return round(int(f.read().strip()) / 1000.0, 1)
    except (OSError, ValueError):
        return None


def wifi_signal(text: str | None = None, iface: str = "wlan0") -> float | None:
    """Уровень сигнала (дБм) из /proc/net/wireless."""
    if text is None:
        try:
            with open("/proc/net/wireless", encoding="ascii") as f:
                text = f.read()
        except OSError:
            return None
    for line in text.splitlines():
        if line.strip().startswith(iface + ":"):
            parts = line.split()
            try:
                return float(parts[3].rstrip("."))
            except (IndexError, ValueError):
                return None
    return None


def wifi_ssid() -> str | None:
    try:
        out = subprocess.run(["iwgetid", "-r"], capture_output=True, text=True, timeout=3)
        return out.stdout.strip() or None
    except (OSError, subprocess.SubprocessError):
        return None


def uptime(path: str = "/proc/uptime") -> int | None:
    try:
        with open(path, encoding="ascii") as f:
            return int(float(f.read().split()[0]))
    except (OSError, ValueError, IndexError):
        return None


def local_ip(server_url: str) -> str | None:
    """IP платы в сторону сервера (без отправки пакетов: UDP connect только выбирает маршрут)."""
    host = server_url.split("://", 1)[-1].split("/", 1)[0].rsplit(":", 1)[0] or "8.8.8.8"
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect((host, 80))
            return s.getsockname()[0]
    except OSError:
        return None


def version() -> str | None:
    """Короткий хеш коммита, из которого запущен сборщик (обновляется update.sh)."""
    try:
        out = subprocess.run(["git", "-C", HERE, "rev-parse", "--short", "HEAD"],
                             capture_output=True, text=True, timeout=3)
        return out.stdout.strip() or None
    except (OSError, subprocess.SubprocessError):
        return None


def collect(server_url: str, buffered: int, sensors: list[str], alarm: bool) -> dict:
    data = {
        "cpu_temp": cpu_temp(),
        "wifi_ssid": wifi_ssid(),
        "wifi_signal": wifi_signal(),
        "ip": local_ip(server_url),
        "uptime_sec": uptime(),
        "version": version(),
        "buffered": buffered,
        "sensors": sensors,
        "alarm": alarm,
    }
    return {k: v for k, v in data.items() if v is not None}


def send(server_url: str, device_id: str, token: str, data: dict) -> None:
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["X-Device-Token"] = token
    req = urllib.request.Request(f"{server_url}/devices/{device_id}/status",
                                 data=json.dumps(data).encode("utf-8"), method="POST", headers=headers)
    with urllib.request.urlopen(req, timeout=10) as resp:
        resp.read()
