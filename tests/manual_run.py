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
  - Always written — even on Ctrl-C, crash, or agent failure
"""
from __future__ import annotations

import atexit
import datetime
import json
import logging
import sys
import traceback
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from dotenv import load_dotenv

load_dotenv()

from env.device import Device
from agent.node import build_graph, Trajectory


# ---------------------------------------------------------------------------
# Log setup
# ---------------------------------------------------------------------------

_log_path: Path | None = None
_log_handler: logging.FileHandler | None = None


def _setup_log(logs_dir: Path) -> Path:
    global _log_path, _log_handler

    logs_dir.mkdir(exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    log_path = logs_dir / f"session_{ts}.log"

    handler = logging.FileHandler(log_path, encoding="utf-8")
    handler.setLevel(logging.DEBUG)
    handler.setFormatter(logging.Formatter(
        "%(asctime)s [%(name)s] %(levelname)s  %(message)s"
    ))

    root = logging.getLogger()
    root.addHandler(handler)
    root.setLevel(logging.DEBUG)

    _log_path = log_path
    _log_handler = handler

    atexit.register(_flush_log)  # guaranteed flush even on hard exit
    return log_path


def _flush_log() -> None:
    if _log_handler:
        _log_handler.flush()
        _log_handler.close()


# ---------------------------------------------------------------------------
# Trajectory helpers
# ---------------------------------------------------------------------------

def _append_trajectory(
    log_path: Path,
    task: str,
    traj: Trajectory,
    started: datetime.datetime,
    interrupted: bool = False,
    error: str | None = None,
) -> None:
    duration = (datetime.datetime.now() - started).total_seconds()
    block = {
        "task": task,
        "started_at": started.isoformat(),
        "duration_seconds": round(duration, 2),
        "interrupted": interrupted,
        "error": error,
        "success": traj.success if not interrupted and not error else None,
        "note": traj.note if not interrupted and not error else (error or "session interrupted by user"),
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

    try:
        with log_path.open("a", encoding="utf-8") as f:
            f.write("\n--- TASK ---\n")
            f.write(json.dumps(block, indent=2))
            f.write("\n")
            f.flush()
    except Exception as e:
        print(f"[ERROR] Could not write trajectory to log: {e}", file=sys.stderr)


def _print_trajectory(traj: Trajectory, interrupted: bool = False, error: str | None = None) -> None:
    print()
    for i, s in enumerate(traj.steps, 1):
        print(f"  [{i}] {s.tool}({s.args})")
        print(f"       -> {s.observation}")

    if error:
        print(f"\n  [ERROR] {error}")
    elif interrupted:
        print("\n  [INTERRUPTED] partial trajectory above")
    else:
        status = "SUCCESS" if traj.success else "FAILED"
        print(f"\n  [{status}] {traj.note}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

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
        error: str | None = None
        traj = Trajectory(task=task)

        try:
            traj = run(task)
        except KeyboardInterrupt:
            interrupted = True
            print("\n[interrupted]")
        except Exception:
            error = traceback.format_exc()
            print(f"\n[AGENT ERROR]\n{error}")

        # Always write — no matter what happened above
        _append_trajectory(log_path, task, traj, started, interrupted=interrupted, error=error)
        _flush_log()

        _print_trajectory(traj, interrupted=interrupted, error=error)
        print(f"  (logged to {log_path.name})\n")

        if interrupted:
            break


if __name__ == "__main__":
    main()
