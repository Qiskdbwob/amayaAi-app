package com.amaya.intelligence.ui.components.shared

internal fun formatCompactDuration(durationMs: Long, minimumSeconds: Long = 0L): String {
    val rawSeconds = (durationMs.coerceAtLeast(0L) / 1_000L)
    val seconds = rawSeconds.coerceAtLeast(minimumSeconds)
    val hours = seconds / 3_600L
    val minutes = seconds % 3_600L / 60L
    val remainder = seconds % 60L
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${remainder}s"
        else -> "${remainder}s"
    }
}
