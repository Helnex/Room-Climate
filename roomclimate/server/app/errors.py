# -*- coding: utf-8 -*-
"""Перевод ошибок FastAPI/pydantic на русский язык.

Без этого модуля на неверный ввод (короткий пароль, пустое поле, битый JSON)
сервер отвечал английским списком вида
{"detail":[{"type":"string_too_short","msg":"String should have at least 6 characters",...}]},
а приложение показывало этот JSON пользователю как есть.
"""
from __future__ import annotations

from typing import Any, Iterable

FIELD_NAMES = {
    "name": "Имя",
    "surname": "Фамилия",
    "email": "Email",
    "password": "Пароль",
    "device_id": "Идентификатор устройства",
    "readings": "Показания",
    "type": "Тип датчика",
    "value": "Значение",
    "unit": "Единица измерения",
    "temp_min": "Минимальная температура",
    "temp_max": "Максимальная температура",
    "humidity_min": "Минимальная влажность",
    "humidity_max": "Максимальная влажность",
    "from": "Начало периода",
    "to": "Конец периода",
}

HTTP_DEFAULTS = {
    400: "Некорректный запрос",
    401: "Требуется вход в аккаунт",
    403: "Доступ запрещён",
    404: "Не найдено",
    405: "Метод не поддерживается",
    409: "Конфликт данных",
    413: "Слишком большой запрос",
    415: "Неподдерживаемый формат запроса",
    422: "Некорректные данные",
    429: "Слишком много запросов",
    500: "Внутренняя ошибка сервера",
    503: "Сервис временно недоступен",
}

# Английские тексты, которые генерируют сами Starlette/FastAPI.
_ENGLISH_DETAILS = {
    "There was an error parsing the body": "Не удалось разобрать тело запроса. Отправьте JSON в кодировке UTF-8",
    "Not Found": HTTP_DEFAULTS[404],
    "Method Not Allowed": HTTP_DEFAULTS[405],
    "Not authenticated": HTTP_DEFAULTS[401],
    "Internal Server Error": HTTP_DEFAULTS[500],
}


def translate_http_detail(status_code: int, detail: Any) -> Any:
    if isinstance(detail, str):
        if detail in _ENGLISH_DETAILS:
            return _ENGLISH_DETAILS[detail]
        return detail
    if detail is None:
        return HTTP_DEFAULTS.get(status_code, "Ошибка")
    return detail


def _plural_chars(n: int) -> str:
    n = abs(int(n))
    if n % 10 == 1 and n % 100 != 11:
        return "символ"
    if 2 <= n % 10 <= 4 and not 12 <= n % 100 <= 14:
        return "символа"
    return "символов"


def _field_label(loc: Iterable[Any]) -> str | None:
    parts = [p for p in loc if p not in ("body", "query", "header", "path")]
    for part in reversed(parts):
        if isinstance(part, str):
            return FIELD_NAMES.get(part, part)
    return None


def _one(error: dict) -> str:
    kind = error.get("type", "")
    ctx = error.get("ctx") or {}
    label = _field_label(error.get("loc", ()))
    prefix = f"{label}: " if label else ""

    if kind == "json_invalid":
        return "Некорректный JSON в запросе"
    if kind == "missing":
        return f"{prefix}обязательное поле".strip() if label else "Не хватает обязательного поля"
    if kind == "value_error":
        # Сообщение из нашего field_validator уже на русском.
        original = ctx.get("error")
        return str(original) if original else f"{prefix}некорректное значение"
    if kind == "string_too_short":
        n = ctx.get("min_length", 1)
        if label == FIELD_NAMES["password"]:
            return f"Пароль должен быть не короче {n} {_plural_chars(n)}"
        if n <= 1:
            return f"{prefix}поле не должно быть пустым"
        return f"{prefix}не короче {n} {_plural_chars(n)}"
    if kind == "string_too_long":
        n = ctx.get("max_length", 0)
        return f"{prefix}не длиннее {n} {_plural_chars(n)}"
    if kind in ("too_short",):
        return f"{prefix}нужно хотя бы одно значение"
    if kind in ("too_long",):
        return f"{prefix}слишком много значений"
    if kind in ("string_type",):
        return f"{prefix}ожидается строка"
    if kind in ("float_parsing", "float_type", "int_parsing", "int_type"):
        return f"{prefix}ожидается число"
    if kind == "finite_number":
        return f"{prefix}ожидается конечное число"
    if kind == "greater_than_equal":
        return f"{prefix}должно быть не меньше {ctx.get('ge')}"
    if kind == "less_than_equal":
        return f"{prefix}должно быть не больше {ctx.get('le')}"
    if kind in ("model_attributes_type", "dict_type", "model_type", "list_type"):
        return f"{prefix}некорректный формат данных" if label else "Некорректный формат запроса"
    return f"{prefix}некорректное значение" if label else "Некорректные данные"


def validation_message(errors: Iterable[dict]) -> str:
    seen: list[str] = []
    for error in errors:
        text = _one(error)
        if text not in seen:
            seen.append(text)
    return "; ".join(seen) if seen else HTTP_DEFAULTS[422]
