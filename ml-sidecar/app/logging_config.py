import atexit
import logging
import logging.config
import logging.handlers
import os
import queue
from contextvars import ContextVar
from datetime import datetime
from zoneinfo import ZoneInfo

request_id_var: ContextVar[str] = ContextVar("request_id", default="-")

LOG_DIR = os.environ.get("LOG_DIR", "logs")

# Python uses WARNING (7 chars) but Java uses WARN (4 chars).
# Override so cross-service log format aligns on 5-char columns.
logging.addLevelName(logging.WARNING, "WARN")


class RequestIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = request_id_var.get("-")
        return True


class NycMillisFormatter(logging.Formatter):
    """Format timestamps in America/New_York timezone for cross-service log correlation."""

    _TZ = ZoneInfo("America/New_York")

    def formatTime(self, record: logging.LogRecord, datefmt: str | None = None) -> str:
        dt = datetime.fromtimestamp(record.created, tz=self._TZ)
        offset = dt.strftime("%z")
        offset_fmt = offset[:3] + ":" + offset[3:]  # +00:00 format
        return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{int(record.msecs):03d}" + offset_fmt


def _rotate_on_startup() -> None:
    """Roll the previous log file into a gzipped archive on process start."""
    import glob
    import gzip
    import shutil
    import time

    log_path = os.path.join(LOG_DIR, "ml-sidecar.log")

    # Roll current log to timestamped gz archive
    if os.path.exists(log_path) and os.path.getsize(log_path) > 0:
        ts = datetime.now(ZoneInfo("America/New_York")).strftime("%Y%m%d-%H%M%S")
        gz_path = os.path.join(LOG_DIR, f"ml-sidecar-{ts}.log.gz")
        with open(log_path, "rb") as f_in, gzip.open(gz_path, "wb") as f_out:
            shutil.copyfileobj(f_in, f_out)
        os.truncate(log_path, 0)

    # Delete archives older than 10 days
    cutoff = time.time() - 10 * 86400
    for gz_file in glob.glob(os.path.join(LOG_DIR, "ml-sidecar-*.log.gz")):
        if os.path.getmtime(gz_file) < cutoff:
            os.remove(gz_file)


def configure_logging() -> None:
    os.makedirs(LOG_DIR, exist_ok=True)
    # Only rotate in the main process — workers inherit the rotated state
    if os.environ.get("_LOCALLOOM_LOG_ROTATED") != "1":
        _rotate_on_startup()
        os.environ["_LOCALLOOM_LOG_ROTATED"] = "1"

    config = {
        "version": 1,
        "disable_existing_loggers": False,
        "filters": {
            "request_id": {"()": RequestIdFilter},
        },
        "formatters": {
            "standard": {
                "()": NycMillisFormatter,
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
