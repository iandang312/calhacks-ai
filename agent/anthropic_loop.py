"""Anthropic-direct agent loop. Per-turn screenshot + tool use.

Why this exists: the agentspan path does not terminate cleanly when `finish` is
called (Conductor swallows worker exceptions and the workflow keeps polling),
and agentspan's `media=` parameter only attaches images to the initial task,
not per-turn. Both are fatal for an interactive UI agent.

This loop calls the Anthropic Messages API directly each turn, attaching a
fresh PNG screenshot to every user message. `finish` terminates immediately
because we control the loop.

Tools and observations reuse `agent.tools.TOOL_SCHEMAS` and `HANDLERS` —
no changes to the tool surface.
"""
from __future__ import annotations

import base64
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from anthropic import Anthropic

from agent import tools as T
from agent.tools import TOOL_SCHEMAS
from env.device import Device


DEFAULT_MODEL = "claude-sonnet-4-5"
DEFAULT_MAX_TOKENS = 4096


@dataclass
class Step:
    tool: str
    args: dict[str, Any]
    observation: str


@dataclass
class Trajectory:
    task: str
    steps: list[Step] = field(default_factory=list)
    success: bool = False
    note: str = ""


def _to_anthropic_tools(schemas: list[dict]) -> list[dict]:
    """Convert OpenAI-style {parameters} to Anthropic-style {input_schema}."""
    return [
        {
            "name": s["name"],
            "description": s["description"],
            "input_schema": s["parameters"],
        }
        for s in schemas
    ]


def _screenshot_block(device: Device) -> dict:
    """Capture current screen as a base64 PNG image content block."""
    png_bytes = device.screenshot()
    b64 = base64.b64encode(png_bytes).decode("ascii")
    return {
        "type": "image",
        "source": {"type": "base64", "media_type": "image/png", "data": b64},
    }


def _stringify_observation(obs: Any) -> str:
    """Tool result content must be a string for Anthropic API."""
    if isinstance(obs, str):
        return obs
    return str(obs)


def run_anthropic(
    device: Device,
    system: str,
    task: str,
    max_steps: int = 25,
) -> Trajectory:
    """Drive the device by calling Claude with per-turn screenshots."""
    client = Anthropic()
    model = os.environ.get("MODEL", DEFAULT_MODEL)
    tools = _to_anthropic_tools(TOOL_SCHEMAS)
    traj = Trajectory(task=task)

    messages: list[dict] = [{
        "role": "user",
        "content": [
            {"type": "text", "text": task},
            _screenshot_block(device),
        ],
    }]

    for _ in range(max_steps):
        resp = client.messages.create(
            model=model,
            max_tokens=DEFAULT_MAX_TOKENS,
            system=system,
            tools=tools,
            messages=messages,
        )

        tool_uses = [b for b in resp.content if b.type == "tool_use"]
        if not tool_uses:
            # No tool call — the model returned text only. Treat as soft finish.
            text_blocks = [b.text for b in resp.content if b.type == "text"]
            traj.note = "\n".join(text_blocks) or "no tool call returned"
            return traj

        # Persist assistant turn (raw content blocks must be passed back as-is).
        messages.append({"role": "assistant", "content": resp.content})

        tool_results: list[dict] = []
        finished = False
        for tu in tool_uses:
            name = tu.name
            args = dict(tu.input) if tu.input else {}

            if T.is_finish(name):
                success = bool(args.get("success", False))
                note = str(args.get("note", ""))
                observation = f"FINISH success={success} note={note}"
                traj.steps.append(Step(tool=name, args=args, observation=observation))
                traj.success = success
                traj.note = note
                tool_results.append({
                    "type": "tool_result",
                    "tool_use_id": tu.id,
                    "content": observation,
                })
                finished = True
                break

            observation = T.call(device, name, args)
            traj.steps.append(Step(tool=name, args=args, observation=observation))
            tool_results.append({
                "type": "tool_result",
                "tool_use_id": tu.id,
                "content": _stringify_observation(observation),
            })

        if finished:
            return traj

        # Next user turn: tool results + fresh screenshot of resulting screen.
        messages.append({
            "role": "user",
            "content": tool_results + [_screenshot_block(device)],
        })

    traj.note = f"aborted: max_steps={max_steps} reached"
    return traj


def load_prompt() -> str:
    return (Path(__file__).parent / "prompt.md").read_text()
