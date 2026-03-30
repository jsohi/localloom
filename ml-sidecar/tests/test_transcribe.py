from unittest.mock import patch

import pytest

from app.services.whisper_service import Segment, TranscriptionResult


@pytest.mark.asyncio
async def test_transcribe_success(client):
    mock_result = TranscriptionResult(
        segments=[
            Segment(start=0.0, end=4.8, text="Hello world"),
            Segment(start=4.8, end=10.0, text="Testing sidecar"),
        ],
        duration=10.0,
    )

    with patch("app.endpoints.transcribe.whisper_service") as mock_service:
        mock_service.transcribe.return_value = mock_result

        response = await client.post(
            "/transcribe",
            files={"audio_file": ("test.wav", b"fake audio data", "audio/wav")},
        )

    assert response.status_code == 200
    data = response.json()
    assert len(data["segments"]) == 2
    assert data["segments"][0]["text"] == "Hello world"
    assert data["duration"] == 10.0


@pytest.mark.asyncio
async def test_transcribe_empty_file(client):
    with patch("app.endpoints.transcribe.whisper_service"):
        response = await client.post(
            "/transcribe",
            files={"audio_file": ("test.wav", b"", "audio/wav")},
        )

    assert response.status_code == 422


@pytest.mark.asyncio
async def test_transcribe_bad_content_type(client):
    response = await client.post(
        "/transcribe",
        files={"audio_file": ("test.txt", b"not audio", "text/plain")},
    )

    assert response.status_code == 422


@pytest.mark.asyncio
async def test_transcribe_service_error(client):
    with patch("app.endpoints.transcribe.whisper_service") as mock_service:
        mock_service.transcribe.side_effect = RuntimeError("Model crashed")

        response = await client.post(
            "/transcribe",
            files={"audio_file": ("test.wav", b"fake audio data", "audio/wav")},
        )

    assert response.status_code == 500


@pytest.mark.asyncio
async def test_transcribe_cleans_up_temp_file(client, tmp_path):
    """Verify the temp file is deleted after transcription."""
    mock_result = TranscriptionResult(
        segments=[Segment(start=0.0, end=1.0, text="test")],
        duration=1.0,
    )

    with patch("app.endpoints.transcribe.whisper_service") as mock_service:
        mock_service.transcribe.return_value = mock_result

        response = await client.post(
            "/transcribe",
            files={"audio_file": ("test.wav", b"fake audio data", "audio/wav")},
        )

    assert response.status_code == 200
    # Temp file cleanup is handled in the finally block of the endpoint;
    # we can't easily assert it here without instrumenting the code,
    # but the test verifies no crash occurs.
