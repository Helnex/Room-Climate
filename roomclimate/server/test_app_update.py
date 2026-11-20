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
os.environ["APP_PUBLISH_TOKEN"] = "publish-secret"

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

        # Публикация из CI: PUT /app/publish с токеном
        apk2 = b"PK\x03\x04 new version" * 1000
        params = {"version_code": 60, "version_name": "1.60", "changes": "Графики, аккаунт"}
        assert c.put("/app/publish", params=params, content=apk2).status_code == 403, "без токена нельзя"
        assert c.put("/app/publish", params=params, content=apk2,
                     headers={"X-Publish-Token": "wrong"}).status_code == 403
        ok = {"X-Publish-Token": "publish-secret"}
        assert c.put("/app/publish", params=params, content=b"not an apk", headers=ok).status_code == 422
        r = c.put("/app/publish", params=params, content=apk2, headers=ok)
        assert r.status_code == 200, r.text
        import hashlib
        assert r.json()["version_code"] == 60 and r.json()["sha256"] == hashlib.sha256(apk2).hexdigest(), r.json()
        assert c.get("/app/roomclimate.apk").content == apk2
        assert c.put("/app/publish", params=params, content=apk2, headers=ok).status_code == 409, "та же версия"
        assert c.put("/app/publish", params={**params, "version_code": 59}, content=apk2,
                     headers=ok).status_code == 409, "старее — нельзя"
        assert not [f for f in os.listdir(RELEASE) if f.endswith(".part")], "временные файлы убраны"
        print("OK  PUT /app/publish: токен, проверка APK, только новее, атомарная запись")
    print("Все проверки обновления приложения пройдены")


if __name__ == "__main__":
    main()
