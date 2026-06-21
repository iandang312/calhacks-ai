"""Interactive manual runner — type tasks, observe the agent execute them.

Prerequisites:
    - agentspan server running: ./scripts/start_server.sh
    - adb device connected: adb devices
    - python -m uiautomator2 init (one-time per device)

Run:
    source .venv/bin/activate
    python tests/manual_run.py

Type a task and press Enter. The agent will execute it on the device.
Watch the connected device or emulator screen to verify the result.
Ctrl-C to quit.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from dotenv import load_dotenv

load_dotenv()

from env.device import Device
from agent.node import build_graph


def main() -> None:
    print("Connecting to device...")
    device = Device()
    run = build_graph(device, max_steps=25)

    print("Connected. Type a task and press Enter. Ctrl-C to quit.\n")
    while True:
        try:
            task = input("Task> ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nBye.")
            break
        if not task:
            continue

        traj = run(task)
        print()
        for i, s in enumerate(traj.steps, 1):
            print(f"  [{i}] {s.tool}({s.args})")
            print(f"       -> {s.observation}")
        status = "SUCCESS" if traj.success else "FAILED"
        print(f"\n  [{status}] {traj.note}\n")


if __name__ == "__main__":
    main()
