# EchoMind 🎙️

### Voice Intent Engine for People with Speech Disorders

> Siri understands your words. We understand *you* — even when the words don't come out right.

[![Deepgram](https://img.shields.io/badge/Deepgram-STT%2FTTS-blue)]()
[![Claude](https://img.shields.io/badge/Anthropic-Claude%20API-orange)]()
[![Agent S](https://img.shields.io/badge/Simular-Agent%20S-purple)]()

---

## The Problem

Most voice assistants are built for fluent, structured speech.

For many people with speech disorders, dementia, aphasia, stutters, or other communication-related conditions, speech can include repeated sounds, interrupted phrases, incomplete sentences, or unclear wording.

For example:

> "I w-w-wan-t a bur-bur-ger"

A traditional assistant may struggle to understand this reliably.

EchoMind is designed to recover the user's true intent from fragmented or disfluent speech and turn it into a clear, actionable plan.

---

## Our Solution

EchoMind is an accessibility-focused Android AI agent layer that listens after a wake word, reconstructs the user's intended request, explains the plan back to them, and then carries out the action inside a real Android app.

Instead of forcing users to speak like machines, EchoMind adapts to how the user naturally communicates.

---

## How It Works

1. The user says a wake word.
2. EchoMind begins listening.
3. Deepgram transcribes the user's speech.
4. Claude interprets the transcript and reconstructs the user's true intent.
5. EchoMind explains the interpreted intent and planned action back to the user.
6. The user confirms the action.
7. Agent S and the Android Accessibility API execute the task inside a real Android app.

---

## Example

User says:

> "I w-w-wan-t a bur-bur-ger"

EchoMind interprets:

> "The user wants to order a burger."

EchoMind responds:

> "I think you want to order a burger. I will open the app, search for burger options, and ask you to confirm before placing anything."

Then the Android agent begins executing the task.

---

## Architecture

```text
Wake Word
   ↓
Voice Input
   ↓
Deepgram Speech-to-Text
   ↓
Claude Intent Reconstruction
   ↓
Intent + Plan Generation
   ↓
User Confirmation
   ↓
Agent S
   ↓
Android Accessibility API
   ↓
Real Android App Action


## Setup

### 1. Create a virtual environment

From the repo root:

```bash
python3 -m venv .venv
source .venv/bin/activate          # macOS / Linux
# .venv\Scripts\activate           # Windows PowerShell
```

You should see `(.venv)` in your prompt. Every command below assumes the
venv is active.

### 2. Install packages

```bash
pip install --upgrade pip
pip install -r requirements.txt
```

Verify:

```bash
python -c "import agentspan, uiautomator2, fastapi, dotenv; print('ok')"
```

### 3. Configure environment variables

```bash
cp .env.example .env
```

Edit `.env` and fill in:

- `GEMINI_API_KEY=...`
- `GOOGLE_CLOUD_PROJECT=...`

`.env` is gitignored.

### 4. Prepare the Android emulator (one-time per device)

1. Launch a Samsung-style AVD from Android Studio
   (<https://developer.android.com/studio>).
2. Confirm `adb` sees it:
   ```bash
   adb devices
   ```
3. Push the on-device `atx-agent` that `uiautomator2` needs:
   ```bash
   python -m uiautomator2 init
   ```

### 5. Start the agentspan server

The agent runtime requires the agentspan server running locally. This script
loads credentials from `.env` into the server's store and starts it:

```bash
# First time only — make the script executable
chmod +x scripts/start_server.sh

# Every time — run from the repo root in a dedicated terminal
./scripts/start_server.sh
```

The server starts on `http://localhost:6767`. Keep this terminal open — the
server must stay running whenever you use the agent or run LLM tests.

## Run

Three processes need to run in separate terminals. Open them in order:

**Terminal 1 — agentspan server** (keep running):
```bash
source .venv/bin/activate
./scripts/start_server.sh
```

**Terminal 2 — Python HTTP service** (for the Java side, keep running):
```bash
source .venv/bin/activate
uvicorn agent.server:app --host 0.0.0.0 --port 8000
```

**Terminal 3 — send a task** (one-shot CLI or tests):
```bash
source .venv/bin/activate

# Single task via CLI
python -m agent.run "open the settings app"

# Or POST to the HTTP service directly
curl -X POST http://localhost:8000/agent/run \
     -H "Content-Type: application/json" \
     -d '{"task": "open the settings app"}'
```

## Test

**Terminal 3** (agentspan server must be running in Terminal 1):

Unit tests — no emulator, no LLM:
```bash
pytest tests/test_agent_loop.py -q
```

LLM integration tests — agentspan server required:
```bash
RUN_LLM_TESTS=1 pytest tests/test_agentspan_node.py -v
```

Device integration tests — emulator required:
```bash
RUN_DEVICE_TESTS=1 pytest tests/test_actions.py -q
```
