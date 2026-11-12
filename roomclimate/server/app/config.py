from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    redis_url: str = "redis://localhost:6379/0"
    sqlite_path: str = "data/roomclimate.db"
    current_ttl_seconds: int = 60
    stream_name: str = "telemetry"
    stream_group: str = "persist"

    # Срок жизни сессии (токена) в днях.
    session_ttl_days: int = 30
    # Общий секрет платы: если задан, POST /telemetry требует заголовок X-Device-Token.
    ingest_token: str = ""
    # Защита от подбора пароля: сколько неудачных попыток входа на один email за окно.
    login_max_failures_per_email: int = 10
    login_failure_window_seconds: int = 15 * 60


settings = Settings()
