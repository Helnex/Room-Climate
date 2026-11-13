"""Запросы из интернета: через Caddy (свой домен и белый IP) или Cloudflare Tunnel.

Оба — контейнеры в той же сети Docker (`caddy`, `cloudflared`): для API все запросы из
интернета приходят с их адресов. Поэтому настоящий IP клиента берём из заголовка
(X-Forwarded-For от Caddy, CF-Connecting-IP от туннеля), но ТОЛЬКО если запрос пришёл от
этих контейнеров — иначе любой в Wi‑Fi подделал бы заголовок и обошёл лимиты.
Адрес для приложения из интернета — https с настоящим сертификатом, pinning не нужен.
"""
from __future__ import annotations

import ipaddress
import socket
import time

from fastapi import Request

PROXY_HOSTS = ("caddy", "cloudflared")   # имена сервисов в docker-compose
_RESOLVE_EVERY = 60.0
_cache: dict[str, object] = {"ips": frozenset(), "at": 0.0}


def _tunnel_ips() -> frozenset[str]:
    now = time.monotonic()
    if now - float(_cache["at"]) > _RESOLVE_EVERY:
        ips: set[str] = set()
        for host in PROXY_HOSTS:
            try:
                ips.update(info[4][0] for info in socket.getaddrinfo(host, None))
            except OSError:
                pass  # этот сервис не запущен
        _cache["ips"] = frozenset(ips)
        _cache["at"] = now
    return _cache["ips"]  # type: ignore[return-value]


def via_tunnel(request: Request) -> bool:
    peer = request.client.host if request.client else ""
    return bool(peer) and peer in _tunnel_ips()


def client_ip(request: Request) -> str:
    peer = request.client.host if request.client else "unknown"
    if via_tunnel(request):
        forwarded = request.headers.get("cf-connecting-ip", "").strip()
        if not forwarded:
            # Caddy пишет адрес клиента последним в X-Forwarded-For.
            forwarded = request.headers.get("x-forwarded-for", "").split(",")[-1].strip()
        try:
            return str(ipaddress.ip_address(forwarded))
        except ValueError:
            return peer
    return peer
