from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health():
    assert client.get("/health").json() == {"status": "UP"}


def test_hello():
    assert client.get("/", params={"name": "Ada"}).json() == {"message": "Hello, Ada!"}
