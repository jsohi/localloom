import logging
import time

from faster_whisper import WhisperModel
from pydantic import BaseModel

from app.config import settings

logger = logging.getLogger(__name__)


class Segment(BaseModel):
    start: float
    end: float
    text: str


class TranscriptionResult(BaseModel):
    segments: list[Segment]
    duration: float


class WhisperService:
    """Lazy-loading wrapper around faster-whisper."""

    def __init__(self) -> None:
        self._models: dict[str, WhisperModel] = {}

    def _load_model(self, model_name: str) -> WhisperModel:
        """Load and cache a WhisperModel by name. Returns the cached instance on repeat calls."""
        if model_name not in self._models:
            logger.info(
                "Loading Whisper model '%s' from cache dir '%s'",
                model_name,
                settings.model_dir,
            )
            t0 = time.monotonic()
            self._models[model_name] = WhisperModel(
                model_name,
                download_root=settings.model_dir,
                device="auto",
                compute_type=settings.whisper_compute_type,
            )
            elapsed = time.monotonic() - t0
            logger.info("Whisper model '%s' loaded in %.2fs", model_name, elapsed)
        return self._models[model_name]

    def transcribe(self, audio_path: str, model: str | None = None) -> TranscriptionResult:
        """Transcribe the audio file at *audio_path* and return segments with duration."""
        model_name = model or settings.whisper_model
        whisper_model = self._load_model(model_name)

        logger.info(
            "Starting transcription of '%s' with model '%s'",
            audio_path,
            model_name,
        )
        t0 = time.monotonic()

        raw_segments, info = whisper_model.transcribe(audio_path)

        segments: list[Segment] = [
            Segment(start=seg.start, end=seg.end, text=seg.text.strip()) for seg in raw_segments
        ]

        elapsed = time.monotonic() - t0
        logger.info(
            "Transcription complete in %.2fs: %d segment(s), duration=%.2fs",
            elapsed,
            len(segments),
            info.duration,
        )

        return TranscriptionResult(segments=segments, duration=info.duration)


# Module-level singleton — shared across all requests in a single process.
whisper_service = WhisperService()
