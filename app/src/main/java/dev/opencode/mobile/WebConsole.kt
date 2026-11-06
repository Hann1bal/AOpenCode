package dev.opencode.mobile

import java.util.ArrayDeque

/**
 * A bounded ring of the WebView's JavaScript console output.
 *
 * This exists because the SPA's JS errors were completely invisible: with no
 * onConsoleMessage hook and no remote debugging enabled, a failing tool call
 * inside the page produced nothing anywhere an operator could read. The
 * backend log covers the server side; nothing covered the UI side.
 *
 * Worth having alongside CdpForwarder rather than instead of it. DevTools
 * shows you the console while you are attached; this keeps the last N lines
 * whether or not anyone was watching, which is the difference between
 * catching a repro and chasing a ghost.
 */
object WebConsole {
    private const val CAPACITY = 500

    private val lines = ArrayDeque<String>()
    private val lock = Any()

    @Volatile
    var totalReceived: Int = 0
        private set

    fun add(level: String, message: String, source: String?, line: Int) {
        totalReceived++
        val entry = "[$level] $message" +
            (if (source.isNullOrEmpty()) "" else "  -- $source:$line")
        synchronized(lock) {
            lines.addLast(entry)
            while (lines.size > CAPACITY) lines.removeFirst()
        }
    }

    fun clear() {
        synchronized(lock) { lines.clear() }
    }

    /** Everything buffered, oldest first. */
    fun dump(): String = synchronized(lock) {
        if (lines.isEmpty()) return@synchronized "(console empty — no messages since start)"
        lines.joinToString("\n")
    }

    fun statusLine(): String = synchronized(lock) {
        "buffered=${lines.size} total=$totalReceived capacity=$CAPACITY"
    }
}
