FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* &&
    pip install --no-cache-dir uv

WORKDIR /app

COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev

RUN groupadd -r appuser && useradd -r -g appuser appuser && \
    mkdir -p /app/data/models /app/data/tts_output && \
    chown -R appuser:appuser /app

COPY app app

USER appuser

EXPOSE 8100

CMD ["uv", "run", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8100"]
