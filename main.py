"""
main.py — Full voice pipeline entry point.

Flow (loops forever):
  1. Mic → Deepgram STT       : capture what the user said
  2. STT transcript → Claude  : clarify / clean up noisy speech
  3. Speak clarified plan     : tell the user what we're about to do
  4. Claude agent loop        : drive the phone via uiautomator2
  5. Speak result             : tell the user what happened
  6. Go to 1

Run:
    python main.py
"""

import asyncio
import os
import sys

from dotenv import load_dotenv

from agent.anthropic_loop import load_prompt, run_anthropic
from audio.stt import capture_speech
from backend.intent_service.intent import infer_intent
from env.device import Device
from services.tts import speak


def _check_env() -> None:
    missing = [k for k in ("ANTHROPIC_API_KEY", "DEEPGRAM_API_KEY") if not os.environ.get(k)]
    if missing:
        print(f"ERROR: missing env vars: {', '.join(missing)}", file=sys.stderr)
        sys.exit(1)


async def run_once(device: Device, system: str) -> None:
    """Run one full voice interaction: listen → clarify → act → speak."""

    # ── 1. Listen ─────────────────────────────────────────────────────────────
    print("\n[listening] say something...")
    transcript = await capture_speech()

    if not transcript.strip():
        print("[listening] nothing heard, trying again")
        return

    print(f"[heard]     {transcript!r}")

    # ── 2. Clarify intent ─────────────────────────────────────────────────────
    print("[intent]    clarifying...")
    plan = await infer_intent(transcript)
    print(f"[intent]    {plan!r}")

    # ── 3. Speak plan back to user ────────────────────────────────────────────
    speak(plan)

    # ── 4. Run agent ──────────────────────────────────────────────────────────
    print("[agent]     running...")
    traj = run_anthropic(device, system, plan, max_steps=25)

    print(f"[agent]     done — success={traj.success} steps={len(traj.steps)}")
    for i, step in enumerate(traj.steps, 1):
        print(f"  [{i}] {step.tool}({step.args}) → {step.observation[:80]}")

    # ── 5. Speak result ───────────────────────────────────────────────────────
    if traj.note:
        speak(traj.note)
        print(f"[spoke]     {traj.note!r}")


async def voice_loop(device: Device) -> None:
    system = load_prompt()
    print("Voice assistant ready. Ctrl+C to stop.\n")
    while True:
        try:
            await run_once(device, system)
        except KeyboardInterrupt:
            raise
        except Exception as e:
            print(f"[error] {e}", file=sys.stderr)


def main() -> None:
    load_dotenv()
    _check_env()

    print("Connecting to device...")
    device = Device()
    print("Device connected.\n")

    try:
        asyncio.run(voice_loop(device))
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
