from unittest.mock import patch

import pytest


@pytest.mark.asyncio
async def test_tts_success(client):
    fake_wav = b"RIFF\x00\x00\x00\x00WAVEfmt "

    with patch("app.endpoints.tts.tts_service") as mock_service:
        mock_service.synthesize.return_value = fake_wav

        response = await client.post(
            "/tts",
            json={"text": "Hello world"},
        )

    assert response.status_code == 200
    assert response.headers["content-type"] == "audio/wav"
    assert response.content == fake_wav
    mock_service.synthesize.assert_called_once_with("Hello world", voice=None)


@pytest.mark.asyncio
async def test_tts_with_custom_voice(client):
    fake_wav = b"RIFF\x00\x00\x00\x00WAVEfmt "

    with patch("app.endpoints.tts.tts_service") as mock_service:
        mock_service.synthesize.return_value = fake_wav

        response = await client.post(
            "/tts",
            json={"text": "Hello world", "voice": "en_US-lessac-medium"},
        )

    assert response.status_code == 200
    mock_service.synthesize.assert_called_once_with("Hello world", voice="en_US-lessac-medium")


@pytest.mark.asyncio
async def test_tts_empty_text(client):
    response = await client.post(
        "/tts",
        json={"text": "   "},
    )

    assert response.status_code == 422


@pytest.mark.asyncio
async def test_tts_missing_text(client):
    response = await client.post(
        "/tts",
        json={},
    )

    assert response.status_code == 422


@pytest.mark.asyncio
async def test_tts_service_error(client):
    with patch("app.endpoints.tts.tts_service") as mock_service:
        mock_service.synthesize.side_effect = RuntimeError("Voice model not found")

        response = await client.post(
            "/tts",
            json={"text": "Hello world"},
        )

    assert response.status_code == 500
