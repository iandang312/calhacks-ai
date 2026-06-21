"""STT module tests — public interface only."""
import asyncio
from contextlib import asynccontextmanager
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from audio.stt import capture_speech
from deepgram.core.events import EventType


def _mock_result(transcript: str, is_final: bool) -> MagicMock:
    alt = MagicMock()
    alt.transcript = transcript
    result = MagicMock()
    result.is_final = is_final
    result.channel.alternatives = [alt]
    return result


def _make_socket(events_to_fire: list[tuple[object, MagicMock]]):
    """Return a mock AsyncV1SocketClient that fires given events on start_listening()."""
    handlers: dict = {}

    def on(event, handler):
        handlers.setdefault(event, []).append(handler)

    async def start_listening():
        for event_type, message in events_to_fire:
            for h in handlers.get(event_type, []):
                await h(message)

    socket = MagicMock()
    socket.on.side_effect = on
    socket.send_media = AsyncMock()
    socket.start_listening = start_listening
    return socket


def _patch_deepgram(socket):
    """Patch AsyncDeepgramClient so listen.v1.connect() yields the given socket."""
    @asynccontextmanager
    async def mock_connect(**kwargs):
        yield socket

    mock_v1 = MagicMock()
    mock_v1.connect = mock_connect
    mock_listen = MagicMock()
    mock_listen.v1 = mock_v1
    mock_client = MagicMock()
    mock_client.listen = mock_listen
    return patch("audio.stt.AsyncDeepgramClient", return_value=mock_client)


def _patch_sd():
    mock_stream = MagicMock()
    mock_stream.__enter__ = MagicMock(return_value=None)
    mock_stream.__exit__ = MagicMock(return_value=False)
    return patch("audio.stt.sd.InputStream", return_value=mock_stream)


# ── 1. Final transcript → returned ────────────────────────────────────────────

@pytest.mark.asyncio
async def test_capture_speech_returns_final_transcript():
    """capture_speech returns the transcript when Deepgram fires is_final=True."""
    socket = _make_socket([
        (EventType.MESSAGE, _mock_result("open settings", is_final=True)),
    ])

    with _patch_deepgram(socket), _patch_sd():
        result = await capture_speech()

    assert result == "open settings"


# ── 2. Partial-only stream → returns empty string ─────────────────────────────

@pytest.mark.asyncio
async def test_capture_speech_ignores_partial_transcripts():
    """Partials (is_final=False) are skipped; only a final result is returned."""
    socket = _make_socket([
        (EventType.MESSAGE, _mock_result("open", is_final=False)),
        (EventType.MESSAGE, _mock_result("open set", is_final=False)),
        # Deepgram sends a final with empty string at utterance end
        (EventType.MESSAGE, _mock_result("", is_final=True)),
    ])

    with _patch_deepgram(socket), _patch_sd():
        result = await capture_speech()

    assert result == ""
