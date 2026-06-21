"""Automated TDD device tests for the agent + environment.

Prerequisites:
    - agentspan server running: ./scripts/start_server.sh
    - adb device connected: adb devices
    - python -m uiautomator2 init (one-time per device)

Run:
    RUN_DEVICE_TESTS=1 pytest tests/test_device_auto.py -v -s

PASS/FAIL is printed per test. On FAIL a debug JSON is written to
tests/debug_<name>_<timestamp>.json for inspection.
"""
from __future__ import annotations

import datetime
import json
import os
from pathlib import Path

import pytest
from dotenv import load_dotenv

load_dotenv()

pytestmark = pytest.mark.skipif(
    not os.environ.get("RUN_DEVICE_TESTS"),
    reason="Set RUN_DEVICE_TESTS=1 to run device tests",
)

from env.device import Device
from agent.node import build_graph


_DEBUG_DIR = Path(__file__).parent


def _dump_debug(name: str, task: str, expected: str, traj, ui_xml: str) -> Path:
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    path = _DEBUG_DIR / f"debug_{name}_{ts}.json"
    path.write_text(json.dumps({
        "test": name,
        "task": task,
        "expected": expected,
        "result": "FAIL",
        "traj_success": traj.success,
        "traj_note": traj.note,
        "trajectory": [
            {"step": i + 1, "tool": s.tool, "args": s.args, "observation": s.observation}
            for i, s in enumerate(traj.steps)
        ],
        "ui_after": ui_xml[:4000],
    }, indent=2))
    return path


def test_open_settings():
    """Agent must open the Settings app from any starting screen.

    PASS condition: after the agent calls finish(), a fresh dump_ui() call
    returns XML that contains 'com.android.settings'. This string appears as
    the foreground package attribute and is unique to the Settings app —
    no other app on the device shares this package name.
    """
    device = Device()
    run = build_graph(device, max_steps=25)

    traj = run("open the settings app")

    ui_after = device.dump_ui()
    passed = "com.android.settings" in ui_after

    print(f"\n[{'PASS' if passed else 'FAIL'}] open_settings")
    if not passed:
        log = _dump_debug("open_settings", "open the settings app",
                          "com.android.settings in UI XML", traj, ui_after)
        print(f"  debug -> {log}")

    assert passed, "open_settings: 'com.android.settings' not found in post-run UI XML"


def test_go_home_from_settings():
    """Agent must navigate to the home screen when Settings is open.

    Setup: opens Settings directly via uiautomator2 (not the agent) so we
    have a known starting state.
    PASS condition: after the agent calls finish(), a fresh dump_ui() returns
    XML that does NOT contain 'com.android.settings'. The Settings app is no
    longer in the foreground — the home launcher is.
    """
    device = Device()
    device.open_app("com.android.settings")

    run = build_graph(device, max_steps=25)
    traj = run("go to the home screen")

    ui_after = device.dump_ui()
    passed = "com.android.settings" not in ui_after

    print(f"\n[{'PASS' if passed else 'FAIL'}] go_home_from_settings")
    if not passed:
        log = _dump_debug("go_home_from_settings", "go to the home screen",
                          "com.android.settings absent from UI XML", traj, ui_after)
        print(f"  debug -> {log}")

    assert passed, "go_home_from_settings: Settings still in foreground after task"
