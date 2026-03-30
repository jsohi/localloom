from fastapi import APIRouter
from fastapi.responses import JSONResponse
from pydantic import BaseModel

router = APIRouter()


class TTSRequest(BaseModel):
    text: str
    voice: str = ""


@router.post("/tts")
async def tts(request: TTSRequest) -> JSONResponse:
    # TODO (APP-91): Integrate Piper TTS. Load the voice model from
    # settings.model_dir, synthesize audio from request.text using
    # request.voice (or fall back to settings.tts_voice), write the
    # output to settings.tts_output_dir, and return the file path or
    # stream the audio bytes directly.
    return JSONResponse(
        status_code=501,
        content={"message": "TTS not yet implemented"},
    )
