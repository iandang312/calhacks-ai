"""Live STT: sync Deepgram WebSocket + sounddevice mic, wrapped for async callers."""
from __future__ import annotations

import asyncio
import os
import threading

import sounddevice as sd
from deepgram import DeepgramClient
from deepgram.core.events import EventType

_SAMPLE_RATE = 16000
_CHANNELS = 1
_DTYPE = "int16"
_BLOCK_SIZE = 8000  # 0.5s chunks at 16 kHz


def _capture_speech_sync() -> str:
    """Block until one final transcript is received from Deepgram, then return it."""
    client = DeepgramClient(api_key=os.environ["DEEPGRAM_API_KEY"])
    done = threading.Event()
    result = [""]

    with client.listen.v1.connect(
        model="nova-3",
        language="en-US",
        smart_format="true",
        interim_results="true",
        utterance_end_ms="1000",
        vad_events="true",
        encoding="linear16",
        sample_rate=str(_SAMPLE_RATE),
    ) as connection:
        print("[stt] connected")

        def on_message(message) -> None:
            is_final = getattr(message, "is_final", False)
            try:
                transcript = message.channel.alternatives[0].transcript
            except Exception:
                transcript = ""
            if transcript or is_final:
                print(f"[stt] is_final={is_final} {transcript!r}")
            if is_final:
                result[0] = transcript
                done.set()

        def on_error(error) -> None:
            print(f"[stt] ERROR: {error}")
            done.set()

        def on_close(_) -> None:
            print("[stt] connection closed")
            done.set()

        connection.on(EventType.MESSAGE, on_message)
        connection.on(EventType.ERROR, on_error)
        connection.on(EventType.CLOSE, on_close)

        # start_listening() blocks reading websocket — run it in a background thread
        listener = threading.Thread(target=connection.start_listening, daemon=True)
        listener.start()

        chunks_sent = 0

        def _audio_cb(indata, frames, time_info, status) -> None:
            nonlocal chunks_sent
            if status:
                print(f"[stt] mic status: {status}", flush=True)
            chunks_sent += 1
            if chunks_sent % 10 == 1:
                print(f"[stt] sending chunk #{chunks_sent}", flush=True)
            connection.send_media(indata.tobytes())

        print("[stt] mic open — speak now")
        with sd.InputStream(
            samplerate=_SAMPLE_RATE,
            channels=_CHANNELS,
            dtype=_DTYPE,
            blocksize=_BLOCK_SIZE,
            callback=_audio_cb,
        ):
            done.wait()

    return result[0]


async def capture_speech() -> str:
    """Run sync STT in a thread so it doesn't block the async event loop."""
    return await asyncio.to_thread(_capture_speech_sync)
