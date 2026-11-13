"""Запросы через Cloudflare Tunnel (доступ к стенду из интернета).

Туннель — контейнер `cloudflared` в той же сети Docker: для API все запросы из интернета
приходят с его адреса. Поэтому:
- настоящий IP клиента берём из заголовка CF-Connecting-IP, но ТОЛЬКО если запрос пришёл
  от контейнера cloudflared (иначе любой в Wi‑Fi подделал бы заголовок и обошёл лимиты);
- регистрация через туннель запрещена: регистрируются в локальной сети стенда, а из
  интернета только входят (иначе зарегистрироваться мог бы кто угодно);
- адрес для приложения через туннель — https://<имя>.trycloudflare.com (сертификат
  Cloudflare настоящий, pinning не нужен).
"""
from __future__ import annotations

import ipaddress
import socket
import time

from fastapi import Request

TUNNEL_HOST = "cloudflared"   # имя сервиса в docker-compose
_RESOLVE_EVERY = 60.0
_cache: dict[str, object] = {"ips": frozenset(), "at": 0.0}


def _tunnel_ips() -> frozenset[str]:
    now = time.monotonic()
    if now - float(_cache["at"]) > _RESOLVE_EVERY:
        try:
            infos = socket.getaddrinfo(TUNNEL_HOST, None)
            _cache["ips"] = frozenset(info[4][0] for info in infos)
        except OSError:
            _cache["ips"] = frozenset()  # туннель не запущен
        _cache["at"] = now
    return _cache["ips"]  # type: ignore[return-value]


def via_tunnel(request: Request) -> bool:
    peer = request.client.host if request.client else ""
    return bool(peer) and peer in _tunnel_ips()


def client_ip(request: Request) -> str:
    peer = request.client.host if request.client else "unknown"
    if via_tunnel(request):
        forwarded = request.headers.get("cf-connecting-ip", "").strip()
        try:
            return str(ipaddress.ip_address(forwarded))
        except ValueError:
            return peer
    return peer
