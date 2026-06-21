"""Tool schemas + handlers the agent calls each step.

Two surfaces:
  - TOOL_SCHEMAS / HANDLERS / call() — used by the hand-rolled loop in node.py
    (testing + fallback).
  - make_tools(device, state) — returns a list of agentspan @tool objects for
    the production Agent + AgentRuntime path.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, Callable

from agentspan.agents import tool as _tool

from agent.bm25_rank import bm25_suggest, format_with_suggestions, parse_nodes_from_xml
from env.device import Device


class _StepLimitReached(Exception):
    pass


LAUNCHER_PACKAGES = {
    "com.sec.android.app.launcher",
    "com.android.launcher3",
    "com.google.android.apps.nexuslauncher",
}


@dataclass
class _AgentState:
    task: str
    steps: list = field(default_factory=list)  # list of (tool, args, obs) tuples
    success: bool = False
    note: str = ""
    finished: bool = False
    max_steps: int = 25
    _last_sig: str | None = None
    _repeat_count: int = 0
    current_location: dict = field(
        default_factory=lambda: {"screen": "unknown", "app": None, "page": 0}
    )

    def update_location(self, device: "Device") -> None:
        try:
            info = device.current_app()
            pkg = info.get("package", "unknown")
            if pkg in LAUNCHER_PACKAGES or pkg == "unknown":
                self.current_location = {
                    "screen": "home",
                    "app": None,
                    "page": self.current_location.get("page", 0),
                }
            else:
                self.current_location = {"screen": "app", "app": pkg, "page": 0}
        except Exception:
            pass  # keep last known location


def _check_limits(state: _AgentState, tool_name: str, args_sig: str) -> None:
    sig = f"{tool_name}:{args_sig}"
    if sig == state._last_sig:
        state._repeat_count += 1
        if state._repeat_count >= 2:
            state.note = f"aborted: tool {tool_name} repeated 3x"
            raise _StepLimitReached
    else:
        state._last_sig = sig
        state._repeat_count = 0
    if len(state.steps) >= state.max_steps:
        state.note = f"aborted: max_steps={state.max_steps} reached"
        raise _StepLimitReached


def make_tools(device: Device, state: _AgentState) -> list:
    """Return agentspan @tool objects with device bound via closure."""

    @_tool
    def dump_ui() -> str:
        """Return current UI hierarchy as XML. Call before any coordinate-based action. Does NOT guarantee the foreground app has finished rendering its first frame."""
        xml = device.dump_ui()
        suggestions = bm25_suggest(parse_nodes_from_xml(xml), state.task)
        result = format_with_suggestions(suggestions, xml)
        state.steps.append(("dump_ui", {}, result))
        _check_limits(state, "dump_ui", json.dumps({}, sort_keys=True))
        return result

    @_tool
    def tap(x: int, y: int) -> str:
        """Tap at absolute pixel coordinates (x, y). Does NOT verify the tap landed on any element. Coordinates must come from the most recent dump_ui output."""
        device.tap(x, y)
        result = f"tapped ({x},{y})"
        state.steps.append(("tap", {"x": x, "y": y}, result))
        _check_limits(state, "tap", json.dumps({"x": x, "y": y}, sort_keys=True))
        return result

    @_tool
    def tap_text(text: str) -> str:
        """Tap the first visible element whose text equals or contains the given string. Does NOT scroll to find off-screen elements. Returns 'miss' immediately if the element is not in the current view."""
        ok = device.tap_text(text)
        result = f"tap_text({text!r}) -> {'hit' if ok else 'miss'}"
        state.steps.append(("tap_text", {"text": text}, result))
        _check_limits(state, "tap_text", json.dumps({"text": text}, sort_keys=True))
        return result

    @_tool
    def long_press(x: int, y: int, duration: float = 1.0) -> str:
        """Long-press at (x, y) for duration seconds."""
        device.long_press(x, y, duration)
        result = f"long_press ({x},{y}) for {duration}s"
        state.steps.append(("long_press", {"x": x, "y": y, "duration": duration}, result))
        _check_limits(state, "long_press", json.dumps({"duration": duration, "x": x, "y": y}, sort_keys=True))
        return result

    @_tool
    def swipe(x1: int, y1: int, x2: int, y2: int, duration: float = 0.3) -> str:
        """Swipe from (x1,y1) to (x2,y2) over duration seconds. Use for scrolling. Does NOT wait for scroll animation to settle before returning."""
        device.swipe(x1, y1, x2, y2, duration)
        result = f"swiped ({x1},{y1})->({x2},{y2})"
        state.steps.append(("swipe", {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "duration": duration}, result))
        _check_limits(state, "swipe", json.dumps({"duration": duration, "x1": x1, "x2": x2, "y1": y1, "y2": y2}, sort_keys=True))
        return result

    @_tool
    def drag(x1: int, y1: int, x2: int, y2: int, duration: float = 0.5) -> str:
        """Drag element from (x1,y1) to (x2,y2). Slower and more deliberate than swipe."""
        device.drag(x1, y1, x2, y2, duration)
        result = f"dragged ({x1},{y1})->({x2},{y2})"
        state.steps.append(("drag", {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "duration": duration}, result))
        _check_limits(state, "drag", json.dumps({"duration": duration, "x1": x1, "x2": x2, "y1": y1, "y2": y2}, sort_keys=True))
        return result

    @_tool
    def type_text(text: str) -> str:
        """Type text into the currently focused input field."""
        device.type_text(text)
        result = f"typed {text!r}"
        state.steps.append(("type_text", {"text": text}, result))
        _check_limits(state, "type_text", json.dumps({"text": text}, sort_keys=True))
        return result

    @_tool
    def press_key(name: str) -> str:
        """Sends an Android keycode event. The 'back' key behavior is app-defined and does NOT guarantee navigation to the previous screen. Keys: home, back, enter, recent, menu, power, volume_up, volume_down."""
        device.press_key(name)
        result = f"pressed {name}"
        state.steps.append(("press_key", {"name": name}, result))
        _check_limits(state, "press_key", json.dumps({"name": name}, sort_keys=True))
        return result

    @_tool
    def open_app(package: str) -> str:
        """Launch an app by its package name (e.g. com.android.settings). Blocks until the app process reaches the foreground. Does NOT wait for the app's first frame to be drawn — call dump_ui after to read the rendered UI."""
        result = device.open_app(package)
        state.steps.append(("open_app", {"package": package}, result))
        _check_limits(state, "open_app", json.dumps({"package": package}, sort_keys=True))
        return result

    @_tool
    def speak(text: str) -> str:
        """Narrate to the user via text-to-speech. Call before major navigation actions (open_app, press_key home/back) and before finish."""
        from services.tts import speak as _tts_speak
        if not text or not text.strip():
            return "spoke: (empty, skipped)"
        _tts_speak(text)
        result = f"spoke: {text}"
        state.steps.append(("speak", {"text": text}, result))
        return result

    @_tool
    def finish(success: bool, note: str) -> str:
        """End the task. Set success=true if goal achieved, false otherwise. Provide a short note."""
        state.success = success
        state.note = note
        state.finished = True
        result = f"FINISH success={success} note={note}"
        state.steps.append(("finish", {"success": success, "note": note}, result))
        # finish is natural termination — do not apply step/repeat limits
        return result

    return [dump_ui, tap, tap_text, long_press, swipe, drag,
            type_text, press_key, open_app, speak, finish]


FINISH = "finish"


def is_finish(tool_name: str) -> bool:
    return tool_name == FINISH


TOOL_SCHEMAS: list[dict[str, Any]] = [
    {
        "name": "tap",
        "description": "Tap at absolute pixel coordinates (x, y). Does NOT verify the tap landed on any element. Read coordinates from the most recent screenshot.",
        "parameters": {
            "type": "object",
            "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}},
            "required": ["x", "y"],
        },
    },
    {
        "name": "tap_text",
        "description": "Tap the first visible element whose text equals or contains the given string. Does NOT scroll to find off-screen elements. Returns 'miss' immediately if the element is not in the current view.",
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
        "description": "Swipe from (x1,y1) to (x2,y2) over `duration` seconds. Use for scrolling. Does NOT wait for scroll animation to settle before returning.",
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
        "description": "Sends an Android keycode event (via uiautomator2 `press`). The 'back' key behavior is app-defined and does NOT guarantee navigation to the previous screen. Keys: home, back, enter, recent, menu, power, volume_up, volume_down.",
        "parameters": {
            "type": "object",
            "properties": {"name": {"type": "string"}},
            "required": ["name"],
        },
    },
    {
        "name": "open_app",
        "description": "Launch an app by its package name (e.g. com.android.settings). Blocks until the app process reaches the foreground. Does NOT wait for the app's first frame to be drawn — call dump_ui after to read the rendered UI.",
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


def _h_dump_ui(d: Device, task: str = "") -> str:
    xml = d.dump_ui()
    suggestions = bm25_suggest(parse_nodes_from_xml(xml), task)
    return format_with_suggestions(suggestions, xml)


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
    return d.open_app(package)


def _h_finish(d: Device, success: bool, note: str) -> str:
    return f"FINISH success={success} note={note}"


HANDLERS: dict[str, Callable[..., str]] = {
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


def call(device: Device, name: str, args: dict[str, Any], task: str = "") -> str:
    if name not in HANDLERS:
        return f"ERROR: unknown tool {name}"
    if name == "dump_ui":
        return _h_dump_ui(device, task=task)
    return HANDLERS[name](device, **args)
