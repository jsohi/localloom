from unittest.mock import MagicMock, patch

from app.services.whisper_service import WhisperService


def _mock_whisper_model():
    """Create a mock WhisperModel that returns predictable segments."""
    model = MagicMock()
    seg1 = MagicMock()
    seg1.start = 0.0
    seg1.end = 4.8
    seg1.text = " Hello world "
    seg2 = MagicMock()
    seg2.start = 4.8
    seg2.end = 10.0
    seg2.text = " Testing "

    info = MagicMock()
    info.duration = 10.0

    model.transcribe.return_value = ([seg1, seg2], info)
    return model


@patch("app.services.whisper_service.WhisperModel")
def test_lazy_model_loading(mock_cls):
    mock_cls.return_value = _mock_whisper_model()

    service = WhisperService()
    service.transcribe("/fake/audio.wav", model="base")
    service.transcribe("/fake/audio2.wav", model="base")

    # WhisperModel constructor should only be called once for the same model name
    assert mock_cls.call_count == 1


@patch("app.services.whisper_service.WhisperModel")
def test_different_models_loaded_separately(mock_cls):
    mock_cls.return_value = _mock_whisper_model()

    service = WhisperService()
    service.transcribe("/fake/audio.wav", model="base")
    service.transcribe("/fake/audio2.wav", model="large-v3-turbo")

    assert mock_cls.call_count == 2


@patch("app.services.whisper_service.WhisperModel")
def test_default_model_from_config(mock_cls):
    mock_cls.return_value = _mock_whisper_model()

    service = WhisperService()
    result = service.transcribe("/fake/audio.wav")

    # Should use the default model from settings
    assert mock_cls.call_count == 1
    assert result.duration == 10.0


@patch("app.services.whisper_service.WhisperModel")
def test_transcription_result_parsing(mock_cls):
    mock_cls.return_value = _mock_whisper_model()

    service = WhisperService()
    result = service.transcribe("/fake/audio.wav", model="base")

    assert len(result.segments) == 2
    assert result.segments[0].start == 0.0
    assert result.segments[0].end == 4.8
    assert result.segments[0].text == "Hello world"  # Should be stripped
    assert result.segments[1].text == "Testing"
    assert result.duration == 10.0
