"""Tests for Tasks 6 and 7: open_app blocking, truncation removal, tool descriptions."""
from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_device_mock(app_wait_return=True, app_wait_raises=None):
    """Return a Device instance with uiautomator2 internals mocked."""
    with patch("uiautomator2.connect") as mock_connect:
        mock_u2 = MagicMock()
        mock_connect.return_value = mock_u2
        mock_u2.info = {}

        if app_wait_raises is not None:
            mock_u2.app_wait.side_effect = app_wait_raises
        else:
            mock_u2.app_wait.return_value = app_wait_return

        from env.device import Device
        device = Device.__new__(Device)
        device.d = mock_u2
        return device


# ---------------------------------------------------------------------------
# Test 1: app_wait True → "(in foreground)" in result
# ---------------------------------------------------------------------------

def test_open_app_foreground_on_success():
    device = _make_device_mock(app_wait_return=True)
    result = device.open_app("com.android.settings")
    assert "(in foreground)" in result


# ---------------------------------------------------------------------------
# Test 2: app_wait False → timeout error string, no exception
# ---------------------------------------------------------------------------

def test_open_app_timeout_string_on_false():
    device = _make_device_mock(app_wait_return=False)
    result = device.open_app("com.android.settings")
    assert "timeout" in result.lower()
    assert isinstance(result, str)


# ---------------------------------------------------------------------------
# Test 3: app_wait raises RuntimeError → timeout error string, no exception
# ---------------------------------------------------------------------------

def test_open_app_timeout_string_on_exception():
    device = _make_device_mock(app_wait_raises=RuntimeError("device error"))
    result = device.open_app("com.android.settings")
    assert "timeout" in result.lower()
    assert isinstance(result, str)


# ---------------------------------------------------------------------------
# Test 4: _h_open_app with mock device returning "(in foreground)" string
# ---------------------------------------------------------------------------

def test_h_open_app_passes_through_foreground_result():
    from agent.tools import _h_open_app
    mock_device = MagicMock()
    mock_device.open_app.return_value = "opened com.android.settings (in foreground)"
    result = _h_open_app(mock_device, "com.android.settings")
    assert result == "opened com.android.settings (in foreground)"


# ---------------------------------------------------------------------------
# Test 5: _h_open_app with mock device returning timeout string
# ---------------------------------------------------------------------------

def test_h_open_app_passes_through_timeout_result():
    from agent.tools import _h_open_app
    mock_device = MagicMock()
    timeout_msg = "open_app(com.android.settings): timeout waiting for foreground"
    mock_device.open_app.return_value = timeout_msg
    result = _h_open_app(mock_device, "com.android.settings")
    assert result == timeout_msg


# ---------------------------------------------------------------------------
# Test 6: _h_dump_ui with 10000-char XML → no truncation, no <!-- truncated -->
# ---------------------------------------------------------------------------

def test_h_dump_ui_no_truncation():
    from agent.tools import _h_dump_ui
    large_xml = "<root>" + "x" * 10000 + "</root>"
    mock_device = MagicMock()
    mock_device.dump_ui.return_value = large_xml
    result = _h_dump_ui(mock_device)
    assert len(result) > 8000
    assert "<!-- truncated -->" not in result
    assert result == large_xml
