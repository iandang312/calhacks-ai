"""Bootstrap script: scan the device home screen and write agent/phone_index.json.

Usage:
    python scripts/build_phone_index.py

Prerequisites:
    - adb device connected (adb devices shows device)
    - python -m uiautomator2 init (one-time per device)

Output: agent/phone_index.json  (overwrites if present)

Schema:
    {
        "home_screen": [
            {"name": "Settings", "package": "com.android.settings", "type": "app", "page": 0}
        ],
        "dock": []
    }

The script parses <node> elements with clickable="true" and non-empty text from
the home-screen UI dump. Dock detection is not implemented (dock entries remain
an empty list); extend if needed.
"""
from __future__ import annotations

import json
import xml.etree.ElementTree as ET
from pathlib import Path

from env.device import Device

OUTPUT_PATH = Path(__file__).parent.parent / "agent" / "phone_index.json"


def _parse_home_screen(xml_str: str) -> list[dict]:
    """Return list of app entries from a home-screen UI dump."""
    try:
        root = ET.fromstring(xml_str)
    except ET.ParseError as exc:
        print(f"[build_phone_index] XML parse error: {exc}")
        return []

    entries = []
    for node in root.iter("node"):
        if node.get("clickable") != "true":
            continue
        text = (node.get("text") or "").strip()
        if not text:
            continue
        package = node.get("package") or ""
        entries.append({
            "name": text,
            "package": package,
            "type": "app",
            "page": 0,
        })
    return entries


def main() -> None:
    print("[build_phone_index] Connecting to device...")
    device = Device()

    print("[build_phone_index] Dumping home-screen UI...")
    xml_str = device.dump_ui()

    home_screen = _parse_home_screen(xml_str)
    print(f"[build_phone_index] Found {len(home_screen)} clickable apps.")

    index = {"home_screen": home_screen, "dock": []}
    OUTPUT_PATH.write_text(json.dumps(index, indent=2))
    print(f"[build_phone_index] Wrote {OUTPUT_PATH}")


# TODO: current implementation only captures the visible home screen page.
# Needs multi-page swipe + App Drawer (swipe-up) support before this is reliable.
# if __name__ == "__main__":
#     main()
