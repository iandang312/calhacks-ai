"""Thin wrapper around uiautomator2 exposing the action set the agent uses.

Reusable by future modules (planner, evaluator, recorder).
"""
from __future__ import annotations

import re
import xml.etree.ElementTree as ET

import uiautomator2 as u2


def _parse_bounds(bounds: str) -> tuple[int, int, int, int] | None:
    m = re.match(r'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', bounds)
    if not m:
        return None
    return int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4))


def _in_viewport(bounds: str, viewport: tuple[int, int]) -> bool:
    parsed = _parse_bounds(bounds)
    if parsed is None:
        return True  # unknown bounds format → keep
    x1, y1, x2, y2 = parsed
    if x1 == x2 or y1 == y2:  # zero-area
        return False
    w, h = viewport
    if x2 <= 0 or y2 <= 0 or x1 >= w or y1 >= h:
        return False
    return True


def _is_interactive(node: ET.Element) -> bool:
    return bool(node.get("text") or node.get("content-desc") or node.get("resource-id"))


def _filter_subtree(node: ET.Element, viewport: tuple[int, int]) -> bool:
    """Filter node's subtree in-place. Returns True to keep, False to remove."""
    if node.get("visible-to-user") == "false":
        return False
    if not _in_viewport(node.get("bounds", ""), viewport):
        return False
    for child in list(node):
        if not _filter_subtree(child, viewport):
            node.remove(child)
    if len(node) == 0 and not _is_interactive(node):
        return False
    return True


def filter_xml(xml: str, viewport: tuple[int, int]) -> str:
    """Return a filtered copy of the UI hierarchy XML.

    Removes invisible nodes, out-of-viewport nodes, and empty non-interactive
    leaf nodes. Preserves parent hierarchy when children survive. Falls back
    to an 8000-char truncation if the XML cannot be parsed.
    """
    try:
        root = ET.fromstring(xml)
        for child in list(root):
            if not _filter_subtree(child, viewport):
                root.remove(child)
        return ET.tostring(root, encoding="unicode")
    except Exception:
        return xml[:8000] + "\n<!-- truncated -->"


_KEY_MAP = {
    "home": "home",
    "back": "back",
    "enter": "enter",
    "recent": "recent",
    "menu": "menu",
    "power": "power",
    "volume_up": "volume_up",
    "volume_down": "volume_down",
}


class Device:
    def __init__(self, serial: str | None = None):
        self.d = u2.connect(serial) if serial else u2.connect()
        info = self.d.info  # Fail fast: surfaces connection errors early.
        try:
            self.viewport: tuple[int, int] = (info["displayWidth"], info["displayHeight"])
        except (KeyError, TypeError):
            self.viewport = (1080, 2340)

    def dump_ui(self) -> str:
        raw = self.d.dump_hierarchy()
        return filter_xml(raw, self.viewport)

    def screenshot(self, path: str | None = None) -> bytes:
        img = self.d.screenshot(format="raw")
        if path:
            with open(path, "wb") as f:
                f.write(img)
        return img

    def tap(self, x: int, y: int) -> None:
        self.d.click(x, y)

    def tap_text(self, text: str) -> bool:
        el = self.d(text=text)
        if el.exists:
            el.click()
            return True
        el = self.d(textContains=text)
        if el.exists:
            el.click()
            return True
        return False

    def long_press(self, x: int, y: int, duration: float = 1.0) -> None:
        self.d.long_click(x, y, duration)

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration: float = 0.3) -> None:
        self.d.swipe(x1, y1, x2, y2, duration=duration)

    def drag(self, x1: int, y1: int, x2: int, y2: int, duration: float = 0.5) -> None:
        self.d.drag(x1, y1, x2, y2, duration=duration)

    def type_text(self, text: str) -> None:
        self.d.send_keys(text, clear=False)

    def press_key(self, name: str) -> None:
        key = _KEY_MAP.get(name)
        if key is None:
            raise ValueError(f"Unsupported key: {name}")
        self.d.press(key)

    def open_app(self, package: str) -> None:
        self.d.app_start(package)
