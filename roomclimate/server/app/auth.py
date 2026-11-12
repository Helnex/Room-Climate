# -*- coding: utf-8 -*-
from __future__ import annotations

import hashlib
import hmac
import secrets
import unicodedata

# OWASP (2023+) рекомендует не меньше 600 000 итераций для PBKDF2-HMAC-SHA256.
# Старые хэши со 120 000 итераций продолжают проверяться: число итераций хранится в хэше.
PBKDF2_ITERATIONS = 600_000


def normalize_password(password: str) -> str:
    # «й» и «ё» с разных клавиатур могут прийти как одна буква или как буква + диакритика.
    # NFKC приводит обе записи к одной, иначе пароль с такой буквой «не совпадёт».
    return unicodedata.normalize("NFKC", password)


def _pbkdf2(password: str, salt: str, iterations: int) -> str:
    return hashlib.pbkdf2_hmac(
        "sha256",
        normalize_password(password).encode("utf-8"),
        salt.encode("utf-8"),
        iterations,
    ).hex()


def hash_password(password: str) -> str:
    salt = secrets.token_hex(16)
    digest = _pbkdf2(password, salt, PBKDF2_ITERATIONS)
    return f"pbkdf2_sha256${PBKDF2_ITERATIONS}${salt}${digest}"


def verify_password(password: str, stored: str) -> bool:
    try:
        algo, iterations_raw, salt, expected = stored.split("$", 3)
    except ValueError:
        return False
    if algo != "pbkdf2_sha256":
        return False
    try:
        iterations = int(iterations_raw)
    except ValueError:
        return False
    return hmac.compare_digest(_pbkdf2(password, salt, iterations), expected)


# Хэш-заглушка: при входе с несуществующим email тратим столько же времени,
# сколько на настоящую проверку, чтобы по времени ответа нельзя было узнать,
# зарегистрирован ли email.
_DUMMY_HASH = hash_password(secrets.token_hex(16))


def burn_password_check(password: str) -> None:
    verify_password(password, _DUMMY_HASH)


def new_token() -> str:
    return secrets.token_urlsafe(32)


def hash_token(token: str) -> str:
    # В БД лежит только SHA-256 токена: утечка файла SQLite не даёт войти под пользователем.
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def user_payload(row) -> dict:
    return {
        "id": int(row["id"]),
        "email": row["email"],
        "name": row["name"],
        "surname": row["surname"],
    }
