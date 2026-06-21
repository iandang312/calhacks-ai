"""Unit tests for compress_steps — no device required."""
from __future__ import annotations

from agent.node import Step, compress_steps


def _dump_ui_step(xml: str) -> Step:
    return Step(tool="dump_ui", args={}, observation=xml)


def _tap_step() -> Step:
    return Step(tool="tap", args={"x": 100, "y": 200}, observation="tapped (100,200)")


def _finish_step() -> Step:
    return Step(tool="finish", args={"success": True, "note": "done"}, observation="FINISH success=True note=done")


SIMPLE_XML = (
    "<hierarchy>"
    '<node text="Settings" bounds="[0,0][540,100]" />'
    '<node text="WiFi" bounds="[0,100][540,200]" />'
    '<node text="Bluetooth" bounds="[0,200][540,300]" />'
    "</hierarchy>"
)


# --- Cycle 1: dump_ui observation is compressed to node count ---

def test_dump_ui_observation_becomes_node_count_summary():
    steps = [_dump_ui_step(SIMPLE_XML)]
    result = compress_steps(steps)
    assert result[0].observation == "UI dumped: 3 nodes visible"


# --- Cycle 2: non-dump_ui observations pass through unchanged ---

def test_tap_observation_is_unchanged():
    steps = [_tap_step()]
    result = compress_steps(steps)
    assert result[0].observation == "tapped (100,200)"


def test_finish_observation_is_unchanged():
    steps = [_finish_step()]
    result = compress_steps(steps)
    assert result[0].observation == "FINISH success=True note=done"


# --- Cycle 3: original list is not mutated ---

def test_compress_does_not_mutate_original():
    original_obs = SIMPLE_XML
    steps = [_dump_ui_step(original_obs)]
    compress_steps(steps)
    assert steps[0].observation == original_obs


def test_compress_returns_new_list():
    steps = [_tap_step()]
    result = compress_steps(steps)
    assert result is not steps


# --- Cycle 4: dump_ui with BM25 suggestions block prepended ---

def test_dump_ui_with_suggestions_block_counts_xml_nodes():
    obs = (
        "## Suggested elements\n"
        '  text="WiFi"\n'
        "\n"
        "<hierarchy>"
        '<node text="Settings" bounds="[0,0][540,100]" />'
        '<node text="WiFi" bounds="[0,100][540,200]" />'
        "</hierarchy>"
    )
    steps = [_dump_ui_step(obs)]
    result = compress_steps(steps)
    assert result[0].observation == "UI dumped: 2 nodes visible"


# --- Cycle 5: dump_ui fallback truncation ---

def test_dump_ui_truncated_returns_parse_failed_summary():
    obs = "<?xml version='1.0'?><hier" + "\n<!-- truncated -->"
    steps = [_dump_ui_step(obs)]
    result = compress_steps(steps)
    assert result[0].observation == "UI dumped (parse failed, truncated)"


# --- Cycle 6: empty list ---

def test_empty_steps_returns_empty_list():
    assert compress_steps([]) == []


# --- Cycle 7: mixed steps list ---

def test_mixed_steps_only_compresses_dump_ui():
    steps = [
        _tap_step(),
        _dump_ui_step(SIMPLE_XML),
        _finish_step(),
    ]
    result = compress_steps(steps)
    assert result[0].observation == "tapped (100,200)"
    assert result[1].observation == "UI dumped: 3 nodes visible"
    assert result[2].observation == "FINISH success=True note=done"
