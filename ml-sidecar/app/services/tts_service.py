import io
import logging
import re
import time
import wave

from piper import PiperVoice

from app.config import settings

logger = logging.getLogger(__name__)

# Sentence-boundary regex: split on `.`, `!`, or `?` followed by whitespace.
_SENTENCE_RE = re.compile(r"(?<=[.!?])\s+")


def _split_sentences(text: str) -> list[str]:
    """Split *text* into sentences on common punctuation boundaries.

    Returns a list with at least one element (the original text) when no
    sentence boundary is detected.
    """
    sentences = [s.strip() for s in _SENTENCE_RE.split(text) if s.strip()]
    return sentences if sentences else [text.strip()]


def _concatenate_wav(buffers: list[bytes]) -> bytes:
    """Concatenate multiple single-channel WAV byte buffers into one WAV file.

    All buffers must share the same sample rate, sample width, and channel
    count (which Piper guarantees for a single voice).
    """
    if len(buffers) == 1:
        return buffers[0]

    # Read params from the first buffer to use as the canonical format.
    with wave.open(io.BytesIO(buffers[0]), "rb") as first:
        params = first.getparams()

    raw_frames = bytearray()
    for buf in buffers:
        with wave.open(io.BytesIO(buf), "rb") as wf:
            raw_frames.extend(wf.readframes(wf.getnframes()))

    out = io.BytesIO()
    with wave.open(out, "wb") as wf_out:
        wf_out.setparams(params)
        wf_out.writeframes(raw_frames)

    return out.getvalue()


def _synthesize_to_wav(voice: PiperVoice, text: str) -> bytes:
    """Synthesize a single text fragment and return WAV bytes."""
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wav_file:
        voice.synthesize(text, wav_file)
    return buf.getvalue()


class TTSService:
    """Lazy-loading wrapper around Piper TTS."""

    def __init__(self) -> None:
        self._voices: dict[str, PiperVoice] = {}

    def _load_voice(self, voice_name: str) -> PiperVoice:
        """Load and cache a PiperVoice by name. Downloads the model on first use."""
        if voice_name not in self._voices:
            logger.info(
                "Loading Piper voice '%s' from model dir '%s'",
                voice_name,
                settings.model_dir,
            )
            t0 = time.monotonic()
            self._voices[voice_name] = PiperVoice.load(
                voice_name,
                download_dir=settings.model_dir,
            )
            elapsed = time.monotonic() - t0
            logger.info("Piper voice '%s' loaded in %.2fs", voice_name, elapsed)
        return self._voices[voice_name]

    def synthesize(self, text: str, voice: str | None = None) -> bytes:
        """Synthesize *text* into WAV audio bytes.

        Long text is split into sentences, each synthesized individually, then
        the resulting WAV buffers are concatenated into a single WAV file.
        """
        voice_name = voice or settings.tts_voice
        piper_voice = self._load_voice(voice_name)

        sentences = _split_sentences(text)
        logger.info(
            "Synthesizing %d sentence(s) with voice '%s'",
            len(sentences),
            voice_name,
        )
        t0 = time.monotonic()

        wav_buffers = [_synthesize_to_wav(piper_voice, sentence) for sentence in sentences]
        result = _concatenate_wav(wav_buffers)

        elapsed = time.monotonic() - t0
        logger.info(
            "TTS synthesis complete in %.2fs: %d sentence(s), %d bytes",
            elapsed,
            len(sentences),
            len(result),
        )
        return result


# Module-level singleton -- shared across all requests in a single process.
tts_service = TTSService()
