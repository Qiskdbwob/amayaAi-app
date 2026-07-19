package com.amaya.intelligence.domain.security

internal sealed interface CommandParseResult {
    data class Success(val argv: List<String>) : CommandParseResult
    data class Error(val reason: String) : CommandParseResult
}

/** Parses one executable plus argv. Shell interpretation is deliberately unsupported. */
internal fun parseCommand(command: String): CommandParseResult {
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
            char == '\n' || char == '\r' -> return CommandParseResult.Error("Multiple commands are not supported; run one executable per call")
            char.isWhitespace() -> flush()
            char == ';' -> return CommandParseResult.Error("Command chaining with ';' is not supported; run each command separately")
            char == '|' -> return CommandParseResult.Error("Pipes are not supported; run each command separately or use a native tool")
            char == '&' -> return CommandParseResult.Error("Background or chained commands are not supported")
            char == '>' || char == '<' -> return CommandParseResult.Error("Shell redirection is not supported; use workspace file tools")
            char == '`' || char == '$' && command.getOrNull(index + 1) == '(' -> return CommandParseResult.Error("Command substitution is not supported")
            else -> current.append(char)
        }
    }
    if (escaped) return CommandParseResult.Error("Command ends with an incomplete escape")
    if (quote != null) return CommandParseResult.Error("Command has an unterminated quote")
    flush()
    return CommandParseResult.Success(args)
}

internal fun parseSafeCommandArguments(command: String): List<String>? =
    (parseCommand(command) as? CommandParseResult.Success)?.argv
