package dev.opencode.mobile

/**
 * A bounded log of failed network requests made by the WebView.
 *
 * A toast reading "Not Found" tells the user nothing actionable; the same
 * failure recorded with its method, URL and status code names the missing
 * endpoint straight away. Kept in memory because the diagnostics report is read
 * in the same process, and bounded because once something is wrong the WebView
 * can produce hundreds of identical failures.
 */
object WebDiagnostics {
    private const val MAX = 40
    private val entries = ArrayDeque<String>()

    @Synchronized
    fun record(entry: String) {
        if (entries.size >= MAX) entries.removeFirst()
        entries.addLast(entry)
    }

    @Synchronized
    fun dump(): String = if (entries.isEmpty()) "none" else entries.joinToString(" | ")
}
