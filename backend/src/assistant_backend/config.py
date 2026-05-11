from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    openrouter_api_key: str
    fast_model: str = "anthropic/claude-haiku-4-5"
    smart_model: str = "anthropic/claude-opus-4-7"

    google_client_id: str = ""
    google_client_secret: str = ""
    google_refresh_token: str = ""

    port: int = 8000


settings = Settings()  # type: ignore[call-arg]
