import logging
import os
import threading
import time
from concurrent.futures import ProcessPoolExecutor

from faster_whisper import WhisperModel
from pydantic import BaseModel

from app.config import settings

logger = logging.getLogger(__name__)

MAX_WORKERS = int(os.environ.get("SIDECAR_WORKERS", "8"))
IDLE_TIMEOUT_SECONDS = 60


class Segment(BaseModel):
    start: float
    end: float
    text: str


class TranscriptionResult(BaseModel):
    segments: list[Segment]
    duration: float


def _transcribe_in_worker(
    audio_path: str, model_name: str, model_dir: str, compute_type: str
) -> dict:
    """Run transcription in a worker process. Each process loads its own model."""
    # Each worker process has its own module-level model cache
    global _worker_model, _worker_model_name
    if "_worker_model" not in globals() or _worker_model_name != model_name:
        logger.info("Worker PID %d loading Whisper model '%s'", os.getpid(), model_name)
        t0 = time.monotonic()
        _worker_model = WhisperModel(
            model_name,
            download_root=model_dir,
            device="auto",
            compute_type=compute_type,
        )
        _worker_model_name = model_name
        logger.info(
            "Worker PID %d loaded '%s' in %.2fs", os.getpid(), model_name, time.monotonic() - t0
        )

    logger.info("Worker PID %d transcribing '%s'", os.getpid(), audio_path)
    t0 = time.monotonic()
    raw_segments, info = _worker_model.transcribe(audio_path)
    segments = [{"start": s.start, "end": s.end, "text": s.text.strip()} for s in raw_segments]
    elapsed = time.monotonic() - t0
    logger.info(
        "Worker PID %d done in %.2fs: %d segments, duration=%.2fs",
        os.getpid(),
        elapsed,
        len(segments),
        info.duration,
    )
    return {"segments": segments, "duration": info.duration}


class WhisperService:
    """Manages a pool of worker processes for parallel Whisper transcription.

    Workers are spawned on demand (up to MAX_WORKERS) and automatically
    shut down after IDLE_TIMEOUT_SECONDS of inactivity to free memory.
    """

    def __init__(self) -> None:
        self._pool: ProcessPoolExecutor | None = None
        self._last_used: float = 0.0
        self._lock = threading.Lock()
        logger.info(
            "WhisperService initialized: max_workers=%d, idle_timeout=%ds",
            MAX_WORKERS,
            IDLE_TIMEOUT_SECONDS,
        )

    def _get_pool(self) -> ProcessPoolExecutor:
        """Get or create the process pool. Must be called while holding _lock."""
        if self._pool is None:
            self._pool = ProcessPoolExecutor(max_workers=MAX_WORKERS)
            logger.info("Created process pool with max_workers=%d", MAX_WORKERS)
        self._last_used = time.monotonic()
        return self._pool

    def shutdown_if_idle(self) -> None:
        """Shut down the pool if no work has been submitted recently."""
        with self._lock:
            if self._pool is None:
                return
            idle_seconds = time.monotonic() - self._last_used
            if idle_seconds > IDLE_TIMEOUT_SECONDS:
                logger.info("Shutting down idle worker pool (idle %.0fs)", idle_seconds)
                self._pool.shutdown(wait=False)
                self._pool = None

    def transcribe(self, audio_path: str, model: str | None = None) -> TranscriptionResult:
        """Submit transcription to the process pool and wait for the result."""
        model_name = model or settings.whisper_model
        logger.info("Submitting transcription of '%s' (model=%s) to pool", audio_path, model_name)

        with self._lock:
            pool = self._get_pool()
            future = pool.submit(
                _transcribe_in_worker,
                audio_path,
                model_name,
                settings.model_dir,
                settings.whisper_compute_type,
            )

        result = future.result()  # blocks outside lock
        self._last_used = time.monotonic()

        return TranscriptionResult(
            segments=[Segment(**s) for s in result["segments"]],
            duration=result["duration"],
        )


# Module-level singleton
whisper_service = WhisperService()
