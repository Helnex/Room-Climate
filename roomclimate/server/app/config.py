from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    redis_url: str = "redis://localhost:6379/0"
    sqlite_path: str = "data/roomclimate.db"
    current_ttl_seconds: int = 60
    stream_name: str = "telemetry"
    stream_group: str = "persist"


settings = Settings()
