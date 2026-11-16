# -*- coding: utf-8 -*-
"""Буфер показаний на плате: если сервер или Wi-Fi недоступны, показания не теряются.

Каждое неотправленное тело запроса /telemetry дописывается строкой JSON в файл
(с полем measured_at — временем замера). Когда связь вернулась, сборщик досылает их
порциями, старые первыми; сервер кладёт их в историю с настоящим временем.

Файл — BUFFER_PATH; по умолчанию в каталоге состояния службы systemd
(/var/lib/roomclimate, StateDirectory) или рядом со сборщиком при запуске вручную.
Размер ограничен BUFFER_MAX строк (по умолчанию 20 000 ≈ 2,5 суток при отправке раз в 10 с):
при переполнении выбрасываются самые старые.
"""
from __future__ import annotations

import json
import os
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path


def default_path() -> str:
    state = os.environ.get("STATE_DIRECTORY")  # systemd StateDirectory=roomclimate
    base = state.split(":")[0] if state else os.path.dirname(os.path.abspath(__file__))
    return os.path.join(base, "buffer.jsonl")


BUFFER_PATH = os.environ.get("BUFFER_PATH") or default_path()
BUFFER_MAX = int(os.environ.get("BUFFER_MAX", "20000"))
# Сколько накопленных показаний досылать за один цикл (чтобы не задерживать свежие).
FLUSH_BATCH = int(os.environ.get("FLUSH_BATCH", "30"))


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def boot_id() -> str:
    try:
        with open("/proc/sys/kernel/random/boot_id", encoding="ascii") as f:
            return f.read().strip()
    except OSError:
        return "pc"


def measured_at(item: dict, now_wall: datetime | None = None, now_mono: float | None = None,
                current_boot: str | None = None) -> str:
    """Время замера накопленного показания.

    У Raspberry Pi нет часов на батарейке: включилась без интернета — системное время
    неверное, пока не синхронизируется. Поэтому вместе с показанием храним монотонное время
    (секунды с загрузки) и id загрузки. Если досылаем в ту же загрузку — время замера
    = «сейчас» (уже точное, связь-то есть) минус сколько прошло по монотонным часам.
    Иначе (плата перезагружалась) — записанное тогда системное время.
    """
    now_wall = now_wall or datetime.now(timezone.utc)
    now_mono = time.monotonic() if now_mono is None else now_mono
    current_boot = current_boot or boot_id()
    if item.get("boot") == current_boot and isinstance(item.get("mono"), (int, float)):
        when = now_wall - timedelta(seconds=max(0.0, now_mono - item["mono"]))
        return when.replace(microsecond=0).isoformat()
    return item.get("wall") or now_iso()


class Buffer:
    def __init__(self, path: str = BUFFER_PATH, max_items: int = BUFFER_MAX):
        self.path = Path(path)
        self.max_items = max_items

    def _load(self) -> list[dict]:
        try:
            lines = self.path.read_text(encoding="utf-8").splitlines()
        except FileNotFoundError:
            return []
        items = []
        for line in lines:
            try:
                items.append(json.loads(line))
            except ValueError:
                continue  # битая строка (питание пропало посреди записи) — пропускаем
        return items

    def _save(self, items: list[dict]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text("".join(json.dumps(i, ensure_ascii=False) + "\n" for i in items), encoding="utf-8")
        os.replace(tmp, self.path)  # атомарно: при сбое питания остаётся старый или новый файл

    def __len__(self) -> int:
        return len(self._load())

    def add(self, body: dict) -> None:
        items = self._load()
        items.append({"body": body, "wall": now_iso(), "mono": time.monotonic(), "boot": boot_id()})
        if len(items) > self.max_items:
            items = items[-self.max_items:]
        self._save(items)

    def flush(self, send, limit: int = FLUSH_BATCH) -> int:
        """Дослать до limit самых старых показаний через send(body). Возвращает число отправленных.

        Останавливается на первой ошибке связи — остальное ждёт следующего цикла. Показание,
        которое сервер отверг как неверное (4xx: например, старше недели), выбрасывается,
        чтобы не застрять на нём навсегда.
        """
        items = self._load()
        if not items:
            return 0
        sent = 0
        dropped = 0
        for item in items[:limit]:
            body = dict(item.get("body") or {})
            body["measured_at"] = measured_at(item)
            try:
                send(body)
            except Exception as exc:  # noqa: BLE001
                code = getattr(exc, "code", None)
                if isinstance(code, int) and 400 <= code < 500 and code not in (401, 408, 429):
                    dropped += 1
                    continue
                break
            sent += 1
        done = sent + dropped
        if done:
            self._save(items[done:])
        return sent
