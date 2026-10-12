package com.releasemanager.util

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

/** "just now", "5m ago", "3h ago", "2d ago", then the date; "" when [iso] is not an ISO-8601 instant. */
fun formatTimeAgo(iso: String, now: Instant = Instant.now()): String {
    val then = try {
        Instant.parse(iso)
    } catch (e: DateTimeParseException) {
        return ""
    }
    val seconds = Duration.between(then, now).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3_600}h ago"
        seconds < 30 * 86_400L -> "${seconds / 86_400}d ago"
        else -> then.atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }
}
