FROM python:3.11-slim

RUN pip install --no-cache-dir uv

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
