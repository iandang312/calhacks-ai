"""Agent node + loop.

Two execution paths:
  - model_call=None  (production): agentspan Agent + AgentRuntime drives the LLM.
  - model_call=<fn>  (tests):      hand-rolled loop using the injected callable,
                                   preserves stuck-detection and step-cap logic.
"""
from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from env.device import Device
from agent import tools as T
from agent.tools import _AgentState, make_tools


PROMPT_PATH = Path(__file__).parent / "prompt.md"


def load_prompt() -> str:
    return PROMPT_PATH.read_text()


def _summarize_dump_ui(observation: str) -> str:
    if "<!-- truncated -->" in observation:
        return "UI dumped (parse failed, truncated)"
    # Strip the ## Suggested elements block if present
    xml_part = observation
    if observation.startswith("## Suggested elements"):
        idx = observation.find("<hierarchy")
        if idx == -1:
            idx = observation.find("<?xml")
        xml_part = observation[idx:] if idx != -1 else observation
    count = xml_part.count("<node")
    return f"UI dumped: {count} nodes visible"


def compress_steps(steps: list[Step]) -> list[Step]:
    """Return a new list with dump_ui observations replaced by a 1-line summary.

    All other tool observations are passed through unchanged. The original
    list and Step objects are never mutated.
    """
    result = []
    for step in steps:
        if step.tool == "dump_ui":
            result.append(Step(tool=step.tool, args=step.args, observation=_summarize_dump_ui(step.observation)))
        else:
            result.append(step)
    return result


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


ModelCall = Callable[[str, str, list[Step]], tuple[str, dict[str, Any]]]


def build_graph(device: Device, max_steps: int = 25, model_call: ModelCall | None = None):
    """Return a callable `run(task) -> Trajectory`.

    When model_call is None the production path is used: agentspan Agent +
    AgentRuntime. When model_call is provided (e.g. in tests) the hand-rolled
    loop runs instead, forwarding each turn to that callable.
    """
    system = load_prompt()

    def run(task: str) -> Trajectory:
        if model_call is not None:
            return _run_hand_rolled(device, system, task, max_steps, model_call)
        return _run_agentspan(device, system, task)

    return run


def _run_agentspan(device: Device, system: str, task: str) -> Trajectory:
    from agentspan.agents import Agent, AgentRuntime

    state = _AgentState(task=task)
    tool_list = make_tools(device, state)
    model = os.environ.get("MODEL", "google_gemini/gemini-2.0-flash")

    agent = Agent(
        name="android-ui-agent",
        model=model,
        instructions=system,
        tools=tool_list,
    )

    with AgentRuntime() as runtime:
        runtime.run(agent, task)

    return Trajectory(
        task=task,
        steps=[Step(tool=s[0], args=s[1], observation=s[2]) for s in state.steps],
        success=state.success,
        note=state.note,
    )


def _run_hand_rolled(
    device: Device,
    system: str,
    task: str,
    max_steps: int,
    model_call: ModelCall,
) -> Trajectory:
    traj = Trajectory(task=task)
    last_call: tuple[str, str] | None = None
    repeat_count = 0

    for _ in range(max_steps):
        name, args = model_call(system, task, compress_steps(traj.steps))
        sig = (name, json.dumps(args, sort_keys=True))

        if sig == last_call:
            repeat_count += 1
            if repeat_count >= 2:
                traj.note = f"aborted: tool {name} repeated 3x"
                return traj
        else:
            last_call, repeat_count = sig, 0

        obs = T.call(device, name, args, task=task)
        traj.steps.append(Step(tool=name, args=args, observation=obs))

        if T.is_finish(name):
            traj.success = bool(args.get("success", False))
            traj.note = str(args.get("note", ""))
            return traj

    traj.note = f"aborted: max_steps={max_steps} reached"
    return traj


def llm_env_key() -> str | None:
    return os.environ.get("ANTHROPIC_API_KEY")
