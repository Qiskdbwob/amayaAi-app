package com.amaya.intelligence.tools

internal fun formatFileSize(bytes: Long, spaced: Boolean = true): String {
    val separator = if (spaced) " " else ""
    return when {
        bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)}${separator}MB"
        bytes >= 1024L -> "${bytes / 1024L}${separator}KB"
        else -> "$bytes${separator}B"
    }
}

internal fun String.escapeXmlText(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")
