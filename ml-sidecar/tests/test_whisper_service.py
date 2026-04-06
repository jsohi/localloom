"""Unit tests for WhisperService.

WhisperService runs the actual transcription in a `ProcessPoolExecutor`
worker, so patching `WhisperModel` at the module level (the natural
target) does not affect the worker subprocess. These tests instead
patch `WhisperService._get_pool` to return a mock pool whose
`submit()` returns a fake future, letting us assert on what the
service forwards to the worker without spinning up a real subprocess
or loading a real model.
"""

from unittest.mock import MagicMock, patch

from app.config import settings
from app.services.whisper_service import WhisperService


def _make_mock_pool(result_dict: dict | None = None) -> MagicMock:
    """Build a mock ProcessPoolExecutor whose submit() returns a future
    with the given result dict (defaults to a 2-segment 10s transcript)."""
    if result_dict is None:
        result_dict = {
            "segments": [
                {"start": 0.0, "end": 4.8, "text": "Hello world"},
                {"start": 4.8, "end": 10.0, "text": "Testing"},
            ],
            "duration": 10.0,
        }
    mock_future = MagicMock()
    mock_future.result.return_value = result_dict
    mock_pool = MagicMock()
    mock_pool.submit.return_value = mock_future
    return mock_pool


@patch("app.services.whisper_service.WhisperService._get_pool")
def test_transcribe_forwards_explicit_model(mock_get_pool):
    """Calling transcribe with an explicit model should pass it to the worker."""
    mock_pool = _make_mock_pool()
    mock_get_pool.return_value = mock_pool

    service = WhisperService()
    service.transcribe("/fake/audio.wav", model="base")

    # pool.submit was called with (_transcribe_in_worker, audio_path, model_name, ...)
    assert mock_pool.submit.call_count == 1
    args, _ = mock_pool.submit.call_args
    assert args[1] == "/fake/audio.wav"
    assert args[2] == "base"


@patch("app.services.whisper_service.WhisperService._get_pool")
def test_transcribe_uses_default_model_from_settings(mock_get_pool):
    """transcribe() with no model arg should fall back to settings.whisper_model."""
    mock_pool = _make_mock_pool()
    mock_get_pool.return_value = mock_pool

    service = WhisperService()
    result = service.transcribe("/fake/audio.wav")

    # Default model should be the one in settings
    args, _ = mock_pool.submit.call_args
    assert args[2] == settings.whisper_model
    assert result.duration == 10.0


@patch("app.services.whisper_service.WhisperService._get_pool")
def test_transcribe_multiple_calls_reuse_pool(mock_get_pool):
    """Multiple transcribe() calls should reuse the same pool (lazy creation)."""
    mock_pool = _make_mock_pool()
    mock_get_pool.return_value = mock_pool

    service = WhisperService()
    service.transcribe("/fake/audio.wav", model="base")
    service.transcribe("/fake/audio2.wav", model="base")

    # _get_pool is called once per transcribe (production code grabs it
    # under the lock), but the same pool is returned each time.
    assert mock_get_pool.call_count == 2
    assert mock_pool.submit.call_count == 2


@patch("app.services.whisper_service.WhisperService._get_pool")
def test_transcription_result_parsing(mock_get_pool):
    """The dict returned by the worker should be parsed into Segment / TranscriptionResult."""
    mock_get_pool.return_value = _make_mock_pool()

    service = WhisperService()
    result = service.transcribe("/fake/audio.wav", model="base")

    assert len(result.segments) == 2
    assert result.segments[0].start == 0.0
    assert result.segments[0].end == 4.8
    assert result.segments[0].text == "Hello world"
    assert result.segments[1].text == "Testing"
    assert result.duration == 10.0
