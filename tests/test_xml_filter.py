"""Unit tests for filter_xml — no device required.

Each test enforces one filtering invariant. All tests operate on
fixture XML strings and call filter_xml directly.
"""
from __future__ import annotations

import xml.etree.ElementTree as ET

from env.device import filter_xml

VIEWPORT = (1080, 2340)


def _xml(*node_fragments: str) -> str:
    """Wrap raw <node .../> strings inside a <hierarchy> root."""
    return "<hierarchy>" + "".join(node_fragments) + "</hierarchy>"


def _node(
    text: str = "",
    content_desc: str = "",
    resource_id: str = "",
    visible: str = "true",
    bounds: str = "[0,0][540,100]",
) -> str:
    return (
        f'<node text="{text}" content-desc="{content_desc}" '
        f'resource-id="{resource_id}" visible-to-user="{visible}" '
        f'bounds="{bounds}" />'
    )


# --- Cycle 1: malformed fallback ---

def test_malformed_xml_returns_fallback():
    result = filter_xml("not valid xml <><", VIEWPORT)
    assert result.endswith("\n<!-- truncated -->")


# --- Cycle 2: invisible node removed ---

def test_invisible_node_is_removed():
    xml = _xml(_node(text="Hidden", visible="false"))
    result = filter_xml(xml, VIEWPORT)
    assert "Hidden" not in result


# --- Cycle 3: out-of-bounds node removed ---

def test_out_of_bounds_node_is_removed():
    xml = _xml(_node(text="OffScreen", bounds="[1200,0][1300,100]"))
    result = filter_xml(xml, VIEWPORT)
    assert "OffScreen" not in result


# --- Cycle 4: zero-area node removed ---

def test_zero_area_node_is_removed():
    xml = _xml(_node(text="ZeroArea", bounds="[0,0][0,0]"))
    result = filter_xml(xml, VIEWPORT)
    assert "ZeroArea" not in result


# --- Cycle 5: interactive leaf kept ---

def test_interactive_leaf_with_text_is_kept():
    xml = _xml(_node(text="Settings"))
    result = filter_xml(xml, VIEWPORT)
    assert "Settings" in result


def test_interactive_leaf_with_content_desc_is_kept():
    xml = _xml(_node(content_desc="Back button"))
    result = filter_xml(xml, VIEWPORT)
    assert "Back button" in result


def test_interactive_leaf_with_resource_id_is_kept():
    xml = _xml(_node(resource_id="com.android.settings:id/action_bar"))
    result = filter_xml(xml, VIEWPORT)
    assert "com.android.settings:id/action_bar" in result


# --- Cycle 6: empty leaf removed ---

def test_empty_leaf_is_removed():
    xml = _xml(_node(text="", content_desc="", resource_id=""))
    result = filter_xml(xml, VIEWPORT)
    root = ET.fromstring(result)
    assert len(list(root)) == 0


# --- Cycle 7: parent with interactive child is kept ---

def test_parent_with_interactive_child_is_kept():
    xml = (
        "<hierarchy>"
        '<node text="" content-desc="" resource-id="" visible-to-user="true" bounds="[0,0][1080,2340]">'
        '<node text="WiFi" content-desc="" resource-id="" visible-to-user="true" bounds="[0,0][540,100]" />'
        "</node>"
        "</hierarchy>"
    )
    result = filter_xml(xml, VIEWPORT)
    assert "WiFi" in result


# --- Cycle 8: parent whose only child was removed is also pruned ---

def test_parent_pruned_when_only_child_removed():
    xml = (
        "<hierarchy>"
        '<node text="" content-desc="" resource-id="" visible-to-user="true" bounds="[0,0][1080,2340]">'
        '<node text="Invisible" content-desc="" resource-id="" visible-to-user="false" bounds="[0,0][540,100]" />'
        "</node>"
        "</hierarchy>"
    )
    result = filter_xml(xml, VIEWPORT)
    root = ET.fromstring(result)
    assert len(list(root)) == 0


# --- Cycle 9: output is valid XML ---

def test_output_is_valid_xml():
    xml = _xml(_node(text="Settings"))
    result = filter_xml(xml, VIEWPORT)
    root = ET.fromstring(result)  # must not raise
    assert root is not None


# --- Cycle 10: mixed fixture reduces node count ---

def test_mixed_fixture_reduces_node_count():
    nodes = [
        _node(text="Visible1"),
        _node(text="Visible2"),
        _node(text="InvisibleNode", visible="false"),
        _node(text="OutOfBounds", bounds="[1200,0][1300,100]"),
        _node(text=""),                                # empty leaf
        _node(text="Visible3"),
    ]
    xml = _xml(*nodes)
    result = filter_xml(xml, VIEWPORT)
    root = ET.fromstring(result)
    kept = list(root)
    assert len(kept) == 3
    kept_texts = {n.get("text") for n in kept}
    assert kept_texts == {"Visible1", "Visible2", "Visible3"}
