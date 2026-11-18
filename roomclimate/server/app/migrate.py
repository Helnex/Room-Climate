# -*- coding: utf-8 -*-
"""Миграции базы: файлы app/migrations/NNN_название.sql применяются по порядку номеров.

Какие уже применены — в таблице schema_migrations. Сервер применяет новые сам при старте
(db.init_db), вручную: python -m app.migrate (или --status — только показать).
Каждая миграция — одна транзакция: упала посередине — откатывается целиком.
"""
from __future__ import annotations

import re
import sqlite3
import sys
from pathlib import Path

MIGRATIONS_DIR = Path(__file__).with_name("migrations")
FILE_RE = re.compile(r"^(\d{3})_[A-Za-z0-9_\-]+\.sql$")


def available() -> list[tuple[int, str, Path]]:
    found = []
    for path in sorted(MIGRATIONS_DIR.glob("*.sql")):
        match = FILE_RE.match(path.name)
        if not match:
            raise RuntimeError(f"Имя миграции {path.name}: нужно NNN_название.sql (например 003_new_sensor.sql)")
        found.append((int(match.group(1)), path.stem, path))
    versions = [v for v, _, _ in found]
    if len(versions) != len(set(versions)):
        raise RuntimeError("Две миграции с одним номером — переименуйте одну")
    return found


def statements(sql: str) -> list[str]:
    """SQL-файл → отдельные команды (executescript нельзя: он сам коммитит транзакцию)."""
    out, buf = [], ""
    for line in sql.splitlines(keepends=True):
        buf += line
        if sqlite3.complete_statement(buf):
            if buf.strip():
                out.append(buf.strip())
            buf = ""
    if buf.strip() and not all(l.strip().startswith("--") or not l.strip() for l in buf.splitlines()):
        raise RuntimeError("Последняя команда миграции не закончена (нет «;»)")
    return out


def _done(conn: sqlite3.Connection) -> set[int]:
    return {row[0] for row in conn.execute("SELECT version FROM schema_migrations")}


def apply(conn: sqlite3.Connection) -> list[str]:
    """Применяет новые миграции, возвращает их имена. Безопасно при одновременном старте
    API и воркера: BEGIN IMMEDIATE + повторная проверка внутри блокировки."""
    conn.execute(
        "CREATE TABLE IF NOT EXISTS schema_migrations ("
        " version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at TEXT NOT NULL)"
    )
    conn.commit()
    applied = []
    for version, name, path in available():
        if version in _done(conn):
            continue
        sql = path.read_text(encoding="utf-8")
        old_isolation = conn.isolation_level
        conn.isolation_level = None  # транзакцией управляем сами
        try:
            conn.execute("BEGIN IMMEDIATE")
            if version in _done(conn):  # успел другой процесс
                conn.execute("ROLLBACK")
                continue
            for stmt in statements(sql):
                conn.execute(stmt)
            conn.execute(
                "INSERT INTO schema_migrations(version, name, applied_at)"
                " VALUES (?, ?, strftime('%Y-%m-%dT%H:%M:%S+00:00', 'now'))",
                (version, name),
            )
            conn.execute("COMMIT")
        except Exception as exc:
            conn.execute("ROLLBACK")
            raise RuntimeError(f"Миграция {path.name} не применилась: {exc}") from exc
        finally:
            conn.isolation_level = old_isolation
        applied.append(name)
    return applied


def main(argv: list[str]) -> int:
    from app import db

    if "--status" in argv:
        conn = db.connect()
        try:
            conn.execute("CREATE TABLE IF NOT EXISTS schema_migrations ("
                         " version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at TEXT NOT NULL)")
            done = {r["version"]: r["applied_at"] for r in conn.execute("SELECT version, applied_at FROM schema_migrations")}
        finally:
            conn.close()
        for version, name, _ in available():
            print(f"{'✓' if version in done else '·'} {name}" + (f"  ({done[version]})" if version in done else "  — не применена"))
        return 0
    db.init_db()  # создаёт базовые таблицы и применяет миграции
    print("Миграции применены, база актуальна.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
