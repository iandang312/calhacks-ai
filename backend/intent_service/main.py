"""
FastAPI service that turns a noisy Deepgram speech-to-text transcript into a
first-person plan of Android UI steps.

Run locally:
    pip install -r requirements.txt
    cp .env.example .env   # then put your ANTHROPIC_API_KEY in it
    uvicorn main:app --reload --host 0.0.0.0 --port 8000

The Android app POSTs the final Deepgram transcript to /infer and gets back a
`plan` to speak via TTS and feed to the mobilerun UI framework. Routes live in the
`api` package; this module only wires the app together.
"""

import logging

from dotenv import load_dotenv

# Must run before importing `api` (which imports `intent`, building the Anthropic
# client at module load and reading ANTHROPIC_API_KEY from the environment).
load_dotenv()

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from api import api_router

logging.basicConfig(level=logging.INFO)

app = FastAPI(title="Intent Inference Service", version="0.1.0")

# Permissive CORS so the endpoint is easy to hit from a browser or web test page.
# (Native Android clients don't enforce CORS, so this only matters for web callers.)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(api_router)
