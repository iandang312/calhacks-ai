import os
import platform
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
        print("[tts] DEEPGRAM_API_KEY not set, skipping", file=sys.stderr)
        return

    text = text[:_MAX_CHARS]
    try:
        client = DeepgramClient(api_key=os.environ["DEEPGRAM_API_KEY"])
        response = client.speak.v1.audio.generate(text=text, model=_MODEL)
        audio_bytes = response.stream.getvalue()

        print(f"[tts] {len(audio_bytes)} bytes", file=sys.stderr)

        with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as f:
            f.write(audio_bytes)
            tmp_path = f.name

        system = platform.system()
        if system == "Darwin":
            subprocess.run(["afplay", tmp_path], check=False)
        elif system == "Windows":
            subprocess.run(["powershell", "-c", f"(New-Object Media.SoundPlayer).SoundLocation = '{tmp_path}'; Start-Process '{tmp_path}'"], check=False)
        else:
            subprocess.run(["mpg123", tmp_path], check=False)

    except Exception as e:
        print(f"[tts] ERROR: {e}", file=sys.stderr)
