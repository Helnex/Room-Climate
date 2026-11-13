"""Динамический DNS через Cloudflare: держит запись A PUBLIC_HOST = внешний IP роутера.

Провайдер (PPPoE) может сменить белый IP после переподключения. Скрипт раз в
DDNS_INTERVAL секунд узнаёт внешний IP и, если он изменился, переписывает запись в
Cloudflare. Приложение ходит по имени, поэтому новый адрес подхватит само (TTL 2 мин).
Запись без проксирования Cloudflare (серое облачко): трафик идёт напрямую на компьютер.

Переменные (server/.env): CF_API_TOKEN (право «Zone DNS Edit» только на эту зону),
CF_ZONE_ID, PUBLIC_HOST. Только стандартная библиотека — запускается в python:3.11-slim.

Внешний IP нужно узнавать мимо VPN: если на компьютере VPN в режиме TUN заворачивает весь
трафик, сервисы вернут адрес VPN. Поэтому берём ответ двух сервисов и обновляем запись,
только если они совпали.
"""
from __future__ import annotations

import ipaddress
import json
import os
import sys
import time
import urllib.request

API = "https://api.cloudflare.com/client/v4"
TOKEN = os.environ.get("CF_API_TOKEN", "").strip()
ZONE = os.environ.get("CF_ZONE_ID", "").strip()
HOST = os.environ.get("PUBLIC_HOST", "").strip().lower()
INTERVAL = int(os.environ.get("DDNS_INTERVAL", "120"))
IP_SOURCES = ("https://api.ipify.org", "https://ifconfig.me/ip", "https://ipv4.icanhazip.com")


def log(msg: str) -> None:
    print(time.strftime("%Y-%m-%d %H:%M:%S"), msg, flush=True)


def fetch(url: str, method: str = "GET", body: dict | None = None, auth: bool = False) -> str:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("User-Agent", "roomclimate-ddns")
    if auth:
        req.add_header("Authorization", f"Bearer {TOKEN}")
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=15) as resp:
        return resp.read().decode()


def external_ip() -> str | None:
    seen: list[str] = []
    for url in IP_SOURCES:
        try:
            ip = str(ipaddress.IPv4Address(fetch(url).strip()))
        except Exception:
            continue
        if ip in seen:
            return ip
        seen.append(ip)
    return None


def cf(path: str, method: str = "GET", body: dict | None = None) -> dict:
    result = json.loads(fetch(f"{API}/zones/{ZONE}{path}", method, body, auth=True))
    if not result.get("success"):
        raise RuntimeError(result.get("errors"))
    return result


def sync(ip: str) -> None:
    records = cf(f"/dns_records?name={HOST}")["result"]
    a_record = None
    for rec in records:
        if rec["type"] == "A":
            a_record = rec
        elif rec["type"] in ("CNAME", "AAAA"):
            # Старая запись туннеля (CNAME) или IPv6 мешали бы прямому входу.
            cf(f"/dns_records/{rec['id']}", "DELETE")
            log(f"удалена запись {rec['type']} {HOST}")
    want = {"type": "A", "name": HOST, "content": ip, "ttl": 120, "proxied": False}
    if a_record is None:
        cf("/dns_records", "POST", want)
        log(f"создана запись {HOST} -> {ip}")
    elif a_record["content"] != ip or a_record.get("proxied"):
        cf(f"/dns_records/{a_record['id']}", "PUT", want)
        log(f"обновлена запись {HOST}: {a_record['content']} -> {ip}")


def main() -> int:
    if not (TOKEN and ZONE and HOST):
        log("CF_API_TOKEN, CF_ZONE_ID или PUBLIC_HOST не заданы в server/.env — DDNS выключен")
        while True:  # не перезапускаемся в цикле, просто ждём настройки
            time.sleep(3600)
    log(f"DDNS: {HOST}, проверка каждые {INTERVAL} с")
    last = None
    while True:
        ip = external_ip()
        if ip is None:
            log("не удалось узнать внешний IP (источники не совпали или недоступны)")
        elif ip != last:
            try:
                sync(ip)
                last = ip
            except Exception as exc:  # сеть, неверный токен — попробуем позже
                log(f"ошибка Cloudflare: {exc}")
        time.sleep(INTERVAL)


if __name__ == "__main__":
    sys.exit(main())
