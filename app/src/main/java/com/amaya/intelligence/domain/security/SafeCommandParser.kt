package com.amaya.intelligence.domain.security

/** Parses one executable plus argv. Compound shell grammar is unsupported. */
internal fun parseSafeCommandArguments(command: String): List<String>? {
    val args = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false
    fun flush() {
        if (current.isNotEmpty()) {
            args += current.toString()
            current.clear()
        }
    }
    command.forEachIndexed { index, char ->
        if (escaped) {
            current.append(char)
            escaped = false
            return@forEachIndexed
        }
        if (char == '\\' && quote != '\'') {
            escaped = true
            return@forEachIndexed
        }
        if (quote != null) {
            if (char == quote) quote = null else current.append(char)
            return@forEachIndexed
        }
        when {
            char == '\'' || char == '"' -> quote = char
            char == '\n' || char == '\r' -> return null
            char.isWhitespace() -> flush()
            char in setOf(';', '|', '&', '>', '<', '`') -> return null
            char == '$' && command.getOrNull(index + 1) == '(' -> return null
            else -> current.append(char)
        }
    }
    if (escaped || quote != null) return null
    flush()
    return args
}
