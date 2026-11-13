import re
import unicodedata

from pydantic import BaseModel, ConfigDict, Field, field_validator

# Простая проверка вида «что-то@домен.зона». Кириллические адреса (иван@почта.рф) тоже проходят.
_EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


def normalize_text(value: str) -> str:
    # NFKC: «й»/«ё», набранные как буква + диакритика, превращаются в одну букву.
    return unicodedata.normalize("NFKC", value).strip()


def normalize_email(value: str) -> str:
    return normalize_text(value).lower()


# Идентификаторы платы и датчика попадают в ключи Redis, имена файлов и CSV-выгрузку:
# только безопасные символы (защита от формульных инъекций в Excel и мусора в ключах).
SAFE_ID = r"^[A-Za-z0-9_.\-]+$"


class ReadingIn(BaseModel):
    model_config = ConfigDict(allow_inf_nan=False)

    type: str = Field(min_length=1, max_length=64, pattern=SAFE_ID)
    value: float
    unit: str = Field(min_length=1, max_length=16)


class TelemetryIn(BaseModel):
    device_id: str = Field(min_length=1, max_length=64, pattern=SAFE_ID)
    readings: list[ReadingIn] = Field(min_length=1, max_length=32)


class SettingsIn(BaseModel):
    # NaN/Infinity в JSON раньше роняли сервер с 500 (NOT NULL в SQLite).
    model_config = ConfigDict(allow_inf_nan=False)

    temp_min: float = Field(ge=-100, le=200)
    temp_max: float = Field(ge=-100, le=200)
    humidity_min: float = Field(default=30.0, ge=0, le=100)
    humidity_max: float = Field(default=70.0, ge=0, le=100)


class RegisterIn(BaseModel):
    name: str = Field(min_length=1, max_length=64)
    surname: str = Field(min_length=1, max_length=64)
    email: str = Field(min_length=3, max_length=128)
    password: str = Field(min_length=6, max_length=128)
    # Код приглашения: нужен только при регистрации из интернета (REGISTER_INVITE_CODE).
    invite_code: str | None = Field(default=None, max_length=64)

    @field_validator("name", "surname")
    @classmethod
    def _clean_name(cls, value: str) -> str:
        value = normalize_text(value)
        if not value:
            raise ValueError("Укажите имя и фамилию")
        if any(unicodedata.category(ch).startswith("C") for ch in value):
            raise ValueError("Имя и фамилия не должны содержать служебные символы")
        return value

    @field_validator("email")
    @classmethod
    def _clean_email(cls, value: str) -> str:
        value = normalize_email(value)
        if not _EMAIL_RE.match(value):
            raise ValueError("Некорректный email")
        return value

    @field_validator("password")
    @classmethod
    def _check_password(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("Пароль не может состоять из одних пробелов")
        return value


class LoginIn(BaseModel):
    email: str = Field(min_length=3, max_length=128)
    password: str = Field(min_length=1, max_length=128)

    @field_validator("email")
    @classmethod
    def _clean_email(cls, value: str) -> str:
        return normalize_email(value)

