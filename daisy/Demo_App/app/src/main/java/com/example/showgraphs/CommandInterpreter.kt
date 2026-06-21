package com.example.showgraphs

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

object CommandInterpreter {

    fun parse(text: String): ParsedCommand {
        val normalized = text.lowercase().trim()
        if (normalized.isBlank()) {
            return ParsedCommand(
                summary = "unknown request",
                action = AgentAction.UNKNOWN,
                confidence = Confidence.LOW,
                clarifyQuestion = "I didn't catch that. Can you say the app or control you want me to use?",
            )
        }

        parseScreenControl(text, normalized)?.let { return it }
        parseAppControl(normalized)?.let { return it }

        return ParsedCommand(
            summary = text,
            action = AgentAction.UNKNOWN,
            confidence = Confidence.LOW,
            clarifyQuestion = "I'm not sure what to do yet. Can you say the app or control you want me to use?",
        )
    }

    private fun parseAppControl(normalized: String): ParsedCommand? {
        val openApp = Regex("(?:open|launch|start)\\s+([a-z0-9 ._-]+?)(?:\\s+(?:and|then)\\s+(.+))?$")
            .find(normalized)
        if (openApp != null) {
            val appName = openApp.groupValues.getOrNull(1).orEmpty().trim()
            val remainder = openApp.groupValues.getOrNull(2).orEmpty().trim()
            val searchQuery = extractSearchQuery(remainder)
            if (appName.isNotBlank() && searchQuery != null) {
                return ParsedCommand(
                    summary = "open $appName and search for $searchQuery",
                    action = AgentAction.APP_SEARCH,
                    confidence = Confidence.HIGH,
                    appName = appName,
                    searchQuery = searchQuery,
                )
            }
            if (appName.isNotBlank()) {
                return ParsedCommand(
                    summary = "open $appName",
                    action = AgentAction.OPEN_APP,
                    confidence = Confidence.HIGH,
                    appName = appName,
                )
            }
        }

        val searchInApp = Regex("(?:search|find|look up)\\s+(.+?)\\s+(?:in|on)\\s+([a-z0-9 ._-]+)$")
            .find(normalized)
        if (searchInApp != null) {
            val query = searchInApp.groupValues.getOrNull(1).orEmpty().trim()
            val appName = searchInApp.groupValues.getOrNull(2).orEmpty().trim()
            if (query.isNotBlank() && appName.isNotBlank()) {
                return ParsedCommand(
                    summary = "search for $query in $appName",
                    action = AgentAction.APP_SEARCH,
                    confidence = Confidence.HIGH,
                    searchQuery = query,
                    appName = appName,
                )
            }
        }

        extractAfterAny(normalized, listOf("search for ", "find ", "look up "))?.let { query ->
            return ParsedCommand(
                summary = "search for $query",
                action = AgentAction.APP_SEARCH,
                confidence = Confidence.HIGH,
                searchQuery = query,
            )
        }

        return null
    }

    private fun parseScreenControl(text: String, normalized: String): ParsedCommand? {
        if (normalized.contains("scan") || normalized.contains("what's on screen") ||
            normalized.contains("what is on screen") || normalized.contains("read screen")
        ) {
            return ParsedCommand(
                summary = "scan the screen",
                action = AgentAction.SCAN_SCREEN,
                confidence = Confidence.HIGH,
            )
        }

        if (normalized == "go back" || normalized == "back" || normalized.contains("press back")) {
            return ParsedCommand(
                summary = "go back",
                action = AgentAction.GO_BACK,
                confidence = Confidence.HIGH,
            )
        }

        scrollDirection(normalized)?.let { direction ->
            return ParsedCommand(
                summary = "scroll ${direction.name.lowercase()}",
                action = AgentAction.SCROLL,
                confidence = Confidence.HIGH,
                scrollDirection = direction,
            )
        }

        extractAfterAny(normalized, listOf("tap ", "click ", "press ", "select "))?.let { target ->
            return ParsedCommand(
                summary = "tap $target",
                action = AgentAction.TAP_TEXT,
                confidence = Confidence.HIGH,
                targetText = target,
            )
        }

        extractAfterAny(normalized, listOf("type ", "enter ", "write "))?.let { input ->
            return ParsedCommand(
                summary = "type $input",
                action = AgentAction.TYPE_TEXT,
                confidence = Confidence.HIGH,
                inputText = input,
            )
        }

        return null
    }

    private fun extractSearchQuery(text: String): String? {
        if (text.isBlank()) return null
        return extractAfterAny(text, listOf("search for ", "search ", "find ", "look up ", "type "))
    }

    private fun scrollDirection(text: String): ScrollDirection? = when {
        text.contains("scroll down") || text.contains("swipe up") -> ScrollDirection.DOWN
        text.contains("scroll up") || text.contains("swipe down") -> ScrollDirection.UP
        text.contains("scroll left") || text.contains("swipe left") -> ScrollDirection.LEFT
        text.contains("scroll right") || text.contains("swipe right") -> ScrollDirection.RIGHT
        else -> null
    }

    private fun extractAfterAny(text: String, prefixes: List<String>): String? {
        for (prefix in prefixes) {
            val index = text.indexOf(prefix)
            if (index >= 0) {
                val value = text.substring(index + prefix.length).trim()
                if (value.length > 1) return value
            }
        }
        return null
    }

    fun isGoodbye(text: String): Boolean {
        val normalized = text.lowercase()
        return normalized.contains("goodbye") ||
            normalized.contains("good bye") ||
            normalized.contains("bye daisy") ||
            normalized == "bye"
    }

    fun isWakePhrase(text: String): Boolean {
        // Strip punctuation and collapse whitespace so a phrase Deepgram splits
        // into segments ("Hi." + "Daisy.") still matches once recombined.
        val normalized = text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        return normalized.contains("hi daisy")
    }

    fun isAffirmative(text: String): Boolean {
        val normalized = text.lowercase()
        return normalized.contains("yes") || normalized.contains("yeah") ||
            normalized.contains("yep") || normalized.contains("correct") ||
            normalized.contains("do it") || normalized.contains("go ahead") ||
            normalized.contains("sure") || normalized.contains("okay") ||
            normalized.contains("ok")
    }

    fun isNegative(text: String): Boolean {
        val normalized = text.lowercase()
        return normalized.contains("no") || normalized.contains("nope") ||
            normalized.contains("cancel") || normalized.contains("stop") ||
            normalized.contains("don't")
    }
}
