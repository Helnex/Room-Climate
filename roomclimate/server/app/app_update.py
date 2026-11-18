# -*- coding: utf-8 -*-
"""Обновление Android-приложения со своего сервера (без Google Play).

Свежий APK кладёт скрипт scripts/publish-apk.ps1 в папку APP_RELEASE_DIR (в Docker —
server/app-release, смонтирована только для чтения):
  roomclimate.apk — сам файл (подписан общим ключом проекта);
  latest.json     — {"version_code", "version_name", "sha256", "size", "changes", "published_at"}.

  GET /app/latest          — какая версия свежая (приложение сравнивает со своей);
  GET /app/roomclimate.apk — скачать её.
Без входа: проверка обновлений идёт и до логина, а в APK нет ничего секретного.
"""
from __future__ import annotations

import json
import os
from pathlib import Path

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse

APK_NAME = "roomclimate.apk"
META_NAME = "latest.json"


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

    return router
