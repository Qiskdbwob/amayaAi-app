package com.amaya.intelligence.tools

import java.io.FilterInputStream
import java.io.InputStream

internal class ByteReadBudget(maxBytes: Long) {
    private var remaining = maxBytes

    fun wrap(input: InputStream): InputStream = object : FilterInputStream(input) {
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) consume(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) consume(count.toLong())
            return count
        }
    }

    fun readBytes(input: InputStream): ByteArray = wrap(input).readBytes()

    private fun consume(count: Long) {
        remaining -= count
        require(remaining >= 0) { "Expanded archive content exceeds safety limit" }
    }
}

internal fun sanitizeModelArguments(arguments: Map<String, Any?>): Result<Map<String, Any?>> = runCatching {
    fun validate(value: Any?, path: String) {
        when (value) {
            is Map<*, *> -> value.forEach { (key, child) ->
                val name = key?.toString().orEmpty()
                require(name.isNotBlank()) { "Blank tool argument key at $path" }
                require(!name.startsWith("__") && name !in setOf("allow_sensitive", "parent_call_id")) {
                    "Reserved tool argument: $path.$name"
                }
                validate(child, "$path.$name")
            }
            is Iterable<*> -> value.forEachIndexed { index, child -> validate(child, "$path[$index]") }
            is Array<*> -> value.forEachIndexed { index, child -> validate(child, "$path[$index]") }
        }
    }
    validate(arguments, "arguments")
    arguments
}
