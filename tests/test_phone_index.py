"""Unit tests for load_prompt phone index injection in agent/node.py.

All tests use temporary files — no device connection needed.
"""
import json
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from agent.node import load_prompt, build_graph


# ---------------------------------------------------------------------------
# 1. valid index → ## Phone layout section injected
# ---------------------------------------------------------------------------

def test_valid_index_injects_phone_layout_section(tmp_path):
    index = {
        "home_screen": [
            {"name": "Settings", "package": "com.android.settings", "type": "app", "page": 0}
        ],
        "dock": []
    }
    index_path = tmp_path / "phone_index.json"
    index_path.write_text(json.dumps(index))

    result = load_prompt(index_path)

    assert "## Phone layout" in result


# ---------------------------------------------------------------------------
# 2. None path → prompt unchanged, no section
# ---------------------------------------------------------------------------

def test_none_path_returns_unchanged_prompt():
    result = load_prompt(None)

    assert "## Phone layout" not in result


# ---------------------------------------------------------------------------
# 3. missing file → prompt unchanged, no exception
# ---------------------------------------------------------------------------

def test_missing_file_returns_unchanged_prompt(tmp_path):
    missing = tmp_path / "does_not_exist.json"

    result = load_prompt(missing)

    assert "## Phone layout" not in result


# ---------------------------------------------------------------------------
# 4. malformed JSON → prompt unchanged, no exception
# ---------------------------------------------------------------------------

def test_malformed_json_returns_unchanged_prompt(tmp_path):
    bad_file = tmp_path / "bad.json"
    bad_file.write_text("{ not valid json ][")

    result = load_prompt(bad_file)

    assert "## Phone layout" not in result


# ---------------------------------------------------------------------------
# 5. empty arrays → no section injected
# ---------------------------------------------------------------------------

def test_empty_arrays_injects_nothing(tmp_path):
    index = {"home_screen": [], "dock": []}
    index_path = tmp_path / "phone_index.json"
    index_path.write_text(json.dumps(index))

    result = load_prompt(index_path)

    assert "## Phone layout" not in result


# ---------------------------------------------------------------------------
# 6. home_screen entry → name and package appear in section
# ---------------------------------------------------------------------------

def test_home_screen_entry_name_and_package_in_section(tmp_path):
    index = {
        "home_screen": [
            {"name": "Settings", "package": "com.android.settings", "type": "app", "page": 0}
        ],
        "dock": []
    }
    index_path = tmp_path / "phone_index.json"
    index_path.write_text(json.dumps(index))

    result = load_prompt(index_path)

    assert "Settings" in result
    assert "com.android.settings" in result


# ---------------------------------------------------------------------------
# 7. build_graph without phone_index.json → no exception
# ---------------------------------------------------------------------------

def test_build_graph_without_phone_index_does_not_raise():
    device = MagicMock()

    # Should not raise even if phone_index.json doesn't exist
    run = build_graph(device, max_steps=1, model_call=lambda s, t, steps: ("finish", {"success": True, "note": ""}))

    assert callable(run)
