import os
import subprocess
import sys
import tempfile

from deepgram import DeepgramClient

_MODEL = "aura-2-thalia-en"
_MAX_CHARS = 500


def speak(text: str) -> None:
    if not text or not text.strip():
        return

    if not os.environ.get("DEEPGRAM_API_KEY"):
        print("WARNING: DEEPGRAM_API_KEY not set, skipping TTS", file=sys.stderr)
        return

    text = text[:_MAX_CHARS]
    try:
        client = DeepgramClient(api_key=os.environ["DEEPGRAM_API_KEY"])
        audio_bytes = b""
        for chunk in client.speak.v1.audio.generate(text=text, model=_MODEL):
            audio_bytes += chunk

        with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as f:
            f.write(audio_bytes)
            tmp_path = f.name

        subprocess.run(["afplay", tmp_path], check=False)
    except Exception as e:
        print(f"WARNING: TTS failed: {e}", file=sys.stderr)
