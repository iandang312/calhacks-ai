package com.echomind.env

data class ParsedCommand(
    val summary: String,
    val action: AgentAction,
    val confidence: Confidence,
    val clarifyQuestion: String? = null,
    val searchQuery: String? = null,
    val targetText: String? = null,
    val inputText: String? = null,
    val scrollDirection: ScrollDirection? = null,
    val appName: String? = null,
)

enum class AgentAction {
    OPEN_APP,
    APP_SEARCH,
    TAP_TEXT,
    TYPE_TEXT,
    SCROLL,
    SCAN_SCREEN,
    GO_BACK,
    UNKNOWN,
}
