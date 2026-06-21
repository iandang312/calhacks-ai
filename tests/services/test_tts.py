"""TTS service tests — one test per behavior, public interface only."""
import subprocess
from unittest.mock import MagicMock, patch

import pytest

from services.tts import speak


@pytest.fixture(autouse=True)
def _no_subprocess(monkeypatch):
    """Never let tests actually play audio."""
    monkeypatch.setattr(subprocess, "run", MagicMock())


def _make_fake_client(chunks: list[bytes] | None = None):
    """Return a mock AsyncDeepgramClient whose generate yields the given chunks."""
    async def _generate(**kwargs):
        for chunk in (chunks or [b"audio"]):
            yield chunk

    mock_client = MagicMock()
    mock_client.speak.v1.audio.generate = _generate
    return mock_client


# ── 1. Empty string skips API ─────────────────────────────────────────────────

def test_speak_empty_string_skips_api():
    with patch("services.tts.AsyncDeepgramClient") as mock_cls:
        speak("")
        mock_cls.assert_not_called()


# ── 2. Whitespace-only skips API ──────────────────────────────────────────────

def test_speak_whitespace_skips_api():
    with patch("services.tts.AsyncDeepgramClient") as mock_cls:
        speak("   ")
        mock_cls.assert_not_called()


# ── 3. Missing API key — no exception, no client created ─────────────────────

def test_speak_no_api_key_returns_silently(monkeypatch):
    monkeypatch.delenv("DEEPGRAM_API_KEY", raising=False)
    with patch("services.tts.AsyncDeepgramClient") as mock_cls:
        speak("hello")  # must not raise
        mock_cls.assert_not_called()


# ── 4. Valid call — creates client with correct key and calls generate ────────

def test_speak_calls_generate_with_text(monkeypatch):
    monkeypatch.setenv("DEEPGRAM_API_KEY", "test-key")

    generate_calls = []

    async def _generate(**kwargs):
        generate_calls.append(kwargs)
        yield b"audio"

    mock_client = MagicMock()
    mock_client.speak.v1.audio.generate = _generate

    with patch("services.tts.AsyncDeepgramClient", return_value=mock_client) as mock_cls:
        speak("hello world")

    mock_cls.assert_called_once_with("test-key")
    assert len(generate_calls) == 1
    assert generate_calls[0]["text"] == "hello world"


# ── 5. Audio bytes are written to a temp file and played ─────────────────────

def test_speak_plays_audio(monkeypatch):
    monkeypatch.setenv("DEEPGRAM_API_KEY", "test-key")

    with patch("services.tts.AsyncDeepgramClient", return_value=_make_fake_client([b"chunk1", b"chunk2"])):
        with patch("services.tts.subprocess.run") as mock_run:
            speak("play me")

    mock_run.assert_called_once()
    cmd = mock_run.call_args[0][0]
    assert cmd[0] == "afplay"


# ── 6. Exception inside async path does not propagate ────────────────────────

def test_speak_exception_does_not_propagate(monkeypatch):
    monkeypatch.setenv("DEEPGRAM_API_KEY", "test-key")

    async def _bad_generate(**kwargs):
        raise RuntimeError("network error")
        yield  # make it an async generator

    mock_client = MagicMock()
    mock_client.speak.v1.audio.generate = _bad_generate

    with patch("services.tts.AsyncDeepgramClient", return_value=mock_client):
        speak("hello")  # must not raise
