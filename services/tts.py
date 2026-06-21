import asyncio
import os
import subprocess
import sys
import tempfile

from deepgram import AsyncDeepgramClient

_MODEL = "aura-2-thalia-en"
_MAX_CHARS = 500


async def _speak_async(text: str) -> None:
    client = AsyncDeepgramClient()
    audio_bytes = b""
    async for chunk in client.speak.v1.audio.generate(text=text, model=_MODEL):
        audio_bytes += chunk

    with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as f:
        f.write(audio_bytes)
        tmp_path = f.name

    subprocess.run(["afplay", tmp_path], check=False)


def speak(text: str) -> None:
    if not text or not text.strip():
        return

    text = text[:_MAX_CHARS]

    api_key = os.environ.get("DEEPGRAM_API_KEY")
    if not api_key:
        print("WARNING: DEEPGRAM_API_KEY not set, skipping TTS", file=sys.stderr)
        return

    try:
        asyncio.run(_speak_async(text))
    except Exception as e:
        print(f"WARNING: TTS failed: {e}", file=sys.stderr)
