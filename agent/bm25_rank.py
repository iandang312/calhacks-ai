"""BM25-based UI node ranking for the dump_ui tool.

parse_nodes_from_xml — flatten XML hierarchy into a list of node dicts.
bm25_suggest        — rank nodes by relevance to a query string.
format_with_suggestions — prepend a ## Suggested elements block to XML output.
"""
from __future__ import annotations

import re
import xml.etree.ElementTree as ET


def _tokenize(text: str) -> list[str]:
    """Split on whitespace and Android identifier separators (. / _ :)."""
    text = re.sub(r'[./:_ ]', ' ', text)
    return [t.lower() for t in text.split() if t]


def parse_nodes_from_xml(xml: str) -> list[dict[str, str]]:
    """Return a flat list of node attribute dicts from an XML hierarchy string.

    Keys per dict: text, content_desc, resource_id, bounds.
    Returns [] on any parse error.
    """
    try:
        root = ET.fromstring(xml)
        return [
            {
                "text": node.get("text", ""),
                "content_desc": node.get("content-desc", ""),
                "resource_id": node.get("resource-id", ""),
                "bounds": node.get("bounds", ""),
            }
            for node in root.iter("node")
        ]
    except Exception:
        return []


def bm25_suggest(
    nodes: list[dict[str, str]], query: str, k: int = 5
) -> list[dict[str, str]]:
    """Return the top-k nodes most relevant to query using BM25.

    Returns [] when: query is empty, nodes is empty, no term overlap,
    or rank_bm25 is not installed.
    """
    if not query or not nodes:
        return []
    try:
        from rank_bm25 import BM25Plus
    except ImportError:
        return []

    query_tokens = _tokenize(query)
    if not query_tokens:
        return []

    corpus = [
        _tokenize(n["text"] + " " + n["content_desc"] + " " + n["resource_id"])
        for n in nodes
    ]

    bm25 = BM25Plus(corpus)
    scores = bm25.get_scores(query_tokens)

    if max(scores) == 0:
        return []

    ranked = sorted(zip(scores, nodes), key=lambda x: x[0], reverse=True)
    return [node for score, node in ranked[:k] if score > 0]


def format_with_suggestions(suggestions: list[dict[str, str]], xml: str) -> str:
    """Prepend a ## Suggested elements block to xml if suggestions is non-empty."""
    if not suggestions:
        return xml

    lines = ["## Suggested elements"]
    for s in suggestions:
        parts = []
        if s.get("text"):
            parts.append(f'text="{s["text"]}"')
        if s.get("content_desc"):
            parts.append(f'content-desc="{s["content_desc"]}"')
        if s.get("resource_id"):
            parts.append(f'resource-id="{s["resource_id"]}"')
        if s.get("bounds"):
            parts.append(f'bounds="{s["bounds"]}"')
        lines.append("  " + " ".join(parts))
    lines.append("")

    return "\n".join(lines) + xml
