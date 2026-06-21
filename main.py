"""Entry point for the voice-driven Android assistant.

Workflow (runs forever):
  microphone → Deepgram STT → intent clarification → agent → TTS → repeat

Usage:
    python main.py
"""
from __future__ import annotations

import asyncio
import os
import sys

from dotenv import load_dotenv

from agent.anthropic_loop import load_prompt, run_anthropic
from audio.stt import capture_speech
from backend.intent_service.intent import infer_intent
from env.device import Device
from services.tts import speak


async def voice_loop(device: Device, max_iterations: int | None = None) -> None:
    """Run the voice pipeline. max_iterations=None loops forever (production)."""
    system = load_prompt()
    iteration = 0

    while max_iterations is None or iteration < max_iterations:
        transcript = await capture_speech()

        if not transcript.strip():
            iteration += 1
            continue

        plan = await infer_intent(transcript)
        speak(plan)

        traj = run_anthropic(device, system, plan, max_steps=25)
        if traj.note:
            speak(traj.note)

        iteration += 1


def main() -> None:
    load_dotenv()

    missing = [k for k in ("ANTHROPIC_API_KEY", "DEEPGRAM_API_KEY") if not os.environ.get(k)]
    if missing:
        print(f"ERROR: missing env vars: {', '.join(missing)}", file=sys.stderr)
        sys.exit(1)

    device = Device()
    print("Listening… (Ctrl+C to stop)")
    try:
        asyncio.run(voice_loop(device))
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
