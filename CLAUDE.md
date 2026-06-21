# calhacks-ai — Android UI Agent (MVP)

Base agent that drives a Samsung Android emulator via `uiautomator2`,
orchestrated with `agentspan`. Action set: tap, tap_text, long_press, swipe,
drag, type_text, press_key, open_app, dump_ui, screenshot, finish.

## Prerequisites

1. Android Studio + a running AVD (Samsung-style system image). See
   <https://developer.android.com/studio>.
2. `adb devices` shows the emulator.
3. `python -m uiautomator2 init` (one-time per device — installs atx-agent).
4. Python 3.11+, `pip install -r requirements.txt`.
5. `cp .env.example .env` and set `ANTHROPIC_API_KEY` or `OPENAI_API_KEY`.

## Run

CLI (local):
```
python -m agent.run "open the settings app"
```

HTTP service (for the Java side to call):
```
uvicorn agent.server:app --host 0.0.0.0 --port 8000
# POST http://localhost:8000/agent/run  {"task": "open the settings app"}
# GET  http://localhost:8000/health
```

## Test

```
RUN_DEVICE_TESTS=1 pytest -q
```

Device tests are integration-only; they hit the live emulator (no mocks).
Skipped unless the env flag is set.

## Module map

- `env/device.py` — `Device` class wrapping `uiautomator2`. Reusable.
- `agent/tools.py` — JSON-schema tool definitions + `(device, **args) -> str`
  handlers. `finish` is a sentinel.
- `agent/prompt.md` — system prompt: loop contract, do/don't, two few-shots.
- `agent/node.py` — `build_graph(device, max_steps, model_call)` returns a
  `run(task) -> Trajectory`. `model_call` is the seam for the agentspan Agent
  node — wire it to the SDK when integrating the LLM.
- `agent/run.py` — CLI entrypoint.
- `agent/server.py` — FastAPI wrapper. `POST /agent/run` and `GET /health`.
  This is the boundary the Java side calls.

## Architecture decision: Python service, Java client

The Deepgram transcription and simulation/orchestration logic live in Java.
The agent loop lives in Python because `uiautomator2` (and the agentspan
Python SDK) are the most mature options for driving an Android device.

Rather than rewrite either side, the two languages are split across an
HTTP boundary:

- **Java owns the system flow**: speech → text (Deepgram), task framing,
  multi-step orchestration, user-facing UI.
- **Python owns one narrow capability**: "given a task string, drive the
  emulator and return a trajectory." Exposed as a single FastAPI service
  (`agent/server.py`).

Why HTTP and not an in-process bridge (JPype/Py4J):
- Decouples deployment — each side restarts independently.
- Debuggable in isolation (curl the endpoint, run the Java side without
  the agent up).
- Cross-call rate is low (one call per user task, not per UI action), so
  HTTP latency is irrelevant.

If finer-grained control is needed later (Java wants to send individual
actions instead of full tasks), expand the HTTP surface — do not collapse
the boundary.

## Conventions

- All device actions go through `Device` — never call `uiautomator2` directly
  from agent code.
- New tools: add schema + handler in `agent/tools.py`, wire any new device
  primitive into `env/device.py` first.
- Step cap defaults to 25; repeated identical tool calls abort after 3.
- Loop guarantees: one tool call per turn; `finish` terminates immediately.

## Known TODOs

- Wire `model_call` in `agent/run.py` to the agentspan Agent node (currently
  a stub that finishes immediately).
- Add vision/screenshot input to the model if XML perception proves
  insufficient.
