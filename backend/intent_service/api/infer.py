"""The /infer endpoint: a noisy Deepgram transcript -> a first-person UI plan."""

import logging

import anthropic
from fastapi import APIRouter, HTTPException

from intent import infer_intent

from .schemas import InferRequest, InferResponse

log = logging.getLogger("intent-service")

router = APIRouter()


@router.post("/infer", response_model=InferResponse)
async def infer(req: InferRequest) -> InferResponse:
    text = req.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="`text` must not be empty")

    log.info("infer request: text=%r context=%r", text, req.context)

    try:
        plan = await infer_intent(text, req.context)
    except anthropic.APIStatusError as e:
        # Surfaced API error (rate limit, auth, bad request, overload, ...).
        log.error("Anthropic API error: %s", e)
        raise HTTPException(status_code=502, detail=f"LLM error: {e.message}")
    except anthropic.APIConnectionError as e:
        log.error("Anthropic connection error: %s", e)
        raise HTTPException(status_code=503, detail="Could not reach the LLM service")
    except Exception as e:  # unexpected
        log.exception("Intent inference failed")
        raise HTTPException(status_code=500, detail=f"Inference failed: {e}")

    log.info("infer response: plan=%r", plan)
    return InferResponse(plan=plan)
