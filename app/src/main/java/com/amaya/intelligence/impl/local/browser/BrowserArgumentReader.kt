package com.amaya.intelligence.impl.local.browser

/** Typed reads for model-provided browser arguments. */
internal fun selectorArg(arguments: Map<String, Any?>): String? =
    firstString(arguments, "element_id", "target", "selector", "query", "id")

internal fun queryArg(arguments: Map<String, Any?>): String? =
    firstString(arguments, "query", "text", "label", "name", "target", "selector", "element_id")

internal fun firstString(arguments: Map<String, Any?>, vararg keys: String): String? {
    keys.forEach { key ->
        val value = arguments[key]?.toString()?.trim()
        if (!value.isNullOrBlank()) return value
    }
    return null
}

internal fun intArg(arguments: Map<String, Any?>, key: String, default: Int): Int =
    (arguments[key] as? Number)?.toInt() ?: arguments[key]?.toString()?.toIntOrNull() ?: default

internal fun longArg(arguments: Map<String, Any?>, key: String, default: Long): Long =
    (arguments[key] as? Number)?.toLong() ?: arguments[key]?.toString()?.toLongOrNull() ?: default

internal fun boolArg(arguments: Map<String, Any?>, key: String, default: Boolean): Boolean =
    arguments[key] as? Boolean ?: arguments[key]?.toString()?.toBooleanStrictOrNull() ?: default

internal fun floatArg(arguments: Map<String, Any?>, key: String, default: Float): Float =
    (arguments[key] as? Number)?.toFloat() ?: arguments[key]?.toString()?.toFloatOrNull() ?: default
