# -*- coding: utf-8 -*-
"""Обновление приложения со своего сервера: python test_app_update.py"""
import json
import os
import tempfile

os.environ["SQLITE_PATH"] = os.path.join(tempfile.mkdtemp(), "update.db")
os.environ["REDIS_URL"] = "redis://127.0.0.1:1/0"
os.environ["SMTP_HOST"] = ""
RELEASE = tempfile.mkdtemp()
os.environ["APP_RELEASE_DIR"] = RELEASE

from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402


def main():
    with TestClient(app) as c:
        assert c.get("/app/latest").status_code == 404, "ничего не опубликовано"
        assert c.get("/app/roomclimate.apk").status_code == 404
        print("OK  без опубликованной версии — 404")

        apk = b"PK\x03\x04 fake apk"
        with open(os.path.join(RELEASE, "roomclimate.apk"), "wb") as f:
            f.write(apk)
        meta = {"version_code": 57, "version_name": "1.57", "sha256": "AB" * 32,
                "changes": "Платы и датчики из базы сервера", "published_at": "2026-11-18T12:00:00+00:00"}
        # PowerShell 5 пишет UTF-8 с BOM — должно читаться.
        with open(os.path.join(RELEASE, "latest.json"), "w", encoding="utf-8-sig") as f:
            json.dump(meta, f, ensure_ascii=False)

        r = c.get("/app/latest")
        assert r.status_code == 200, r.text
        latest = r.json()
        assert latest["version_code"] == 57 and latest["version_name"] == "1.57", latest
        assert latest["size"] == len(apk) and latest["sha256"] == "ab" * 32, latest
        assert latest["url"] == "/app/roomclimate.apk" and "Платы" in latest["changes"], latest
        print("OK  /app/latest: версия, размер, контрольная сумма, изменения")

        r = c.get("/app/roomclimate.apk")
        assert r.status_code == 200 and r.content == apk, r.status_code
        assert r.headers["content-type"] == "application/vnd.android.package-archive", r.headers
        assert "RoomClimate-1.57.apk" in r.headers.get("content-disposition", ""), r.headers
        print("OK  /app/roomclimate.apk отдаётся как APK")

        with open(os.path.join(RELEASE, "latest.json"), "w", encoding="utf-8") as f:
            f.write("{битый json")
        assert c.get("/app/latest").status_code == 404, "битое описание — как будто ничего нет"
        print("OK  битый latest.json не роняет сервер")
    print("Все проверки обновления приложения пройдены")


if __name__ == "__main__":
    main()
