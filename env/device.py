"""Thin wrapper around uiautomator2 exposing the action set the agent uses.

Reusable by future modules (planner, evaluator, recorder).
"""
from __future__ import annotations

import uiautomator2 as u2


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
        # Fail fast: a no-op call surfaces a connection error early.
        _ = self.d.info

    def dump_ui(self) -> str:
        return self.d.dump_hierarchy()

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
