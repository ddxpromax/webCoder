from fastapi import FastAPI

from app.routers.explanations import router as explanations_router


app = FastAPI(
    title="WebCoder AI Service",
    version="0.1.0",
)

app.include_router(explanations_router)

@app.get("/health")
def health() -> dict[str, str]:
    return {
        "status": "ok",
        "service": "webcoder-ai",
    }
