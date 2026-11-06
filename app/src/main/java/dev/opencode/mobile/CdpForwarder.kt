package dev.opencode.mobile

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges the WebView's Chrome DevTools socket onto TCP so a machine on the
 * same tailnet can attach real DevTools without a cable, without adb, and
 * without an emulator.
 *
 * Why this is possible at all: WebView exposes CDP on an *abstract* Unix
 * domain socket named `webview_devtools_remote_<pid>`. `adb forward` is not
 * doing anything magical — it turns that socket into a TCP port. This does the
 * same thing in-process, which means the transport is the tailnet instead of
 * USB.
 *
 * The prerequisite is WebView.setWebContentsDebuggingEnabled(true). Without
 * it the socket is never created and there is nothing to forward; that call
 * lives in MainActivity, gated on the same diagnostic switch as this.
 *
 * SECURITY: this is the most powerful diagnostic surface in the app. CDP
 * gives anyone who can reach the port arbitrary JavaScript execution in the
 * app's origin, full DOM access, and the ability to read and replay every
 * network request the UI makes. It is bound only while Prefs.diagnosticMode
 * is on, and it carries no authentication of its own beyond that — CDP has no
 * auth protocol, so the port itself is the boundary. Do not enable this on an
 * untrusted network. Prefer reaching it through `tailscale serve` or a
 * loopback-forwarded tunnel over exposing the raw port.
 */
object CdpForwarder {
    const val PORT = 9222

    private val started = AtomicBoolean(false)
    private val pumpPool = Executors.newCachedThreadPool()

    @Volatile
    var lastError: String? = null
        private set

    /** The abstract socket name this forwarder targets. */
    @Volatile
    var socketName: String = ""
        private set

    @Volatile
    var activeTunnels: Int = 0
        private set

    val isRunning: Boolean
        get() = started.get()

    /**
     * Starts the forwarder. No-op unless remote diagnostics are enabled.
     *
     * The socket name embeds our own pid because the WebView runs in this
     * process; if the app ever moves to a sandboxed renderer process this has
     * to discover the name from /proc/net/unix instead of assuming it.
     */
    fun start() {
        if (!Prefs.diagnosticMode) return
        if (!started.compareAndSet(false, true)) return

        socketName = "webview_devtools_remote_${android.os.Process.myPid()}"

        Thread {
            var backoffSec = 1L
            while (started.get()) {
                try {
                    ServerSocket().use { server ->
                        server.reuseAddress = true
                        server.bind(InetSocketAddress("0.0.0.0", PORT))
                        backoffSec = 1L
                        while (started.get()) {
                            val tcp = server.accept()
                            pumpPool.execute { tunnel(tcp) }
                        }
                    }
                } catch (e: Exception) {
                    if (!started.get()) break
                    lastError = "${e.javaClass.simpleName}: ${e.message}"
                    // Same failure mode as DiagServer: one transient accept error
                    // used to close the DevTools port for good while the app kept
                    // running, so DevTools could not reattach until a restart.
                    try {
                        Thread.sleep(backoffSec * 1000L)
                    } catch (_: InterruptedException) {
                        break
                    }
                    backoffSec = minOf(backoffSec * 2, 30L)
                }
            }
            started.set(false)
        }.start()
    }

    /**
     * One DevTools connection: TCP in, abstract socket out, both directions.
     *
     * Two threads per connection because CDP is a long-lived bidirectional
     * stream and a blocking read on either side must not stall the other —
     * DevTools hangs instantly if responses stop flowing while it is waiting
     * to send.
     */
    private fun tunnel(tcp: Socket) {
        val local = LocalSocket()
        try {
            local.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            activeTunnels++

            val toDevtools = tcp.getInputStream()
            val fromDevtools = local.inputStream
            val toTcp = tcp.getOutputStream()
            val toLocal = local.outputStream

            val a = pumpPool.submit { pipe(toDevtools, toLocal) }
            val b = pumpPool.submit { pipe(fromDevtools, toTcp) }
            // Block until either direction dies; when one side closes, the whole
            // tunnel is done. submit() rather than execute() because only the
            // returned Future reports completion.
            var done = false
            while (!done) {
                Thread.sleep(200)
                done = a.isDone || b.isDone
            }
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            activeTunnels = (activeTunnels - 1).coerceAtLeast(0)
            try { tcp.close() } catch (_: Exception) {}
            try { local.close() } catch (_: Exception) {}
        }
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
            // Either end hanging up is the normal way for a DevTools session to
            // close. Nothing to report.
        }
    }

    fun status(): String = buildString {
        appendLine("running=${started.get()}")
        appendLine("socket=@${socketName}")
        appendLine("port=$PORT")
        appendLine("tunnels=$activeTunnels")
        lastError?.let { appendLine("error=$it") }
    }
}
