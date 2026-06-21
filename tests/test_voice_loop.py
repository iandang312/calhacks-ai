"""Voice loop integration tests — public interface only."""
from unittest.mock import MagicMock, patch

import pytest

from main import voice_loop


# ── 1. Full pipeline: transcript → intent → agent → speak ─────────────────────

@pytest.mark.asyncio
async def test_voice_loop_runs_full_pipeline():
    """One iteration: capture_speech → infer_intent → run_anthropic → speak."""
    speak_calls: list[str] = []
    mock_traj = MagicMock()
    mock_traj.note = "Alarm set successfully."

    async def mock_capture():
        return "set an alarm for 8am"

    async def mock_intent(text, context=None):
        return "I'll open the Clock app and set an alarm for 8 AM."

    with patch("main.capture_speech", mock_capture), \
         patch("main.infer_intent", mock_intent), \
         patch("main.run_anthropic", return_value=mock_traj), \
         patch("main.speak", side_effect=lambda t: speak_calls.append(t)):
        await voice_loop(MagicMock(), max_iterations=1)

    assert any("Clock app" in c for c in speak_calls), "plan was not spoken"
    assert "Alarm set successfully." in speak_calls, "result note was not spoken"


# ── 2. Empty transcript: intent and agent are never called ────────────────────

@pytest.mark.asyncio
async def test_voice_loop_skips_empty_transcript():
    """Empty transcript must not invoke intent service or agent."""
    intent_calls: list[str] = []
    agent_calls: list = []

    async def mock_capture():
        return ""

    async def mock_intent(text, context=None):
        intent_calls.append(text)
        return "some plan"

    mock_traj = MagicMock()
    mock_traj.note = ""

    with patch("main.capture_speech", mock_capture), \
         patch("main.infer_intent", mock_intent), \
         patch("main.run_anthropic", side_effect=lambda *a, **k: agent_calls.append(a) or mock_traj), \
         patch("main.speak"):
        await voice_loop(MagicMock(), max_iterations=1)

    assert intent_calls == [], "infer_intent called on empty transcript"
    assert agent_calls == [], "run_anthropic called on empty transcript"


# ── 3. Loop auto-restarts: second transcript processed after first ─────────────

@pytest.mark.asyncio
async def test_voice_loop_processes_multiple_iterations():
    """Two iterations each with a valid transcript → agent called twice."""
    agent_call_count = 0
    transcripts = ["open settings", "open chrome"]
    call_index = 0

    async def mock_capture():
        nonlocal call_index
        t = transcripts[call_index % len(transcripts)]
        call_index += 1
        return t

    async def mock_intent(text, context=None):
        return f"I'll {text}."

    mock_traj = MagicMock()
    mock_traj.note = ""

    def mock_agent(*args, **kwargs):
        nonlocal agent_call_count
        agent_call_count += 1
        return mock_traj

    with patch("main.capture_speech", mock_capture), \
         patch("main.infer_intent", mock_intent), \
         patch("main.run_anthropic", side_effect=mock_agent), \
         patch("main.speak"):
        await voice_loop(MagicMock(), max_iterations=2)

    assert agent_call_count == 2
