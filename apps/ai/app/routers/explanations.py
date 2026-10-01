from typing import Literal

from fastapi import APIRouter
from pydantic import BaseModel, ConfigDict, Field, field_validator


router = APIRouter(
    prefix="/internal/explanations",
    tags=["explanations"],
)

class ExplainRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    code: str = Field(min_length=1, max_length=20_000)
    language: str = Field(default="text", min_length=1, max_length=40)

    @field_validator("code", "language")
    @classmethod
    def reject_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("must not be blank")
        return value

class ExplainResponse(BaseModel):
    mode: Literal["mock"] = "mock"
    explanation: str

@router.post("", response_model=ExplainResponse)
def explain_code(request: ExplainRequest) -> ExplainResponse:
    return ExplainResponse(
        explanation=(
            f"Mock response: received {request.language} code. "
            "No AI model has been called."
        )
    )
