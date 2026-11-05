package dev.opencode.mobile

import android.app.Application
import android.os.Build
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter

/**
 * A crash log that survives the crash.
 *
 * A silent exit tells the user nothing and tells us nothing. Every phase of the
 * startup path is appended to a file and fsync'd as it is entered, so when the
 * process dies — from an uncaught exception, an `OutOfMemoryError`, or a native
 * signal that no Java handler ever sees — the last line names the thing that was
 * running. That turns "it just closes" into a specific step we can fix.
 *
 * Written to internal storage rather than the shared area: `/Android/data` is
 * hidden from most file managers since Android 11, and a diagnostic nobody can
 * open is not a diagnostic. It is surfaced through the in-app report instead.
 */
object CrashLog {
    @Volatile
    private var out: FileOutputStream? = null

    @Volatile
    private var file: File? = null

    fun install(app: Application) {
        val f = File(app.filesDir, "crash.txt")
        file = f
        try {
            if (f.exists() && f.length() > 2L * 1024 * 1024) f.delete()
            out = FileOutputStream(f, true)
            mark("process start ${BuildConfig.VERSION_NAME} pid=${Process.myPid()} api=${Build.VERSION.SDK_INT}")
        } catch (_: Throwable) {
            // A log we cannot write must never become the reason for a crash.
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(thread, error)
            close()
            previous?.uncaughtException(thread, error)
        }
    }

    /** Records the step being entered. Call immediately before risky work. */
    @Synchronized
    fun mark(step: String) {
        write("phase: $step")
    }

    /** Any other line worth having on disk. */
    @Synchronized
    fun note(text: String) {
        write(text)
    }

    @Synchronized
    private fun record(thread: Thread, error: Throwable) {
        val trace = StringWriter()
        PrintWriter(trace).use { error.printStackTrace(it) }
        write("UNCAUGHT in ${thread.name}: ${error.javaClass.name}: ${error.message}\n$trace")
    }

    private fun write(text: String) {
        try {
            val stream = out ?: return
            stream.write((text + "\n").toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    private fun close() {
        try {
            out?.close()
        } catch (_: Throwable) {
        }
        out = null
    }

    fun hasCrash(): Boolean = file?.exists() == true && (file?.length() ?: 0L) > 0L

    /** The tail of the log, newest last. Bounded so a report stays copyable. */
    fun tail(maxBytes: Int = 24 * 1024): String {
        val f = file ?: return ""
        if (!f.exists()) return ""
        return try {
            val bytes = f.readBytes()
            if (bytes.size <= maxBytes) {
                String(bytes, Charsets.UTF_8)
            } else {
                String(bytes, bytes.size - maxBytes, maxBytes, Charsets.UTF_8)
            }
        } catch (_: Throwable) {
            ""
        }
    }

    /** The last few recorded steps, newest last. */
    fun recentPhases(n: Int = 14): String {
        // split('\n'), not lines(): from API 35 java.lang.String has a member
        // lines() that outranks the Kotlin extension and does not exist on
        // Android 12, which is exactly how the rootfs pass died.
        val lines = tail()
            .split('\n')
            .filter { it.startsWith("phase:") || it.startsWith("FAILED") || it.startsWith("UNCAUGHT") }
        if (lines.isEmpty()) return "none"
        return lines.takeLast(n).joinToString(" | ")
    }

    /**
     * The last recorded uncaught exception, as one short line.
     *
     * The full trace is in the file; this is what goes in a toast or a report
     * where a stack trace would be useless.
     */
    fun lastErrorLine(): String {
        val text = tail()
        val idx = text.lastIndexOf("UNCAUGHT ")
        if (idx < 0) return "none"
        return text.substring(idx).lineSequence().firstOrNull()?.take(300) ?: "none"
    }
}
