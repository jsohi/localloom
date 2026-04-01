import re

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_generates_request_id_when_missing():
    response = client.get("/health")
    assert response.status_code == 200
    request_id = response.headers.get("X-Request-Id")
    assert request_id is not None
    assert len(request_id) == 8
    assert re.match(r"^[a-f0-9]+$", request_id)


def test_passes_valid_request_id_through():
    response = client.get("/health", headers={"X-Request-Id": "abc12345"})
    assert response.headers["X-Request-Id"] == "abc12345"


def test_rejects_request_id_too_long():
    response = client.get("/health", headers={"X-Request-Id": "a" * 37})
    request_id = response.headers["X-Request-Id"]
    assert len(request_id) == 8
    assert request_id != "a" * 8


def test_rejects_request_id_with_invalid_chars():
    response = client.get("/health", headers={"X-Request-Id": "evil\ninjection"})
    request_id = response.headers["X-Request-Id"]
    assert len(request_id) == 8
    assert re.match(r"^[a-f0-9]+$", request_id)


def test_accepts_uuid_format():
    uuid_id = "550e8400-e29b-41d4-a716-446655440000"
    response = client.get("/health", headers={"X-Request-Id": uuid_id})
    assert response.headers["X-Request-Id"] == uuid_id
