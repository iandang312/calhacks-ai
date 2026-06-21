"""TTS service tests — one test per behavior, public interface only."""
import subprocess
from unittest.mock import MagicMock, patch

import pytest

from services.tts import speak


@pytest.fixture(autouse=True)
def _no_subprocess(monkeypatch):
    monkeypatch.setattr(subprocess, "run", MagicMock())


def _make_fake_client(chunks: list[bytes] | None = None):
    def _generate(**kwargs):
        yield from (chunks or [b"audio"])

    mock_client = MagicMock()
    mock_client.speak.v1.audio.generate = _generate
    return mock_client


# ── 1. Empty string skips API ─────────────────────────────────────────────────

def test_speak_empty_string_skips_api():
    with patch("services.tts.DeepgramClient") as mock_cls:
        speak("")
        mock_cls.assert_not_called()


# ── 2. Whitespace-only skips API ──────────────────────────────────────────────

def test_speak_whitespace_skips_api():
    with patch("services.tts.DeepgramClient") as mock_cls:
        speak("   ")
        mock_cls.assert_not_called()


# ── 3. Missing API key — no exception, no client created ─────────────────────

def test_speak_no_api_key_returns_silently(monkeypatch):
    monkeypatch.delenv("DEEPGRAM_API_KEY", raising=False)
    with patch("services.tts.DeepgramClient") as mock_cls:
        speak("hello")
        mock_cls.assert_not_called()


# ── 4. Valid call — calls generate with correct text ─────────────────────────

def test_speak_calls_generate_with_text(monkeypatch):
    monkeypatch.setenv("DEEPGRAM_API_KEY", "test-key")
    generate_calls = []

    def _generate(**kwargs):
        generate_calls.append(kwargs)
        yield b"audio"

    mock_client = MagicMock()
    mock_client.speak.v1.audio.generate = _generate

    with patch("services.tts.DeepgramClient", return_value=mock_client):
        speak("hello world")

    assert len(generate_calls) == 1
    assert generate_calls[0]["text"] == "hello world"


# ── 5. Audio bytes are played via afplay ─────────────────────────────────────

def test_speak_plays_audio(monkeypatch):
    monkeypatch.setenv("DEEPGRAM_API_KEY", "test-key")

    with patch("services.tts.DeepgramClient", return_value=_make_fake_client([b"chunk1", b"chunk2"])):
        with patch("services.tts.subprocess.run") as mock_run:
            speak("play me")

    mock_run.assert_called_once()
    assert mock_run.call_args[0][0][0] == "afplay"


# ── 6. Exception does not propagate ──────────────────────────────────────────

def test_speak_exception_does_not_propagate(monkeypatch):
    monkeypatch.setenv("DEEPGRAM_API_KEY", "test-key")

    def _bad_generate(**kwargs):
        raise RuntimeError("network error")
        yield  # make it a generator

    mock_client = MagicMock()
    mock_client.speak.v1.audio.generate = _bad_generate

    with patch("services.tts.DeepgramClient", return_value=mock_client):
        speak("hello")  # must not raise
