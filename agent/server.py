"""FastAPI wrapper around the agent loop.

Boundary between the Java side (Deepgram + simulation/orchestration) and the
Python side (uiautomator2 + agent loop). Java POSTs a task string; this
service drives the device and returns the trajectory.

Run: `uvicorn agent.server:app --host 0.0.0.0 --port 8000`
"""
from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from env.device import Device
from agent.node import build_graph, llm_env_key


class RunRequest(BaseModel):
    task: str
    max_steps: int = 25


class StepDTO(BaseModel):
    tool: str
    args: dict[str, Any]
    observation: str


class RunResponse(BaseModel):
    success: bool
    note: str
    steps: list[StepDTO]


_state: dict[str, Any] = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    load_dotenv()
    if not llm_env_key():
        raise RuntimeError("no ANTHROPIC_API_KEY or OPENAI_API_KEY in .env")
    _state["device"] = Device()
    yield
    _state.clear()


app = FastAPI(title="Android UI Agent", lifespan=lifespan)


@app.get("/health")
def health() -> dict[str, Any]:
    d: Device | None = _state.get("device")
    return {"ok": d is not None}


@app.post("/agent/run", response_model=RunResponse)
def run_agent(req: RunRequest) -> RunResponse:
    device: Device | None = _state.get("device")
    if device is None:
        raise HTTPException(status_code=503, detail="device not initialized")
    # NOTE: pass a real `model_call` here once the agentspan node is wired.
    run = build_graph(device, max_steps=req.max_steps)
    traj = run(req.task)
    return RunResponse(
        success=traj.success,
        note=traj.note,
        steps=[StepDTO(tool=s.tool, args=s.args, observation=s.observation) for s in traj.steps],
    )
