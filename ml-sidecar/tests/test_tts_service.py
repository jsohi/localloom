import io
import wave
from unittest.mock import MagicMock, patch

from app.services.tts_service import TTSService, _concatenate_wav, _split_sentences


def _make_wav_bytes(num_frames: int = 100, sample_rate: int = 22050) -> bytes:
    """Create a minimal valid WAV buffer with the given number of silent frames."""
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(b"\x00\x00" * num_frames)
    return buf.getvalue()


# -- sentence splitting tests --------------------------------------------------


def test_split_simple_sentences():
    result = _split_sentences("Hello world. How are you? I am fine!")
    assert result == ["Hello world.", "How are you?", "I am fine!"]


def test_split_single_sentence():
    result = _split_sentences("No punctuation boundary here")
    assert result == ["No punctuation boundary here"]


def test_split_empty_string():
    result = _split_sentences("")
    assert result == [""]


def test_split_preserves_abbreviations_without_space():
    # "Dr.Smith" has no space after the period, so it should NOT split.
    result = _split_sentences("Dr.Smith went home")
    assert result == ["Dr.Smith went home"]


# -- WAV concatenation tests ---------------------------------------------------


def test_concatenate_single_buffer():
    wav = _make_wav_bytes(50)
    result = _concatenate_wav([wav])
    assert result == wav  # single buffer returned as-is


def test_concatenate_two_buffers():
    wav1 = _make_wav_bytes(50)
    wav2 = _make_wav_bytes(30)
    result = _concatenate_wav([wav1, wav2])

    # The combined WAV should have 80 frames total.
    with wave.open(io.BytesIO(result), "rb") as wf:
        assert wf.getnframes() == 80
        assert wf.getnchannels() == 1
        assert wf.getsampwidth() == 2


# -- TTSService tests ---------------------------------------------------------


@patch("app.services.tts_service.PiperVoice")
def test_lazy_voice_loading(mock_piper_cls):
    mock_voice = MagicMock()
    mock_piper_cls.load.return_value = mock_voice

    # Make synthesize write a valid WAV when called.
    mock_voice.synthesize.side_effect = lambda text, wav_file: _write_silent_wav(wav_file)

    service = TTSService()
    service.synthesize("Hello.", voice="test-voice")
    service.synthesize("Again.", voice="test-voice")

    # PiperVoice.load should only be called once for the same voice.
    assert mock_piper_cls.load.call_count == 1


@patch("app.services.tts_service.PiperVoice")
def test_different_voices_loaded_separately(mock_piper_cls):
    mock_voice = MagicMock()
    mock_piper_cls.load.return_value = mock_voice
    mock_voice.synthesize.side_effect = lambda text, wav_file: _write_silent_wav(wav_file)

    service = TTSService()
    service.synthesize("Hello.", voice="voice-a")
    service.synthesize("Hello.", voice="voice-b")

    assert mock_piper_cls.load.call_count == 2


@patch("app.services.tts_service.PiperVoice")
def test_synthesize_splits_long_text(mock_piper_cls):
    mock_voice = MagicMock()
    mock_piper_cls.load.return_value = mock_voice
    mock_voice.synthesize.side_effect = lambda text, wav_file: _write_silent_wav(wav_file)

    service = TTSService()
    result = service.synthesize("First sentence. Second sentence.", voice="test-voice")

    # Should call synthesize twice (once per sentence).
    assert mock_voice.synthesize.call_count == 2
    # Result should be valid WAV bytes.
    assert result[:4] == b"RIFF"


def _write_silent_wav(wav_file: wave.Wave_write, num_frames: int = 10) -> None:
    """Helper: write minimal silent WAV data into an already-opened wave.Wave_write."""
    wav_file.setnchannels(1)
    wav_file.setsampwidth(2)
    wav_file.setframerate(22050)
    wav_file.writeframes(b"\x00\x00" * num_frames)
