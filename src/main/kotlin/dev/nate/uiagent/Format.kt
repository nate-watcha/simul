package dev.nate.uiagent

/** `7.2s` — step/turn/tool timings in live logs. */
fun secs(ms: Long): String = "%.1fs".format(ms / 1000.0)

/** `12s`, `1m24s`, `2h05m` — scenario and phase durations in reports. */
fun humanDuration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m${"%02d".format(s % 60)}s"
        else -> "${s / 3600}h${"%02d".format((s % 3600) / 60)}m"
    }
}

/** `just now`, `5m ago`, `3h ago`, `2d ago` — how long since a run or a state snapshot. */
fun ago(deltaMs: Long): String {
    val m = deltaMs / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "${m}m ago"
        m < 60 * 24 -> "${m / 60}h ago"
        else -> "${m / (60 * 24)}d ago"
    }
}
