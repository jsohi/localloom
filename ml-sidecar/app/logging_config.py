import logging
import logging.config
import logging.handlers
import os
import queue
from contextvars import ContextVar
from datetime import datetime, timezone

request_id_var: ContextVar[str] = ContextVar("request_id", default="-")

LOG_DIR = os.environ.get("LOG_DIR", "logs")


class RequestIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = request_id_var.get("-")
        return True


class UtcMillisFormatter(logging.Formatter):
    """Match the Java API's Log4j2 ISO-8601 UTC format for cross-service log correlation."""

    def formatTime(self, record: logging.LogRecord, datefmt: str | None = None) -> str:
        dt = datetime.fromtimestamp(record.created, tz=timezone.utc)
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
                "format": "%(asctime)s [ml-sidecar] %(levelname)-5s [%(request_id)s] %(name)s - %(message)s",
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

    # Wrap the file handler in a QueueHandler so disk I/O doesn't block the event loop
    file_handler = _find_file_handler()
    if file_handler is not None:
        log_queue: queue.Queue[logging.LogRecord] = queue.Queue(-1)
        queue_handler = logging.handlers.QueueHandler(log_queue)
        queue_handler.addFilter(RequestIdFilter())
        listener = logging.handlers.QueueListener(log_queue, file_handler, respect_handler_level=True)
        listener.start()

        for logger_name in ("app", "uvicorn", "uvicorn.access", ""):
            lgr = logging.getLogger(logger_name)
            lgr.removeHandler(file_handler)
            lgr.addHandler(queue_handler)


def _find_file_handler() -> logging.handlers.RotatingFileHandler | None:
    """Find the RotatingFileHandler across all loggers."""
    for lgr_name in ("app", "uvicorn", "uvicorn.access", ""):
        for handler in logging.getLogger(lgr_name).handlers:
            if isinstance(handler, logging.handlers.RotatingFileHandler):
                return handler
    return None
