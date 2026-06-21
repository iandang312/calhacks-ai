"""Live STT via Deepgram WebSocket + sounddevice microphone capture."""
from __future__ import annotations

import asyncio
import os
from typing import Awaitable, Callable

import sounddevice as sd
from deepgram import AsyncDeepgramClient
from deepgram.core.events import EventType

_SAMPLE_RATE = 16000
_CHANNELS = 1
_DTYPE = "int16"
_BLOCK_SIZE = 8000  # 0.5s chunks at 16 kHz


async def listen_once(callback: Callable[[str], Awaitable[None]]) -> None:
    """Open mic, stream to Deepgram, call callback with first is_final transcript, then stop."""
    client = AsyncDeepgramClient(api_key=os.environ["DEEPGRAM_API_KEY"])
    done = asyncio.Event()
    loop = asyncio.get_running_loop()

    async with client.listen.v1.connect(
        model="nova-2",
        language="en-US",
        smart_format=True,
        interim_results=True,
        utterance_end_ms=1000,
        vad_events=True,
        encoding="linear16",
        sample_rate=_SAMPLE_RATE,
    ) as connection:
        print("[stt] connected to Deepgram")

        async def on_error(error) -> None:
            print(f"[stt] ERROR from Deepgram: {error}")
            done.set()

        async def on_close(msg) -> None:
            print(f"[stt] connection closed: {msg}")
            done.set()

        connection.on(EventType.ERROR, on_error)
        connection.on(EventType.CLOSE, on_close)

        async def on_message(message) -> None:
            msg_type = getattr(message, "type", type(message).__name__)
            is_final = getattr(message, "is_final", None)
            transcript = ""
            try:
                transcript = message.channel.alternatives[0].transcript
            except Exception:
                pass
            print(f"[stt] message type={msg_type} is_final={is_final} transcript={transcript!r}")
            if not is_final:
                return
            await callback(transcript)
            done.set()

        connection.on(EventType.MESSAGE, on_message)
        listen_task = asyncio.create_task(connection.start_listening())

        chunks_sent = 0

        def _audio_cb(indata, frames, time, status) -> None:
            nonlocal chunks_sent
            if status:
                print(f"[stt] audio status: {status}")
            chunks_sent += 1
            if chunks_sent % 10 == 1:
                print(f"[stt] sending audio chunk #{chunks_sent} ({len(indata)} frames)")
            asyncio.run_coroutine_threadsafe(
                connection.send_media(bytes(indata)),
                loop,
            )

        print("[stt] opening microphone...")
        with sd.InputStream(
            samplerate=_SAMPLE_RATE,
            channels=_CHANNELS,
            dtype=_DTYPE,
            blocksize=_BLOCK_SIZE,
            callback=_audio_cb,
        ):
            print("[stt] mic open, speak now")
            await done.wait()

        listen_task.cancel()
        try:
            await listen_task
        except asyncio.CancelledError:
            pass


async def capture_speech() -> str:
    """Capture one utterance from the microphone and return the transcript."""
    result: list[str] = []

    async def _cb(text: str) -> None:
        result.append(text)

    await listen_once(_cb)
    return result[0] if result else ""
