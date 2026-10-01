import pytest
from fastapi.testclient import TestClient

from app.main import app


def test_explanation_is_explicitly_marked_as_mock():
    with TestClient(app) as client:
        response = client.post(
            "/internal/explanations",
            json={
                "code": "print(1)",
                "language": "python",
            },
        )

    assert response.status_code == 200
    assert response.json()["mode"] == "mock"
    assert "No AI model has been called." in response.json()["explanation"]

@pytest.mark.parametrize("code", ["", "    \n\t", "x" * 20_001])
def test_invalid_code_is_rejected(code: str):
    with TestClient(app) as client:
        response = client.post(
            "/internal/explanations",
            json={
                "code": code,
                "language": "python",
            },
        )

    assert response.status_code == 422
    assert any(
        error["loc"] == ["body", "code"]
        for error in response.json()["detail"]
    )

def test_explanation_stream_returns_sse_tokens_and_done_event():
    with TestClient(app) as client:
        response = client.post(
            "/internal/explanations/stream",
            json={
                "code": "print(1)",
                "language": "python",
            },
        )

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert "event: token" in response.text
    assert '"Mock ' in response.text
    assert "event: done" in response.text
    assert "data: [DONE]" in response.text
