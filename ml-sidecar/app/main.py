import logging
import re
import uuid
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from starlette.middleware.base import BaseHTTPMiddleware

from app.config import settings
from app.endpoints import health, transcribe, tts
from app.logging_config import configure_logging, request_id_var

configure_logging()

logger = logging.getLogger(__name__)


_VALID_ID = re.compile(r"^[a-zA-Z0-9\-]{1,36}$")


class RequestIdMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next) -> Response:
        raw_id = request.headers.get("X-Request-Id", "")
        request_id = raw_id if _VALID_ID.match(raw_id) else uuid.uuid4().hex[:8]
        token = request_id_var.set(request_id)
        try:
            response = await call_next(request)
            response.headers["X-Request-Id"] = request_id
            return response
        finally:
            request_id_var.reset(token)


@asynccontextmanager
async def lifespan(app: FastAPI):
    import asyncio

    from app.services.whisper_service import whisper_service

    logger.info("LocalLoom ML Sidecar starting up on %s:%d", settings.host, settings.port)

    # Background task to shut down idle Whisper worker processes
    async def idle_checker():
        while True:
            await asyncio.sleep(30)
            whisper_service.shutdown_if_idle()

    task = asyncio.create_task(idle_checker())
    yield
    task.cancel()
    whisper_service.shutdown_if_idle()
    logger.info("LocalLoom ML Sidecar shutting down")


app = FastAPI(title="LocalLoom ML Sidecar", lifespan=lifespan)

app.add_middleware(RequestIdMiddleware)

app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:8080",
        "http://localhost:3000",
        "http://api:8080",
        "http://frontend:3000",
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(health.router)
app.include_router(transcribe.router)
app.include_router(tts.router)


if __name__ == "__main__":
    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=False,
        log_config=None,
    )
