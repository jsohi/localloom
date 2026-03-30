import pytest


@pytest.mark.asyncio
async def test_tts_returns_501(client):
    response = await client.post(
        "/tts",
        json={"text": "Hello world", "voice": "en_US-amy-medium"},
    )
    assert response.status_code == 501
    assert response.json()["message"] == "TTS not yet implemented"
