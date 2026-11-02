from pydantic import BaseModel, Field


class ReadingIn(BaseModel):
    type: str = Field(min_length=1, max_length=64)
    value: float
    unit: str = Field(min_length=1, max_length=16)


class TelemetryIn(BaseModel):
    device_id: str = Field(min_length=1, max_length=64)
    readings: list[ReadingIn] = Field(min_length=1)


class SettingsIn(BaseModel):
    temp_min: float
    temp_max: float


class RegisterIn(BaseModel):
    name: str = Field(min_length=1, max_length=64)
    surname: str = Field(min_length=1, max_length=64)
    email: str = Field(min_length=3, max_length=128)
    password: str = Field(min_length=6, max_length=128)


class LoginIn(BaseModel):
    email: str = Field(min_length=3, max_length=128)
    password: str = Field(min_length=1, max_length=128)
