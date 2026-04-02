import logging

from fastapi import APIRouter
from fastapi.responses import PlainTextResponse
from prometheus_client import (
    Counter,
    Gauge,
    Histogram,
    generate_latest,
)

logger = logging.getLogger(__name__)

router = APIRouter()

# Metrics
transcriptions_total = Counter(
    "localloom_sidecar_transcriptions_total",
    "Total transcriptions completed",
)
transcription_duration = Histogram(
    "localloom_sidecar_transcription_duration_seconds",
    "Transcription duration in seconds",
    buckets=[10, 30, 60, 120, 300, 600, 1200],
)
tts_duration = Histogram(
    "localloom_sidecar_tts_duration_seconds",
    "TTS synthesis duration in seconds",
    buckets=[0.5, 1, 2, 5, 10, 30],
)
whisper_workers_active = Gauge(
    "localloom_sidecar_whisper_workers_active",
    "Number of active Whisper worker processes",
)


@router.get("/metrics", response_class=PlainTextResponse)
async def metrics() -> str:
    """Prometheus metrics endpoint."""
    return generate_latest().decode("utf-8")
