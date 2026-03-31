import logging

from fastapi import APIRouter, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel

from app.services.tts_service import tts_service

logger = logging.getLogger(__name__)

router = APIRouter()


class TTSRequest(BaseModel):
    text: str
    voice: str | None = None


@router.post("/tts")
async def tts(request: TTSRequest) -> Response:
    """Synthesize speech from text using Piper TTS.

    - **text**: the text to synthesize
    - **voice**: optional Piper voice name (defaults to config ``tts_voice``)

    Returns WAV audio binary with ``audio/wav`` content type.
    """
    if not request.text.strip():
        raise HTTPException(status_code=422, detail="Text must not be empty.")

    if len(request.text) > 5000:
        raise HTTPException(
            status_code=422,
            detail="Text exceeds maximum length of 5000 characters.",
        )

    try:
        wav_bytes = tts_service.synthesize(request.text, voice=request.voice)
        return Response(content=wav_bytes, media_type="audio/wav")
    except Exception as exc:
        logger.exception("TTS synthesis failed: %s", exc)
        raise HTTPException(
            status_code=500,
            detail="TTS synthesis failed. Check server logs for details.",
        ) from exc
