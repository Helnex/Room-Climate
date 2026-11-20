# -*- coding: utf-8 -*-
"""Обновление Android-приложения со своего сервера (без Google Play).

Свежий APK кладёт скрипт scripts/publish-apk.ps1 в папку APP_RELEASE_DIR (в Docker —
server/app-release, смонтирована только для чтения):
  roomclimate.apk — сам файл (подписан общим ключом проекта);
  latest.json     — {"version_code", "version_name", "sha256", "size", "changes", "published_at"}.

  GET /app/latest          — какая версия свежая (приложение сравнивает со своей);
  GET /app/roomclimate.apk — скачать её.
Без входа: проверка обновлений идёт и до логина, а в APK нет ничего секретного.

  PUT /app/publish — выложить новую версию (GitHub Actions после слияния в main).
  Заголовок X-Publish-Token = APP_PUBLISH_TOKEN из server/.env (пусто — загрузка выключена),
  тело — сам APK, параметры: version_code, version_name, changes.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from fastapi import APIRouter, Header, HTTPException, Query, Request
from fastapi.responses import FileResponse

APK_NAME = "roomclimate.apk"
META_NAME = "latest.json"
MAX_APK_BYTES = 150 * 1024 * 1024


def release_dir() -> Path:
    return Path(os.environ.get("APP_RELEASE_DIR", "/app-release"))


def read_latest() -> dict | None:
    """Описание опубликованной версии или None, если ничего не опубликовано или файлы битые."""
    folder = release_dir()
    meta, apk = folder / META_NAME, folder / APK_NAME
    if not meta.is_file() or not apk.is_file():
        return None
    try:
        # utf-8-sig: PowerShell 5 пишет UTF-8 с BOM.
        data = json.loads(meta.read_text(encoding="utf-8-sig"))
        code = int(data["version_code"])
    except (OSError, ValueError, KeyError, TypeError):
        return None
    if code < 1:
        return None
    return {
        "version_code": code,
        "version_name": str(data.get("version_name") or code)[:32],
        "sha256": str(data.get("sha256") or "").lower()[:64],
        "size": apk.stat().st_size,
        "changes": str(data.get("changes") or "")[:2000],
        "published_at": str(data.get("published_at") or "")[:40],
        "url": "/app/" + APK_NAME,
    }


def build_router() -> APIRouter:
    router = APIRouter()

    @router.get("/app/latest")
    def app_latest():
        latest = read_latest()
        if latest is None:
            raise HTTPException(status_code=404, detail="Новая версия приложения ещё не опубликована")
        return latest

    @router.get("/app/" + APK_NAME)
    def app_apk():
        latest = read_latest()
        if latest is None:
            raise HTTPException(status_code=404, detail="Новая версия приложения ещё не опубликована")
        return FileResponse(
            release_dir() / APK_NAME,
            media_type="application/vnd.android.package-archive",
            filename=f"RoomClimate-{latest['version_name']}.apk",
        )

    @router.put("/app/publish")
    async def app_publish(
        request: Request,
        version_code: int = Query(ge=1),
        version_name: str = Query(min_length=1, max_length=32),
        changes: str = Query(default="", max_length=2000),
        x_publish_token: str | None = Header(default=None),
    ):
        expected = os.environ.get("APP_PUBLISH_TOKEN", "")
        if not expected:
            raise HTTPException(status_code=404, detail="Загрузка новых версий на этом сервере выключена")
        if not x_publish_token or not hmac.compare_digest(x_publish_token, expected):
            raise HTTPException(status_code=403, detail="Неверный токен публикации")
        current = read_latest()
        if current is not None and version_code <= current["version_code"]:
            raise HTTPException(status_code=409,
                                detail=f"Уже опубликована версия {current['version_name']} — нужна новее")

        folder = release_dir()
        folder.mkdir(parents=True, exist_ok=True)
        sha = hashlib.sha256()
        size = 0
        # Сначала во временный файл рядом, потом атомарно на место: приложение никогда
        # не скачает наполовину записанный APK.
        fd, tmp_name = tempfile.mkstemp(dir=folder, suffix=".part")
        try:
            with os.fdopen(fd, "wb") as out:
                async for chunk in request.stream():
                    size += len(chunk)
                    if size > MAX_APK_BYTES:
                        raise HTTPException(status_code=413, detail="Файл слишком большой")
                    sha.update(chunk)
                    out.write(chunk)
            with open(tmp_name, "rb") as f:
                if f.read(2) != b"PK":
                    raise HTTPException(status_code=422, detail="Это не APK")
            meta = {
                "version_code": version_code,
                "version_name": version_name,
                "sha256": sha.hexdigest(),
                "changes": changes,
                "published_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
            }
            os.replace(tmp_name, folder / APK_NAME)
            (folder / META_NAME).write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
        finally:
            if os.path.exists(tmp_name):
                os.unlink(tmp_name)
        return read_latest()

    return router
