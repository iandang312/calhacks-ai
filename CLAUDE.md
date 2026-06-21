# calhacks-ai — Voice-Driven Android Assistant (MVP)

Agent that listens via microphone, clarifies intent, then drives a connected Android device via `uiautomator2`. Think Siri but agentic — no hardcoded flows, the agent navigates the real UI.

## Prerequisites

1. Connected Android device or running AVD (Samsung-style).
2. `adb devices` shows the device.
3. `python -m uiautomator2 init` (one-time per device — installs atx-agent).
4. Python 3.11+, `pip install -r requirements.txt`.
5. `cp .env.example .env` and fill in `ANTHROPIC_API_KEY` and `DEEPGRAM_API_KEY`.

## Run

Start the full voice loop (mic → STT → intent → agent → TTS):
```
python main.py
```

## Test

```
pytest -q
```

Device integration tests require a live device:
```
RUN_DEVICE_TESTS=1 pytest -q
```

## Architecture

```
Microphone
  │
  ▼
audio/stt.py          — Deepgram live STT (nova-2, Python mic capture)
  │  transcript
  ▼
backend/intent_service/intent.py  — Claude clarifies noisy STT → first-person plan
  │  plan
  ├─► services/tts.py             — speak plan back to user (Deepgram TTS)
  ▼
agent/anthropic_loop.py           — Claude tool-use loop drives the device
  │  per-turn screenshot + tool calls
  ▼
env/device.py                     — uiautomator2 Device wrapper
  │
  ▼
Android device (via ADB)
```

All orchestration lives in `main.py`. No HTTP servers, no subprocesses — one Python process.

## Module map

- `main.py` — entry point; `voice_loop(device)` runs the pipeline forever.
- `audio/stt.py` — `capture_speech() -> str`; `listen_once(callback)` for lower-level use.
- `services/tts.py` — `speak(text)` via Deepgram aura-2.
- `backend/intent_service/intent.py` — `async infer_intent(text) -> str`; called directly (no HTTP).
- `env/device.py` — `Device` class wrapping `uiautomator2`. All device actions go through here.
- `agent/tools.py` — tool schemas + handlers. New tools: add schema + handler here, wire primitive into `env/device.py` first.
- `agent/anthropic_loop.py` — `run_anthropic(device, system, task)` → `Trajectory`.
- `agent/prompt.md` — system prompt: loop contract, conversational mode, voice narration rules.

## Conversational mode

The agent handles both device-control requests ("open settings") and conversational requests ("what's the weather?"). For conversational requests, the agent calls `speak()` with its answer and finishes without touching the device — no special routing needed.

## Dev / isolation tools (not production)

- `agent/run.py` — CLI for testing a single task without microphone: `python -m agent.run "open settings"`
- `tests/manual_run.py` — isolated module tests; run individual components manually.

## Deprecated (do not use)

- `agent/server.py` — FastAPI HTTP server; replaced by direct function calls in `main.py`.
- `backend/intent_service/api/` and `backend/intent_service/main.py` — HTTP wrapper around intent service; removed. Use `intent.py` directly.
- `daisy/` and `frontend/` — Android Kotlin/Java code that owned Deepgram STT/TTS and device control. Replaced entirely by the Python pipeline above.
- `agent/node.py` — agentspan-based graph; replaced by `agent/anthropic_loop.py`.

## Conventions

- All device actions go through `Device` — never call `uiautomator2` directly from agent code.
- Step cap defaults to 25; agent aborts after 3 identical tool calls in a row.
- Model: `MODEL` env var, defaults to `claude-haiku-4-5`.
