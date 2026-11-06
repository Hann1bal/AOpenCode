package dev.opencode.mobile

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Remote diagnostic exec endpoint.
 *
 * =====================================================================
 *  DIAGNOSTIC ACCESS — REMOVE BEFORE ANY RELEASE BUILD
 * =====================================================================
 *
 * Runs a command inside the app's own sandbox using the exact environment
 * the agent gets from [BackendManager.applyAgentEnv], and returns its
 * merged stdout/stderr. That makes the device legible from the tailnet:
 * the unpacked rootfs, backend.log, the wrapper tree, and any tool the
 * agent can reach, all under the same uid.
 *
 * This is a remote shell as the app user. It exists because a copy/pasted
 * diagnostics blob cannot answer "what did that command actually print",
 * which is the only question that matters when a tool fails. Gated on
 * [Prefs.diagnosticMode] and on Basic auth with the same token the
 * backend uses.
 *
 * Never enabled by default. The whole surface is behind the user's "Remote
 * diagnostics" switch, the token is generated per install rather than
 * compiled in, and with the switch off the backend binds loopback and this
 * server never starts. Treat the switch as a debug tool: while it is on,
 * anyone holding the token has a shell on the device.
 *
 * Usage:
 *   curl -u opencode:TOKEN http://DEVICE:4180/diag/ping
 *   curl -u opencode:TOKEN -X POST --data 'id; ls -la $HOME' \
 *       http://DEVICE:4180/diag/exec
 */
object DiagServer {
    const val PORT = 4180
    private const val MAX_OUTPUT = 4 * 1024 * 1024

    // 60s, not 180. A hung command holds its worker for the whole timeout, and
    // with a small pool two of them starve every later request -- which reads
    // on the client as a transport that randomly times out.
    private const val TIMEOUT_SECONDS = 60L

    private val started = AtomicBoolean(false)
    private val pool = Executors.newFixedThreadPool(8)

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var lastCall: String = "none"
        private set

    val isRunning: Boolean
        get() = started.get()

    fun start(ctx: Context) {
        if (!Prefs.diagnosticMode) return
        if (!started.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread {
            var backoffSec = 1L
            while (started.get()) {
                try {
                    ServerSocket(PORT, 8, InetAddress.getByName("0.0.0.0")).use { server ->
                        backoffSec = 1L
                        while (started.get()) {
                            val socket = server.accept()
                            pool.execute { serve(app, socket) }
                        }
                    }
                } catch (e: Exception) {
                    if (!started.get()) break
                    lastError = "${e.javaClass.simpleName}: ${e.message}"
                    // A transient accept failure used to be terminal: the port went
                    // quiet for the rest of the process's life while the app looked
                    // perfectly healthy, which reads from outside as ECONNREFUSED
                    // with diagnostics switched on. Rebind instead of giving up.
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

    private fun serve(ctx: Context, socket: Socket) {
        try {
            socket.use { s ->
                val input = s.getInputStream()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrElse(0) { "" }.uppercase()
                val path = parts.getOrElse(1) { "/" }

                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }

                if (!authorized(headers["authorization"])) {
                    reply(
                        s,
                        401,
                        "unauthorized\n",
                        mapOf("WWW-Authenticate" to "Basic realm=\"aopencode-diag\""),
                    )
                    return
                }

                lastCall = "$method $path"
                when {
                    path == "/diag/ping" -> reply(s, 200, "ok\n")
                    // The shim's own report card: whether the patch reached the
                    // document and whether URL.canParse exists as a function.
                    // On Chromium 114 a missing canParse throws inside
                    // ServerConnection.local(), so "true/function" is the only
                    // value that means the UI is safe. Anything else needs the
                    // inject reason alongside to tell "not installed" from
                    // "installed but the call site is uncovered".
                    path == "/diag/shim" -> reply(
                        s,
                        200,
                        "installed=${WebViewShim.installedState}\n" +
                            "inject=${WebViewShim.lastInject}\n",
                    )
                    // The proxy keeps a bounded record of every tunnel: method,
                    // path, upstream host and how it ended. The agent's 404 body
                    // is written by a server that does not have the route it was
                    // given, and this is the only place that says what route that
                    // was. Reading it here beats reasoning about the composer.
                    path == "/diag/conns" -> reply(s, 200, HttpConnectProxy.connDump() + "\n")
                    // The SPA's console, buffered whether or not anyone was
                    // attached. Read this first for any UI-side failure.
                    path == "/diag/console" -> reply(s, 200, WebConsole.dump() + "\n")
                    // State of the DevTools bridge: the abstract socket name,
                    // the TCP port, and how many tunnels are live.
                    path == "/diag/cdp" -> reply(s, 200, CdpForwarder.status() + "\n")
                    // A freshly minted pairing link for every address this device
                    // answers on. Minting here rather than hand-writing a link is
                    // the point: the code is single-use and lives five minutes, so
                    // anything typed out by hand and carried over is stale by the
                    // time someone tries it, and the failure blames the link.
                    path == "/diag/pairlink" -> reply(s, 200, pairLink())
                    path.startsWith("/diag/exec") -> {
                        val len = headers["content-length"]?.toIntOrNull() ?: 0
                        val cmd = String(readExact(input, len), Charsets.UTF_8).trim()
                        if (cmd.isEmpty()) {
                            reply(s, 400, "empty command\n")
                            return
                        }
                        exec(ctx, cmd, s)
                    }
                    else -> reply(s, 404, "not found\n")
                }
            }
        } catch (_: Exception) {
            // A client that hangs up mid-request is not worth losing the listener over.
        }
    }

    /**
     * Mints a pairing code against the local backend and renders one link per
     * address this device is reachable at.
     *
     * Loopback is sorted last on purpose. It does work when the link is used on
     * this device, but a loopback link handed to another machine points at that
     * machine's own loopback, which is how a perfectly valid pairing reads as
     * "не робит". The addresses that actually mean something across a network
     * come first.
     */
    private fun pairLink(): String {
        val port = BackendManager.port
        if (port <= 0) return "backend is not running: no port has been assigned yet\n"
        val auth = "Basic " + android.util.Base64.encodeToString(
            ("opencode:" + Prefs.backendToken).toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )

        val minted = try {
            val body = http("POST", "http://127.0.0.1:$port/api/pair", auth)
            org.json.JSONObject(body)
        } catch (e: Exception) {
            return "could not mint a code against 127.0.0.1:$port: " +
                "${e.javaClass.simpleName}: ${e.message}\n"
        }
        val code = minted.optString("code")
        if (code.isEmpty()) {
            return "the backend returned no code: ${minted.toString().take(240)}\n"
        }
        val ttl = minted.optInt("expires_in", 300)

        val advertised = try {
            val info = org.json.JSONObject(http("GET", "http://127.0.0.1:$port/api/info", auth))
            val arr = info.optJSONArray("urls")
            (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }

        val bases = (advertised + "http://127.0.0.1:$port").distinct()
            .sortedBy { it.contains("127.0.0.1") }

        val sb = StringBuilder()
        sb.append("pairing code: $code   (single use, expires in ${ttl}s)\n\n")
        for (base in bases) sb.append("$base/auth/connect/$code\n")
        sb.append("\nUse a non-loopback line for anything outside this device.\n")
        sb.append("The code dies with the backend process: it is cached in memory, not on disk.\n")
        return sb.toString()
    }

    private fun http(method: String, url: String, auth: String): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Authorization", auth)
        if (method == "POST") {
            conn.doOutput = true
            conn.outputStream.use { it.write(ByteArray(0)) }
        }
        val status = conn.responseCode
        val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        if (status !in 200..299) {
            throw IllegalStateException("HTTP $status from $url: ${body.take(200)}")
        }
        return body
    }

    private fun exec(ctx: Context, cmd: String, socket: Socket) {
        // Bionic's sh, not the bundled bash. The bundled bash cannot resolve a
        // bare command name here: its PATH lookup uses faccessat2, which this
        // ROM's seccomp filter traps as SIGSYS, so `ls` dies with 159 while the
        // identical absolute path returns 0. See the note in
        // BackendManager.applyAgentEnv, which sets the agent's SHELL to the
        // same interpreter -- running the diagnostic here rather than in bash
        // is what keeps this endpoint measuring the environment the agent
        // actually has, and it makes bare commands usable over diag.
        val shell = File("/system/bin/sh")
        if (!shell.isFile) {
            reply(socket, 503, "no shell at ${shell.absolutePath}\n")
            return
        }
        // Output goes to a file, not a pipe. A pipe needs a reader kept in step
        // with the writer: any grandchild that inherits the descriptor holds it
        // open past the parent's exit, the reader blocks forever, and the worker
        // is lost. That leak is what made roughly half of all requests time out,
        // including trivial ones, which read as a broken runtime. There is no
        // pipe here, so there is nothing to leak.
        val outFile = File(ctx.cacheDir, "diag-${System.nanoTime()}.out")
        val proc = try {
            val pb = ProcessBuilder(shell.absolutePath, "-c", cmd)
                .redirectErrorStream(true)
                .redirectOutput(outFile)
                .directory(BackendManager.workspaceDir(ctx))
            BackendManager.applyAgentEnv(ctx, pb.environment())
            pb.start()
        } catch (e: Exception) {
            reply(socket, 500, "spawn failed: ${e.javaClass.simpleName}: ${e.message}\n")
            return
        }

        if (!proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            reply(
                socket,
                504,
                "timed out after ${TIMEOUT_SECONDS}s\n" + readOut(outFile),
            )
            return
        }
        reply(
            socket,
            200,
            readOut(outFile),
            mapOf("X-Exit" to proc.exitValue().toString()),
        )
    }

    private fun readOut(file: File): String {
        val text = try {
            if (file.isFile) String(file.readBytes(), Charsets.UTF_8) else ""
        } catch (e: Exception) {
            "(could not read output: ${e.javaClass.simpleName})"
        }
        file.delete()
        return if (text.length > MAX_OUTPUT) text.take(MAX_OUTPUT) + "\n… truncated" else text
    }

    private fun authorized(header: String?): Boolean {
        if (header == null || !header.startsWith("Basic ")) return false
        val decoded = try {
            String(
                android.util.Base64.decode(header.substring(6).trim(), android.util.Base64.DEFAULT),
                Charsets.UTF_8,
            )
        } catch (e: Exception) {
            return false
        }
        val i = decoded.indexOf(':')
        if (i < 0) return false
        return decoded.substring(i + 1) == Prefs.backendToken
    }

    private fun reply(socket: Socket, status: Int, body: String, extra: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder()
        head.append("HTTP/1.1 $status ${statusText(status)}\r\n")
        head.append("Content-Type: text/plain; charset=utf-8\r\n")
        head.append("Content-Length: ${bytes.size}\r\n")
        head.append("Connection: close\r\n")
        for ((key, value) in extra) head.append("$key: $value\r\n")
        head.append("\r\n")
        try {
            socket.use { s ->
                val out = s.getOutputStream()
                out.write(head.toString().toByteArray(Charsets.US_ASCII))
                out.write(bytes)
                out.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Status"
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream(96)
        var c = input.read()
        if (c == -1) return null
        while (c != -1 && c != '\n'.code) {
            if (c != '\r'.code) out.write(c)
            c = input.read()
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun readExact(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(if (n > 0) n else 0)
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r <= 0) break
            off += r
        }
        return if (off == buf.size) buf else buf.copyOf(off)
    }
}
