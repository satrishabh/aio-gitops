# FastAPI starter (AIO)

Python 3.12 + FastAPI served by uvicorn on port 8080.

| | |
|---|---|
| Run locally | `pip install -r requirements.txt -r requirements-dev.txt && uvicorn app.main:app --reload --port 8080` |
| Tests | `python -m pytest` (`tests/`) |
| Health | `GET /health` → `{"status":"UP"}` |
| Image | multi-stage `Dockerfile`: pytest stage, then Alpine runtime as uid 10001 |

Every push to the default branch is tested, built, scanned by Trivy and deployed to **dev** by the AIO platform.
