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

Each session writes a log file to logs/session_<timestamp>.log containing:
  - All agentspan/conductor INFO and ERROR messages
  - A structured JSON block per task (tool calls, args, observations, outcome)
"""
from __future__ import annotations

import datetime
import json
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import os

import sentry_sdk
from dotenv import load_dotenv

load_dotenv()

sentry_sdk.init(
    dsn=os.environ.get("SENTRY_DSN"),
    traces_sample_rate=1.0,
)

from env.device import Device
from agent.node import build_graph


def _setup_log(logs_dir: Path) -> Path:
    logs_dir.mkdir(exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    log_path = logs_dir / f"session_{ts}.log"

    handler = logging.FileHandler(log_path, encoding="utf-8")
    handler.setLevel(logging.DEBUG)
    handler.setFormatter(logging.Formatter(
        "%(asctime)s [%(name)s] %(levelname)s  %(message)s"
    ))
    logging.getLogger().addHandler(handler)
    logging.getLogger().setLevel(logging.DEBUG)

    return log_path


def _append_trajectory(log_path: Path, task: str, traj, started: datetime.datetime, interrupted: bool = False) -> None:
    duration = (datetime.datetime.now() - started).total_seconds()
    block = {
        "task": task,
        "started_at": started.isoformat(),
        "duration_seconds": round(duration, 2),
        "interrupted": interrupted,
        "success": traj.success if not interrupted else None,
        "note": traj.note if not interrupted else "session interrupted by user",
        "steps": [
            {
                "step": i + 1,
                "tool": s.tool,
                "args": s.args,
                "observation": s.observation,
            }
            for i, s in enumerate(traj.steps)
        ],
    }
    with log_path.open("a", encoding="utf-8") as f:
        f.write("\n--- TASK ---\n")
        f.write(json.dumps(block, indent=2))
        f.write("\n")


def _print_trajectory(traj, interrupted: bool = False) -> None:
    print()
    for i, s in enumerate(traj.steps, 1):
        print(f"  [{i}] {s.tool}({s.args})")
        print(f"       -> {s.observation}")
    if interrupted:
        print("\n  [INTERRUPTED] partial trajectory above")
    else:
        status = "SUCCESS" if traj.success else "FAILED"
        print(f"\n  [{status}] {traj.note}")


def main() -> None:
    repo_root = Path(__file__).parent.parent
    log_path = _setup_log(repo_root / "logs")
    print(f"Session log: {log_path}\n")

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

        started = datetime.datetime.now()
        interrupted = False
        from agent.node import Trajectory
        traj = Trajectory(task=task)
        try:
            traj = run(task)
        except KeyboardInterrupt:
            interrupted = True
            print("\n[interrupted]")

        trajectory_context = {
            "task": task,
            "success": traj.success,
            "note": traj.note,
            "steps": [
                {"step": i + 1, "tool": s.tool, "args": str(s.args), "observation": s.observation[:200]}
                for i, s in enumerate(traj.steps)
            ],
        }
        with sentry_sdk.new_scope() as scope:
            scope.set_tag("task", task)
            scope.set_tag("success", str(traj.success))
            scope.set_context("trajectory", trajectory_context)
            sentry_sdk.capture_message(
                f"[{'INTERRUPTED' if interrupted else 'SUCCESS' if traj.success else 'FAILED'}] {task}",
                level="info" if traj.success else "error",
            )

        _append_trajectory(log_path, task, traj, started, interrupted=interrupted)
        _print_trajectory(traj, interrupted=interrupted)
        print(f"  (logged to {log_path.name})\n")

        if interrupted:
            break


if __name__ == "__main__":
    main()
