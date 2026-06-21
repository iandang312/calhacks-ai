"""CLI entrypoint: `python -m agent.run "open the settings app"`."""
from __future__ import annotations

import sys

from dotenv import load_dotenv

from env.device import Device
from agent.node import build_graph, llm_env_key


def main(argv: list[str]) -> int:
    load_dotenv()
    if not llm_env_key():
        print("ERROR: no ANTHROPIC_API_KEY or OPENAI_API_KEY in .env", file=sys.stderr)
        return 2
    if len(argv) < 2:
        print("usage: python -m agent.run \"<task>\"", file=sys.stderr)
        return 2

    task = argv[1]
    device = Device()
    # NOTE: pass a real `model_call` here that wraps the agentspan Agent node.
    run = build_graph(device, max_steps=25)
    traj = run(task)
    for i, s in enumerate(traj.steps, 1):
        print(f"[{i}] {s.tool}({s.args}) -> {s.observation}")
    print(f"\nDONE success={traj.success} note={traj.note}")
    return 0 if traj.success else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
