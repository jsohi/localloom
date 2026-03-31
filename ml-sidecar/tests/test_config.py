from app.config import Settings


def test_default_settings():
    s = Settings()
    assert s.port == 8100
    assert s.whisper_model == "large-v3-turbo"
    assert s.model_dir == "data/models"
    assert s.tts_voice == "en_US-lessac-medium"


def test_env_override(monkeypatch):
    monkeypatch.setenv("LOCALLOOM_WHISPER_MODEL", "base")
    monkeypatch.setenv("LOCALLOOM_PORT", "9000")
    s = Settings()
    assert s.whisper_model == "base"
    assert s.port == 9000
