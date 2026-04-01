import atexit
import logging
import logging.config
import logging.handlers
import os
import queue
from contextvars import ContextVar
from datetime import UTC, datetime

request_id_var: ContextVar[str] = ContextVar("request_id", default="-")

LOG_DIR = os.environ.get("LOG_DIR", "logs")

# Python uses WARNING (7 chars) but Java uses WARN (4 chars).
# Override so cross-service log format aligns on 5-char columns.
logging.addLevelName(logging.WARNING, "WARN")


class RequestIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = request_id_var.get("-")
        return True


class UtcMillisFormatter(logging.Formatter):
    """Match the Java API's Log4j2 ISO-8601 UTC format for cross-service log correlation."""

    def formatTime(self, record: logging.LogRecord, datefmt: str | None = None) -> str:
        dt = datetime.fromtimestamp(record.created, tz=UTC)
        return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{int(record.msecs):03d}Z"


def configure_logging() -> None:
    os.makedirs(LOG_DIR, exist_ok=True)

    config = {
        "version": 1,
        "disable_existing_loggers": False,
        "filters": {
            "request_id": {"()": RequestIdFilter},
        },
        "formatters": {
            "standard": {
                "()": UtcMillisFormatter,
                "format": (
                    "%(asctime)s [ml-sidecar] %(levelname)-5s"
                    " [%(request_id)s] %(name)s - %(message)s"
                ),
            },
        },
        "handlers": {
            "console": {
                "class": "logging.StreamHandler",
                "formatter": "standard",
                "filters": ["request_id"],
                "stream": "ext://sys.stdout",
            },
            "file": {
                "class": "logging.handlers.RotatingFileHandler",
                "formatter": "standard",
                "filters": ["request_id"],
                "filename": os.path.join(LOG_DIR, "ml-sidecar.log"),
                "maxBytes": 50 * 1024 * 1024,
                "backupCount": 7,
                "encoding": "utf-8",
            },
        },
        "loggers": {
            "app": {
                "level": "DEBUG",
                "handlers": ["console", "file"],
                "propagate": False,
            },
            "uvicorn": {
                "level": "INFO",
                "handlers": ["console", "file"],
                "propagate": False,
            },
            "uvicorn.access": {
                "level": "INFO",
                "handlers": ["console", "file"],
                "propagate": False,
            },
        },
        "root": {
            "level": "WARNING",
            "handlers": ["console", "file"],
        },
    }

    logging.config.dictConfig(config)

    # Offload file writes to a background thread so disk I/O doesn't block the event loop
    file_handler = next(
        (
            h
            for h in logging.getLogger("app").handlers
            if isinstance(h, logging.handlers.RotatingFileHandler)
        ),
        None,
    )
    if file_handler is not None:
        log_queue: queue.Queue[logging.LogRecord] = queue.Queue(10_000)
        queue_handler = logging.handlers.QueueHandler(log_queue)
        listener = logging.handlers.QueueListener(
            log_queue, file_handler, respect_handler_level=True
        )
        listener.start()
        atexit.register(listener.stop)

        for logger_name in ("app", "uvicorn", "uvicorn.access", ""):
            lgr = logging.getLogger(logger_name)
            lgr.removeHandler(file_handler)
            lgr.addHandler(queue_handler)
