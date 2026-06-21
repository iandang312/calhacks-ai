"""Pydantic request/response models for the API."""

from pydantic import BaseModel, Field


class InferRequest(BaseModel):
    text: str = Field(..., description="Raw Deepgram STT transcript from the client.")
    context: str | None = Field(
        None,
        description="Optional hint about the user's situation (e.g. the current "
        "screen) to help disambiguate intent.",
    )


class InferResponse(BaseModel):
    plan: str = Field(
        ...,
        description="First-person plan of the Android UI steps the assistant will "
        "take. Spoken to the user via TTS and fed to the mobilerun UI framework.",
    )
