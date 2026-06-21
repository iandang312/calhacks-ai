"""TDD tests for Task 8: max-steps guard and repeat detection in _run_agentspan.

Tests 1-4 test _check_limits directly (unit).
Tests 5-6 test via build_graph with a hand-rolled model_call stub (integration).
All tests use the hand-rolled path — no live agentspan/Conductor needed.
"""
from __future__ import annotations

import pytest
from unittest.mock import MagicMock

from agent.tools import _AgentState, _check_limits, _StepLimitReached
from agent.node import build_graph


# ---------------------------------------------------------------------------
# Test 1: raises when len(state.steps) == state.max_steps
# ---------------------------------------------------------------------------

def test_check_limits_raises_at_max_steps():
    state = _AgentState(task="t", max_steps=3)
    state.steps = [("tap", {}, "ok")] * 3
    with pytest.raises(_StepLimitReached):
        _check_limits(state, "tap", '{"x": 1, "y": 2}')
    assert "max_steps" in state.note


# ---------------------------------------------------------------------------
# Test 2: does NOT raise when len(state.steps) < state.max_steps
# ---------------------------------------------------------------------------

def test_check_limits_no_raise_below_max_steps():
    state = _AgentState(task="t", max_steps=3)
    state.steps = [("tap", {}, "ok")] * 2  # only 2, limit is 3
    # Should not raise
    _check_limits(state, "tap", '{"x": 1, "y": 2}')


# ---------------------------------------------------------------------------
# Test 3: raises when same sig fires 3 times in a row
# ---------------------------------------------------------------------------

def test_check_limits_raises_on_third_identical_call():
    state = _AgentState(task="t", max_steps=25)
    sig = '{"x": 5, "y": 10}'
    # First call — sets _last_sig, no raise
    _check_limits(state, "tap", sig)
    # Second call — _repeat_count becomes 1, no raise
    _check_limits(state, "tap", sig)
    # Third call — _repeat_count becomes 2, raises
    with pytest.raises(_StepLimitReached):
        _check_limits(state, "tap", sig)
    assert "repeated 3x" in state.note


# ---------------------------------------------------------------------------
# Test 4: does NOT raise after 2 identical then 1 different (repeat resets)
# ---------------------------------------------------------------------------

def test_check_limits_repeat_resets_on_different_call():
    state = _AgentState(task="t", max_steps=25)
    sig_a = '{"x": 1}'
    sig_b = '{"x": 2}'
    # Two identical
    _check_limits(state, "tap", sig_a)
    _check_limits(state, "tap", sig_a)
    # Different — should reset and NOT raise
    _check_limits(state, "tap", sig_b)  # no raise expected


# ---------------------------------------------------------------------------
# Test 5: via build_graph — max_steps=3, model never calls finish → aborts
# Rotates between different taps so repeat detection does not trigger first.
# ---------------------------------------------------------------------------

def test_build_graph_aborts_at_max_steps():
    device = MagicMock()
    device.tap.return_value = None

    call_count = 0

    def rotating_taps(_system, _task, _steps):
        nonlocal call_count
        result = ("tap", {"x": call_count, "y": 0})
        call_count += 1
        return result

    run = build_graph(device, max_steps=3, model_call=rotating_taps)
    traj = run("do something")

    assert "max_steps" in traj.note
    assert len(traj.steps) == 3


# ---------------------------------------------------------------------------
# Test 6: via build_graph — model always returns same tool → aborts "repeated 3x"
# ---------------------------------------------------------------------------

def test_build_graph_aborts_on_repeat():
    device = MagicMock()
    device.tap.return_value = None

    def always_tap(_system, _task, _steps):
        return ("tap", {"x": 10, "y": 20})

    run = build_graph(device, max_steps=25, model_call=always_tap)
    traj = run("do something")

    assert "repeated 3x" in traj.note
