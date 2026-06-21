# Intent Service — Intent Inference (Python / FastAPI)

Receives a (often noisy) speech-to-text transcript and uses the Claude API to
infer what the user meant and return a single `plan`: a first-person,
plain-language narration of the Android UI steps it will take to achieve that
intent ("I'll open the Clock app, go to the Alarm tab...").

That one string serves both consumers as-is — it is spoken back to the user via
TTS, and fed to the [mobilerun](https://github.com) framework, which turns the
on-screen UI into data and follows natural-language steps to control it. Because
both want natural language, the response is plain text — no structured schema.

## Setup

```bash
cd backend/intent_service
python -m venv .venv && source .venv/Scripts/activate   # Windows Git Bash
# (PowerShell: .venv\Scripts\Activate.ps1)
pip install -r requirements.txt
cp .env.example .env          # then put your real ANTHROPIC_API_KEY in .env
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

`.env` is gitignored, so the key stays out of version control.

## Endpoint

### `POST /infer`

Request:
```json
{
  "text": "uh can you set a alarm for ate am tomorrow",
  "context": "home screen"          // optional
}
```

Response:
```json
{
  "plan": "I'll open the Clock app, go to the Alarm tab, add a new alarm set to 8:00 AM, and save it."
}
```

`GET /health` → `{"status": "ok"}`

## Try it

```bash
curl -s http://localhost:8000/infer \
  -H "content-type: application/json" \
  -d '{"text":"wuts the wether like rn"}' | python -m json.tool
```

Interactive docs (Swagger UI) are at `http://localhost:8000/docs`.

## How the model is configured

| Setting | Value | Why |
|---|---|---|
| Model | `claude-opus-4-8` | Most capable Opus-tier model |
| Thinking | adaptive | Claude decides how much to reason per input |
| Effort | `low` | This is a real-time voice loop — latency matters |
| Output | plain text | Both consumers (TTS + mobilerun) want natural language |

The system prompt (in `intent.py`) is the contract that tells Claude its input is
noisy STT output and that the response must be a first-person, plain-language
narration of the Android UI steps it will take (no markdown, no code, no
ADB/developer commands).

## Calling it from the Android app

After Deepgram returns the final transcript, POST it here and use the `plan`:

- speak it to the user via TTS
- feed it to mobilerun to drive the device

## Files

| Path | Role |
|------|------|
| `main.py` | App wiring: creates the FastAPI app, CORS, mounts `api_router` |
| `api/` | Route layer — `infer.py`, `health.py`, `schemas.py`, aggregated in `__init__.py` |
| `intent.py` | System prompt and the Claude call |
| `requirements.txt` | Dependencies |
| `.env.example` | Template for the `ANTHROPIC_API_KEY` secret |
