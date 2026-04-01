from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    model_dir: str = "data/models"
    whisper_model: str = "large-v3-turbo"
    whisper_compute_type: str = "auto"
    tts_voice: str = "en_US-lessac-medium"
    tts_output_dir: str = "data/tts_output"
    host: str = "0.0.0.0"
    port: int = 8100

    class Config:
        env_prefix = "LOCALLOOM_"
        env_file = ".env"


settings = Settings()
