"""Interactive REPL — keep the device connected, run task after task.

Usage:
    source .venv/bin/activate
    python -m agent.repl

Connection setup mirrors tests/manual_run.py: load env first, then Device().
"""
from __future__ import annotations

import os
import sys
import traceback

from dotenv import load_dotenv

load_dotenv()

from env.device import Device
from agent.anthropic_loop import load_prompt, run_anthropic


def main() -> int:
    if not os.environ.get("ANTHROPIC_API_KEY"):
        print("ERROR: no ANTHROPIC_API_KEY in .env", file=sys.stderr)
        return 2

    print("connecting to device...")
    device = Device()
    system = load_prompt()
    print(f"connected. viewport={device.viewport}")
    print("type a task and press enter. type 'quit' to exit.\n")

    while True:
        try:
            task = input("> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return 0

        if not task:
            continue
        if task.lower() in {"quit", "exit", "q"}:
            return 0
        if task.lower() == "home":
            device.press_key("home")
            print("(pressed home)")
            continue

        try:
            traj = run_anthropic(device, system, task, max_steps=25)
            for i, s in enumerate(traj.steps, 1):
                print(f"[{i}] {s.tool}({s.args}) -> {s.observation}")
            print(f"DONE success={traj.success} note={traj.note}\n")
        except Exception:
            traceback.print_exc()
            print()


if __name__ == "__main__":
    sys.exit(main())
