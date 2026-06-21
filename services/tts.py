import os
import sys

import numpy as np
import sounddevice as sd
from deepgram import DeepgramClient

_MODEL = "aura-2-thalia-en"
_MAX_CHARS = 500
_SAMPLE_RATE = 24000


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
        for chunk in client.speak.v1.audio.generate(
            text=text,
            model=_MODEL,
            encoding="linear16",
            sample_rate=_SAMPLE_RATE,
        ):
            audio_bytes += chunk

        print(f"[tts] received {len(audio_bytes)} bytes", file=sys.stderr)

        if not audio_bytes:
            print("[tts] no audio bytes — check model/plan", file=sys.stderr)
            return

        audio = np.frombuffer(audio_bytes, dtype=np.int16)
        sd.play(audio, samplerate=_SAMPLE_RATE)
        sd.wait()
    except Exception as e:
        print(f"[tts] ERROR: {e}", file=sys.stderr)
