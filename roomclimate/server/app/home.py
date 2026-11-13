"""«Мой дом» из макета Figma: помещения, добавление датчика, профиль, семейный доступ,
безопасность (смена пароля, история входов) и обращения в поддержку.

Построено поверх уже работающего: доступ к платам (app/access.py, владелец/участник),
сессии (таблица sessions), почта (app/recovery.send_mail).
"""
from __future__ import annotations

import os
import sqlite3
from email.message import EmailMessage

from fastapi import APIRouter, Depends, Header, HTTPException, Path, Query, Request
from pydantic import BaseModel, Field, field_validator

from app import access, auth, db
from app.schemas import SAFE_ID, normalize_email, normalize_text

SCHEMA = """
CREATE TABLE IF NOT EXISTS rooms (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE(owner_id, name),
    FOREIGN KEY(owner_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS support_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER,
    topic TEXT NOT NULL,
    message TEXT NOT NULL,
    created_at TEXT NOT NULL
);
"""
KINDS = {"climate": "Температура и влажность"}


def init(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA)
    db._ensure_column(conn, "devices", "room_id", "INTEGER")
    db._ensure_column(conn, "devices", "kind", "TEXT")
    db._ensure_column(conn, "sessions", "user_agent", "TEXT")
    db._ensure_column(conn, "sessions", "ip", "TEXT")


def remember_session_client(conn: sqlite3.Connection, token_hash: str, request: Request) -> None:
    agent = (request.headers.get("user-agent") or "")[:200]
    ip = request.client.host if request.client else ""
    conn.execute("UPDATE sessions SET user_agent = ?, ip = ? WHERE token = ?", (agent, ip, token_hash))


def _clean_name(value: str) -> str:
    value = normalize_text(value)
    if not value:
        raise ValueError("Название не может быть пустым")
    return value


class RoomIn(BaseModel):
    name: str = Field(min_length=1, max_length=40)
    @field_validator("name")
    @classmethod
    def _name(cls, value: str) -> str:
        return _clean_name(value)


class SensorSetupIn(BaseModel):
    name: str = Field(min_length=1, max_length=40)
    room_id: int | None = None
    kind: str = Field(default="climate", pattern="^(climate)$")
    @field_validator("name")
    @classmethod
    def _name(cls, value: str) -> str:
        return _clean_name(value)


class ProfileIn(BaseModel):
    name: str = Field(min_length=1, max_length=64)
    surname: str = Field(min_length=1, max_length=64)
    email: str = Field(min_length=3, max_length=128)
    @field_validator("name", "surname")
    @classmethod
    def _names(cls, value: str) -> str:
        return _clean_name(value)

    @field_validator("email")
    @classmethod
    def _email(cls, value: str) -> str:
        value = normalize_email(value)
        if "@" not in value or "." not in value.split("@")[-1]:
            raise ValueError("Некорректный email")
        return value


class PasswordChangeIn(BaseModel):
    old_password: str = Field(min_length=1, max_length=128)
    new_password: str = Field(min_length=6, max_length=128)

    @field_validator("new_password")
    @classmethod
    def _check(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("Пароль не может состоять из одних пробелов")
        return value


class EmailIn(BaseModel):
    email: str = Field(min_length=3, max_length=128)

    @field_validator("email")
    @classmethod
    def _clean(cls, value: str) -> str:
        return normalize_email(value)


class SupportIn(BaseModel):
    topic: str = Field(min_length=1, max_length=120)
    message: str = Field(min_length=1, max_length=4000)


def _token_hash(authorization: str | None) -> str:
    _, _, token = (authorization or "").partition(" ")
    return auth.hash_token(token.strip())


def _visible_devices(conn: sqlite3.Connection, uid: int) -> list[sqlite3.Row]:
    """Платы, которые видит пользователь: свои/общие и ещё никем не закреплённые."""
    return conn.execute(
        """
        SELECT d.id AS device_id, d.name AS name, d.room_id AS room_id, d.kind AS kind,
               (SELECT role FROM device_members WHERE device_id = d.id AND user_id = ?) AS role,
               EXISTS(SELECT 1 FROM device_members WHERE device_id = d.id) AS claimed
        FROM devices d ORDER BY d.id
        """,
        (uid,),
    ).fetchall()


def _latest(conn: sqlite3.Connection, device_id: str) -> dict:
    out: dict = {}
    for row in db.last_measurements(conn, device_id):
        out[row["type"]] = row["value"]
        out["measured_at"] = max(out.get("measured_at") or "", row["measured_at"])
    return out


def build_router(current_user) -> APIRouter:
    router = APIRouter()

    # ---------- Помещения ----------

    @router.get("/rooms")
    def list_rooms(user=Depends(current_user)):
        uid = int(user["id"])
        conn = db.connect()
        try:
            devices = [d for d in _visible_devices(conn, uid) if not (d["claimed"] and d["role"] is None)]
            room_ids = {d["room_id"] for d in devices if d["room_id"] is not None}
            rows = conn.execute(
                "SELECT id, name, owner_id FROM rooms WHERE owner_id = ? OR id IN (%s) ORDER BY id"
                % (",".join("?" * len(room_ids)) or "NULL"),
                (uid, *room_ids),
            ).fetchall()
            rooms = {r["id"]: {"id": r["id"], "name": r["name"], "own": r["owner_id"] == uid, "devices": []} for r in rows}
            unassigned = []
            for d in devices:
                latest = _latest(conn, d["device_id"])
                item = {
                    "device_id": d["device_id"],
                    "name": d["name"] or d["device_id"],
                    "kind": d["kind"] or "climate",
                    "role": d["role"],
                    "temperature": latest.get("temperature"),
                    "humidity": latest.get("humidity"),
                    "last_seen": latest.get("measured_at"),
                }
                (rooms[d["room_id"]]["devices"] if d["room_id"] in rooms else unassigned).append(item)
        finally:
            conn.close()
        return {"rooms": list(rooms.values()), "unassigned": unassigned}

    @router.post("/rooms")
    def create_room(body: RoomIn, user=Depends(current_user)):
        conn = db.connect()
        try:
            try:
                cur = conn.execute("INSERT INTO rooms(owner_id, name, created_at) VALUES (?, ?, ?)",
                                   (int(user["id"]), body.name, db.utc_now()))
            except sqlite3.IntegrityError as exc:
                raise HTTPException(status_code=409, detail="Помещение с таким названием уже есть") from exc
            conn.commit()
            return {"id": cur.lastrowid, "name": body.name}
        finally:
            conn.close()

    @router.patch("/rooms/{room_id}")
    def rename_room(body: RoomIn, room_id: int = Path(ge=1), user=Depends(current_user)):
        conn = db.connect()
        try:
            try:
                n = conn.execute("UPDATE rooms SET name = ? WHERE id = ? AND owner_id = ?",
                                 (body.name, room_id, int(user["id"]))).rowcount
            except sqlite3.IntegrityError as exc:
                raise HTTPException(status_code=409, detail="Помещение с таким названием уже есть") from exc
            if not n:
                raise HTTPException(status_code=404, detail="Помещение не найдено")
            conn.commit()
            return {"id": room_id, "name": body.name}
        finally:
            conn.close()

    @router.delete("/rooms/{room_id}")
    def delete_room(room_id: int = Path(ge=1), user=Depends(current_user)):
        conn = db.connect()
        try:
            if not conn.execute("DELETE FROM rooms WHERE id = ? AND owner_id = ?", (room_id, int(user["id"]))).rowcount:
                raise HTTPException(status_code=404, detail="Помещение не найдено")
            conn.execute("UPDATE devices SET room_id = NULL WHERE room_id = ?", (room_id,))
            conn.commit()
        finally:
            conn.close()
        return {"status": "ok"}

    # ---------- Добавление датчика ----------

    @router.post("/devices/{device_id}/setup")
    def setup_device(body: SensorSetupIn, device_id: str = Path(max_length=64, pattern=SAFE_ID),
                     user=Depends(current_user)):
        """«Добавить датчик»: плата закрепляется за пользователем (если ещё ничья), получает
        название и помещение. Плату можно добавить и до того, как она пришлёт показания."""
        uid = int(user["id"])
        conn = db.connect()
        try:
            role = access.role(conn, device_id, uid)
            if role is None:
                if access.is_claimed(conn, device_id):
                    raise HTTPException(status_code=404, detail="Устройство не найдено")
                conn.execute("INSERT OR IGNORE INTO devices(id, name, created_at) VALUES (?, ?, ?)",
                             (device_id, device_id, db.utc_now()))
                conn.execute("INSERT INTO device_members(device_id, user_id, role, created_at) VALUES (?, ?, 'owner', ?)",
                             (device_id, uid, db.utc_now()))
            if body.room_id is not None:
                room = conn.execute("SELECT owner_id FROM rooms WHERE id = ?", (body.room_id,)).fetchone()
                if room is None or (room["owner_id"] != uid and role != "member"):
                    raise HTTPException(status_code=404, detail="Помещение не найдено")
            conn.execute("UPDATE devices SET name = ?, room_id = ?, kind = ? WHERE id = ?",
                         (body.name, body.room_id, body.kind, device_id))
            conn.commit()
        finally:
            conn.close()
        return {"device_id": device_id, "name": body.name, "room_id": body.room_id, "kind": body.kind}

    # ---------- Профиль ----------

    @router.patch("/auth/me")
    def update_profile(body: ProfileIn, user=Depends(current_user)):
        uid = int(user["id"])
        conn = db.connect()
        try:
            other = db.get_user_by_email(conn, body.email)
            if other is not None and int(other["id"]) != uid:
                raise HTTPException(status_code=409, detail="Пользователь с таким email уже есть")
            conn.execute("UPDATE users SET name = ?, surname = ?, email = ? WHERE id = ?",
                         (body.name, body.surname, body.email, uid))
            conn.commit()
            return auth.user_payload(db.get_user_by_id(conn, uid))
        finally:
            conn.close()

    # ---------- Безопасность ----------

    @router.post("/auth/password/change")
    def change_password(body: PasswordChangeIn, authorization: str | None = Header(default=None),
                        user=Depends(current_user)):
        uid = int(user["id"])
        conn = db.connect()
        try:
            row = conn.execute("SELECT password_hash FROM users WHERE id = ?", (uid,)).fetchone()
            if row is None or not auth.verify_password(body.old_password, row["password_hash"]):
                raise HTTPException(status_code=400, detail="Текущий пароль неверный")
            conn.execute("UPDATE users SET password_hash = ? WHERE id = ?", (auth.hash_password(body.new_password), uid))
            # Остальные входы закрываем (вдруг пароль узнал кто-то ещё), текущий остаётся.
            conn.execute("DELETE FROM sessions WHERE user_id = ? AND token != ?", (uid, _token_hash(authorization)))
            conn.commit()
        finally:
            conn.close()
        return {"status": "ok", "detail": "Пароль изменён. На других устройствах нужно войти заново."}

    @router.get("/auth/sessions")
    def list_sessions(authorization: str | None = Header(default=None), user=Depends(current_user)):
        current = _token_hash(authorization)
        conn = db.connect()
        try:
            rows = conn.execute(
                "SELECT token, created_at, user_agent, ip FROM sessions WHERE user_id = ? ORDER BY created_at DESC LIMIT 20",
                (int(user["id"]),),
            ).fetchall()
        finally:
            conn.close()
        return {"sessions": [{
            "created_at": r["created_at"],
            "client": _client_name(r["user_agent"]),
            "ip": r["ip"] or "",
            "current": r["token"] == current,
        } for r in rows]}

    @router.delete("/auth/sessions/others")
    def logout_others(authorization: str | None = Header(default=None), user=Depends(current_user)):
        conn = db.connect()
        try:
            n = conn.execute("DELETE FROM sessions WHERE user_id = ? AND token != ?",
                             (int(user["id"]), _token_hash(authorization))).rowcount
            conn.commit()
        finally:
            conn.close()
        return {"status": "ok", "closed": n}

    # ---------- Семейный доступ ----------

    @router.get("/family")
    def family(user=Depends(current_user)):
        """Все, у кого есть доступ к моим платам: владелец (я) и участники."""
        uid = int(user["id"])
        conn = db.connect()
        try:
            owned = [r["device_id"] for r in conn.execute(
                "SELECT device_id FROM device_members WHERE user_id = ? AND role = 'owner'", (uid,))]
            members = []
            if owned:
                marks = ",".join("?" * len(owned))
                members = [dict(r) for r in conn.execute(
                    f"SELECT DISTINCT u.email, u.name, u.surname FROM device_members dm JOIN users u ON u.id = dm.user_id"
                    f" WHERE dm.device_id IN ({marks}) AND dm.role = 'member' ORDER BY u.name", owned)]
        finally:
            conn.close()
        me = auth.user_payload(user)
        return {
            "devices": len(owned),
            "people": [{"email": me["email"], "name": me["name"], "surname": me["surname"], "role": "owner"}]
                      + [{**m, "role": "member"} for m in members],
        }

    @router.post("/family")
    def family_invite(body: EmailIn, user=Depends(current_user)):
        uid = int(user["id"])
        conn = db.connect()
        try:
            owned = [r["device_id"] for r in conn.execute(
                "SELECT device_id FROM device_members WHERE user_id = ? AND role = 'owner'", (uid,))]
            if not owned:
                raise HTTPException(status_code=400,
                                    detail="Сначала закрепите плату за собой: «Устройства» → плата → «Закрепить за мной»")
            target = db.get_user_by_email(conn, body.email)
            if target is None:
                raise HTTPException(status_code=404, detail="Пользователь с таким email не зарегистрирован")
            if int(target["id"]) == uid:
                raise HTTPException(status_code=400, detail="Это ваш собственный email")
            for device_id in owned:
                conn.execute("INSERT OR IGNORE INTO device_members(device_id, user_id, role, created_at)"
                             " VALUES (?, ?, 'member', ?)", (device_id, int(target["id"]), db.utc_now()))
            conn.commit()
        finally:
            conn.close()
        return {"status": "ok", "devices": len(owned)}

    @router.delete("/family")
    def family_remove(email: str = Query(min_length=3, max_length=128), user=Depends(current_user)):
        uid = int(user["id"])
        conn = db.connect()
        try:
            target = db.get_user_by_email(conn, normalize_email(email))
            if target is not None and int(target["id"]) != uid:
                conn.execute(
                    "DELETE FROM device_members WHERE user_id = ? AND role = 'member' AND device_id IN"
                    " (SELECT device_id FROM device_members WHERE user_id = ? AND role = 'owner')",
                    (int(target["id"]), uid),
                )
                conn.commit()
        finally:
            conn.close()
        return {"status": "ok"}

    # ---------- Помощь ----------

    @router.post("/support")
    def support(body: SupportIn, user=Depends(current_user)):
        topic, message = normalize_text(body.topic), body.message.strip()
        if not topic or not message:
            raise HTTPException(status_code=422, detail="Заполните тему и сообщение")
        conn = db.connect()
        try:
            recent = conn.execute(
                "SELECT COUNT(*) FROM support_messages WHERE user_id = ? AND created_at >= datetime('now', '-1 hour')",
                (int(user["id"]),),
            ).fetchone()[0]
            if recent >= 5:
                raise HTTPException(status_code=429, detail="Слишком много сообщений. Попробуйте через час.")
            conn.execute("INSERT INTO support_messages(user_id, topic, message, created_at) VALUES (?, ?, ?, ?)",
                         (int(user["id"]), topic, message, db.utc_now()))
            conn.commit()
        finally:
            conn.close()
        emailed = _mail_support(user, topic, message)
        return {"status": "ok", "emailed": emailed}

    return router


def _client_name(agent: str | None) -> str:
    agent = agent or ""
    if "okhttp" in agent.lower():
        return "Приложение RoomClimate (Android)"
    if "python" in agent.lower():
        return "Скрипт (Python)"
    if "mozilla" in agent.lower():
        return "Браузер"
    return agent[:40] or "Неизвестное устройство"


def _mail_support(user, topic: str, message: str) -> bool:
    """Письмо в поддержку стенда (SUPPORT_EMAIL или ящик-отправитель), если настроена почта."""
    if not os.environ.get("SMTP_HOST"):
        return False
    try:
        from app.recovery import send_mail
    except ImportError:
        return False
    sender = os.environ.get("SMTP_FROM") or os.environ.get("SMTP_USER", "")
    msg = EmailMessage()
    msg["Subject"] = f"RoomClimate, помощь: {topic[:80]}"
    msg["From"] = sender
    msg["To"] = os.environ.get("SUPPORT_EMAIL") or sender
    msg["Reply-To"] = user["email"]
    msg.set_content(f"От: {user['name']} {user['surname']} <{user['email']}>\n\n{message}\n")
    try:
        send_mail(msg)
        return True
    except Exception:  # noqa: BLE001 — сообщение уже сохранено в базе
        return False

