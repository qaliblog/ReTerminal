package com.rk.terminal.ui.screens.terminal

import java.util.regex.Pattern

fun detectIndentation(text: String): Pair<String, Int> {
    val lines = text.lines()
    val indentCounts = mutableMapOf<String, Int>()
    for (line in lines) {
        if (line.isBlank()) continue
        val indent = line.takeWhile { it.isWhitespace() }
        if (indent.isNotEmpty()) {
            indentCounts[indent] = (indentCounts[indent] ?: 0) + 1
        }
    }
    val dominantIndent = indentCounts.maxByOrNull { it.value }?.key ?: "    " // Default to 4 spaces
    val indentSize = if (dominantIndent.isNotEmpty() && dominantIndent.all { it == ' ' }) {
        dominantIndent.length
    } else {
        4 // Default size
    }
    return Pair(dominantIndent, indentSize)
}

fun normalizeIndent(text: String, indent: String): String {
    // The original implementation is unknown. Returning the text as-is is the safest option.
    return text
}

fun buildRegexFromAnchor(anchor: String): String {
    val trimmed = anchor.trim()
    val escaped = Pattern.quote(trimmed)
    // Allow for flexible whitespace around the anchor
    return "(?m)^[\\t ]*" + escaped + "[\\t ]*$"
}
