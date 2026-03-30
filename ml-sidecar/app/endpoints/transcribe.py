import logging
import os
import tempfile

from fastapi import APIRouter, HTTPException, UploadFile, File
from fastapi import Query

from app.services.whisper_service import TranscriptionResult, whisper_service

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post("/transcribe", response_model=TranscriptionResult)
async def transcribe(
    audio_file: UploadFile = File(...),
    model: str | None = Query(default=None, description="Whisper model name to use (overrides default from config)"),
) -> TranscriptionResult:
    """Transcribe an uploaded audio file using Whisper.

    - **audio_file**: multipart audio file (wav, mp3, m4a, ogg, flac, …)
    - **model**: optional model override (e.g. ``large-v3-turbo``, ``base``)

    Returns segments with start/end timestamps and the total audio duration.
    """
    # Basic content-type guard — faster-whisper will fail on non-audio anyway,
    # but returning 422 early gives the caller a clearer signal.
    content_type = audio_file.content_type or ""
    if content_type and not (
        content_type.startswith("audio/")
        or content_type.startswith("video/")  # some containers (mp4) carry audio
        or content_type == "application/octet-stream"
    ):
        logger.warning(
            "Rejected upload with unsupported content-type '%s' (filename=%s)",
            content_type,
            audio_file.filename,
        )
        raise HTTPException(
            status_code=422,
            detail=f"Unsupported content-type '{content_type}'. Supply an audio file.",
        )

    # Derive a suffix from the original filename so faster-whisper can detect
    # the container format correctly.
    original_name = audio_file.filename or "audio"
    suffix = os.path.splitext(original_name)[1] or ".bin"

    tmp_path: str | None = None
    try:
        # Write upload to a named temp file (deleted in the finally block).
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            tmp_path = tmp.name
            contents = await audio_file.read()
            if not contents:
                raise HTTPException(status_code=422, detail="Uploaded audio file is empty.")
            tmp.write(contents)

        logger.info(
            "Saved upload '%s' (%d bytes) to temp path '%s'",
            original_name,
            len(contents),
            tmp_path,
        )

        result = whisper_service.transcribe(tmp_path, model=model)
        return result

    except HTTPException:
        raise
    except Exception as exc:
        logger.exception(
            "Transcription failed for upload '%s': %s", original_name, exc
        )
        raise HTTPException(
            status_code=500,
            detail="Transcription failed. Check server logs for details.",
        ) from exc
    finally:
        if tmp_path and os.path.exists(tmp_path):
            try:
                os.unlink(tmp_path)
                logger.debug("Removed temp file '%s'", tmp_path)
            except OSError as cleanup_err:
                logger.warning("Could not remove temp file '%s': %s", tmp_path, cleanup_err)
