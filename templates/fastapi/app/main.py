from fastapi import FastAPI

app = FastAPI(title="My API")


@app.get("/health")
def health() -> dict:
    return {"status": "UP"}


@app.get("/")
def hello(name: str = "world") -> dict:
    return {"message": f"Hello, {name}!"}
