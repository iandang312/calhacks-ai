"""Tool schemas + handlers the agent calls each step.

Two surfaces:
  - TOOL_SCHEMAS / HANDLERS / call() — used by the hand-rolled loop in node.py
    (testing + fallback).
  - make_tools(device, state) — returns a list of agentspan @tool objects for
    the production Agent + AgentRuntime path.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable

from agentspan.agents import tool as _tool

from env.device import Device


@dataclass
class _AgentState:
    task: str
    steps: list = field(default_factory=list)  # list of (tool, args, obs) tuples
    success: bool = False
    note: str = ""
    finished: bool = False


def make_tools(device: Device, state: _AgentState) -> list:
    """Return agentspan @tool objects with device bound via closure."""

    @_tool
    def dump_ui() -> str:
        """Return current UI hierarchy as XML. Call before any coordinate-based action."""
        xml = device.dump_ui()
        result = xml if len(xml) < 8000 else xml[:8000] + "\n<!-- truncated -->"
        state.steps.append(("dump_ui", {}, result))
        return result

    @_tool
    def screenshot() -> str:
        """Capture a PNG screenshot. Saved to last_screenshot.png."""
        device.screenshot("last_screenshot.png")
        result = "screenshot saved to last_screenshot.png"
        state.steps.append(("screenshot", {}, result))
        return result

    @_tool
    def tap(x: int, y: int) -> str:
        """Tap at absolute pixel coordinates (x, y)."""
        device.tap(x, y)
        result = f"tapped ({x},{y})"
        state.steps.append(("tap", {"x": x, "y": y}, result))
        return result

    @_tool
    def tap_text(text: str) -> str:
        """Tap the first visible element whose text equals or contains the given string."""
        ok = device.tap_text(text)
        result = f"tap_text({text!r}) -> {'hit' if ok else 'miss'}"
        state.steps.append(("tap_text", {"text": text}, result))
        return result

    @_tool
    def long_press(x: int, y: int, duration: float = 1.0) -> str:
        """Long-press at (x, y) for duration seconds."""
        device.long_press(x, y, duration)
        result = f"long_press ({x},{y}) for {duration}s"
        state.steps.append(("long_press", {"x": x, "y": y, "duration": duration}, result))
        return result

    @_tool
    def swipe(x1: int, y1: int, x2: int, y2: int, duration: float = 0.3) -> str:
        """Swipe from (x1,y1) to (x2,y2) over duration seconds. Use for scrolling."""
        device.swipe(x1, y1, x2, y2, duration)
        result = f"swiped ({x1},{y1})->({x2},{y2})"
        state.steps.append(("swipe", {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "duration": duration}, result))
        return result

    @_tool
    def drag(x1: int, y1: int, x2: int, y2: int, duration: float = 0.5) -> str:
        """Drag element from (x1,y1) to (x2,y2). Slower and more deliberate than swipe."""
        device.drag(x1, y1, x2, y2, duration)
        result = f"dragged ({x1},{y1})->({x2},{y2})"
        state.steps.append(("drag", {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "duration": duration}, result))
        return result

    @_tool
    def type_text(text: str) -> str:
        """Type text into the currently focused input field."""
        device.type_text(text)
        result = f"typed {text!r}"
        state.steps.append(("type_text", {"text": text}, result))
        return result

    @_tool
    def press_key(name: str) -> str:
        """Press a hardware/system key: home, back, enter, recent, menu, power, volume_up, volume_down."""
        device.press_key(name)
        result = f"pressed {name}"
        state.steps.append(("press_key", {"name": name}, result))
        return result

    @_tool
    def open_app(package: str) -> str:
        """Launch an app by its package name (e.g. com.android.settings)."""
        device.open_app(package)
        result = f"opened {package}"
        state.steps.append(("open_app", {"package": package}, result))
        return result

    @_tool
    def finish(success: bool, note: str) -> str:
        """End the task. Set success=true if goal achieved, false otherwise. Provide a short note."""
        state.success = success
        state.note = note
        state.finished = True
        result = f"FINISH success={success} note={note}"
        state.steps.append(("finish", {"success": success, "note": note}, result))
        return result

    return [dump_ui, screenshot, tap, tap_text, long_press, swipe, drag,
            type_text, press_key, open_app, finish]


FINISH = "finish"


def is_finish(tool_name: str) -> bool:
    return tool_name == FINISH


TOOL_SCHEMAS: list[dict[str, Any]] = [
    {
        "name": "dump_ui",
        "description": "Return the current UI hierarchy as XML. Call this before any coordinate-based action.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "screenshot",
        "description": "Capture a PNG screenshot of the current screen. Use sparingly.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "tap",
        "description": "Tap at absolute pixel coordinates (x, y).",
        "parameters": {
            "type": "object",
            "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}},
            "required": ["x", "y"],
        },
    },
    {
        "name": "tap_text",
        "description": "Tap the first visible element whose text equals or contains the given string. Returns whether an element was found.",
        "parameters": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    },
    {
        "name": "long_press",
        "description": "Long-press at (x, y) for `duration` seconds.",
        "parameters": {
            "type": "object",
            "properties": {
                "x": {"type": "integer"},
                "y": {"type": "integer"},
                "duration": {"type": "number", "default": 1.0},
            },
            "required": ["x", "y"],
        },
    },
    {
        "name": "swipe",
        "description": "Swipe from (x1,y1) to (x2,y2) over `duration` seconds. Use for scrolling.",
        "parameters": {
            "type": "object",
            "properties": {
                "x1": {"type": "integer"}, "y1": {"type": "integer"},
                "x2": {"type": "integer"}, "y2": {"type": "integer"},
                "duration": {"type": "number", "default": 0.3},
            },
            "required": ["x1", "y1", "x2", "y2"],
        },
    },
    {
        "name": "drag",
        "description": "Drag an element from (x1,y1) to (x2,y2). Slower and more deliberate than swipe.",
        "parameters": {
            "type": "object",
            "properties": {
                "x1": {"type": "integer"}, "y1": {"type": "integer"},
                "x2": {"type": "integer"}, "y2": {"type": "integer"},
                "duration": {"type": "number", "default": 0.5},
            },
            "required": ["x1", "y1", "x2", "y2"],
        },
    },
    {
        "name": "type_text",
        "description": "Type text into the currently focused input field.",
        "parameters": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    },
    {
        "name": "press_key",
        "description": "Press a hardware/system key: home, back, enter, recent, menu, power, volume_up, volume_down.",
        "parameters": {
            "type": "object",
            "properties": {"name": {"type": "string"}},
            "required": ["name"],
        },
    },
    {
        "name": "open_app",
        "description": "Launch an app by its package name (e.g. com.android.settings).",
        "parameters": {
            "type": "object",
            "properties": {"package": {"type": "string"}},
            "required": ["package"],
        },
    },
    {
        "name": FINISH,
        "description": "End the task. Set success=true if the goal was achieved, false otherwise. Provide a short note.",
        "parameters": {
            "type": "object",
            "properties": {
                "success": {"type": "boolean"},
                "note": {"type": "string"},
            },
            "required": ["success", "note"],
        },
    },
]


def _h_dump_ui(d: Device) -> str:
    xml = d.dump_ui()
    return xml if len(xml) < 8000 else xml[:8000] + "\n<!-- truncated -->"


def _h_screenshot(d: Device) -> str:
    d.screenshot("last_screenshot.png")
    return "screenshot saved to last_screenshot.png"


def _h_tap(d: Device, x: int, y: int) -> str:
    d.tap(int(x), int(y))
    return f"tapped ({x},{y})"


def _h_tap_text(d: Device, text: str) -> str:
    ok = d.tap_text(text)
    return f"tap_text({text!r}) -> {'hit' if ok else 'miss'}"


def _h_long_press(d: Device, x: int, y: int, duration: float = 1.0) -> str:
    d.long_press(int(x), int(y), float(duration))
    return f"long_press ({x},{y}) for {duration}s"


def _h_swipe(d: Device, x1, y1, x2, y2, duration: float = 0.3) -> str:
    d.swipe(int(x1), int(y1), int(x2), int(y2), float(duration))
    return f"swiped ({x1},{y1})->({x2},{y2})"


def _h_drag(d: Device, x1, y1, x2, y2, duration: float = 0.5) -> str:
    d.drag(int(x1), int(y1), int(x2), int(y2), float(duration))
    return f"dragged ({x1},{y1})->({x2},{y2})"


def _h_type_text(d: Device, text: str) -> str:
    d.type_text(text)
    return f"typed {text!r}"


def _h_press_key(d: Device, name: str) -> str:
    d.press_key(name)
    return f"pressed {name}"


def _h_open_app(d: Device, package: str) -> str:
    d.open_app(package)
    return f"opened {package}"


def _h_finish(d: Device, success: bool, note: str) -> str:
    return f"FINISH success={success} note={note}"


HANDLERS: dict[str, Callable[..., str]] = {
    "dump_ui": _h_dump_ui,
    "screenshot": _h_screenshot,
    "tap": _h_tap,
    "tap_text": _h_tap_text,
    "long_press": _h_long_press,
    "swipe": _h_swipe,
    "drag": _h_drag,
    "type_text": _h_type_text,
    "press_key": _h_press_key,
    "open_app": _h_open_app,
    FINISH: _h_finish,
}


def call(device: Device, name: str, args: dict[str, Any]) -> str:
    if name not in HANDLERS:
        return f"ERROR: unknown tool {name}"
    return HANDLERS[name](device, **args)
