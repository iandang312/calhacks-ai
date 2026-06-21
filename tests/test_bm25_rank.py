"""Unit tests for bm25_rank — no device required.

Tests cover the two public functions (parse_nodes_from_xml, bm25_suggest)
and the formatting helper. All tests use fixture data.
"""
from __future__ import annotations

from agent.bm25_rank import bm25_suggest, format_with_suggestions, parse_nodes_from_xml

# ---------------------------------------------------------------------------
# parse_nodes_from_xml
# ---------------------------------------------------------------------------

def test_parse_returns_flat_list_from_nested_xml():
    xml = (
        "<hierarchy>"
        '<node text="Parent" content-desc="" resource-id="" bounds="[0,0][1080,100]">'
        '<node text="Child" content-desc="" resource-id="" bounds="[0,0][540,100]" />'
        "</node>"
        "</hierarchy>"
    )
    nodes = parse_nodes_from_xml(xml)
    texts = [n["text"] for n in nodes]
    assert "Parent" in texts
    assert "Child" in texts


def test_parse_returns_empty_list_on_malformed_xml():
    assert parse_nodes_from_xml("not xml <><") == []


def test_parse_node_has_required_keys():
    xml = '<hierarchy><node text="WiFi" content-desc="desc" resource-id="res" bounds="[0,0][100,100]" /></hierarchy>'
    nodes = parse_nodes_from_xml(xml)
    assert len(nodes) == 1
    assert set(nodes[0].keys()) == {"text", "content_desc", "resource_id", "bounds"}


# ---------------------------------------------------------------------------
# bm25_suggest — ranking
# ---------------------------------------------------------------------------

def test_wifi_node_ranks_first_for_wifi_query():
    nodes = [
        {"text": "Bluetooth", "content_desc": "", "resource_id": "", "bounds": "[0,0][100,100]"},
        {"text": "WiFi", "content_desc": "", "resource_id": "", "bounds": "[0,100][100,200]"},
        {"text": "Display", "content_desc": "", "resource_id": "", "bounds": "[0,200][100,300]"},
    ]
    results = bm25_suggest(nodes, "open wifi settings")
    assert len(results) > 0
    assert results[0]["text"] == "WiFi"


def test_resource_id_tokens_split_on_separators():
    nodes = [
        {"text": "", "content_desc": "", "resource_id": "com.android.settings:id/wifi_toggle", "bounds": "[0,0][100,100]"},
        {"text": "Bluetooth", "content_desc": "", "resource_id": "", "bounds": "[0,100][100,200]"},
    ]
    results = bm25_suggest(nodes, "wifi")
    assert len(results) > 0
    assert results[0]["resource_id"] == "com.android.settings:id/wifi_toggle"


def test_zero_overlap_returns_empty_list():
    nodes = [
        {"text": "Bluetooth", "content_desc": "", "resource_id": "", "bounds": "[0,0][100,100]"},
        {"text": "Display", "content_desc": "", "resource_id": "", "bounds": "[0,100][100,200]"},
    ]
    assert bm25_suggest(nodes, "zxqvbnm") == []


def test_fewer_than_k_nodes_returns_all_matching():
    nodes = [
        {"text": "WiFi", "content_desc": "", "resource_id": "", "bounds": "[0,0][100,100]"},
        {"text": "WiFi Advanced", "content_desc": "", "resource_id": "", "bounds": "[0,100][100,200]"},
    ]
    results = bm25_suggest(nodes, "wifi", k=5)
    assert len(results) == 2


def test_returns_at_most_k_results():
    nodes = [
        {"text": f"WiFi option {i}", "content_desc": "", "resource_id": "", "bounds": f"[0,{i*100}][100,{(i+1)*100}]"}
        for i in range(10)
    ]
    results = bm25_suggest(nodes, "wifi", k=3)
    assert len(results) <= 3


def test_empty_query_returns_empty_list():
    nodes = [{"text": "WiFi", "content_desc": "", "resource_id": "", "bounds": "[0,0][100,100]"}]
    assert bm25_suggest(nodes, "") == []


def test_empty_nodes_returns_empty_list():
    assert bm25_suggest([], "wifi settings") == []


# ---------------------------------------------------------------------------
# bm25_suggest — ImportError graceful degradation
# ---------------------------------------------------------------------------

def test_import_error_returns_empty_list(monkeypatch):
    import builtins
    real_import = builtins.__import__

    def mock_import(name, *args, **kwargs):
        if name == "rank_bm25":
            raise ImportError("rank_bm25 not installed")
        return real_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", mock_import)
    # Clear cached import so the monkeypatch takes effect
    import sys
    sys.modules.pop("rank_bm25", None)
    nodes = [{"text": "WiFi", "content_desc": "", "resource_id": "", "bounds": "[0,0][100,100]"}]
    result = bm25_suggest(nodes, "wifi")
    assert result == []


# ---------------------------------------------------------------------------
# format_with_suggestions
# ---------------------------------------------------------------------------

def test_format_prepends_suggested_elements_block():
    suggestions = [{"text": "WiFi", "content_desc": "", "resource_id": "", "bounds": "[0,0][100,100]"}]
    xml = "<hierarchy />"
    result = format_with_suggestions(suggestions, xml)
    assert result.startswith("## Suggested elements")
    assert xml in result


def test_format_returns_xml_unchanged_when_no_suggestions():
    xml = "<hierarchy />"
    assert format_with_suggestions([], xml) == xml


def test_format_block_contains_node_text():
    suggestions = [{"text": "Settings", "content_desc": "", "resource_id": "", "bounds": "[0,0][100,100]"}]
    result = format_with_suggestions(suggestions, "<hierarchy />")
    assert "Settings" in result
