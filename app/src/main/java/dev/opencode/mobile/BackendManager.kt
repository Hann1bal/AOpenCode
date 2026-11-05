package dev.opencode.mobile

import android.content.Context
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import dev.opencode.mobile.sync.PeerStore
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Owns the bundled `opencode serve` process.
 *
 * The backend is a linux-arm64-musl binary extracted from app assets into
 * app-private storage, launched through the bundled musl loader:
 *
 *   ld-musl-aarch64.so.1 --library-path <runtimeDir> <runtimeDir>/opencode serve ...
 *
 * It binds to 127.0.0.1 on a random port in 4096..4196 and is never exposed
 * beyond the loopback interface.
 */
object BackendManager {
    private const val TAG = "BackendManager"

    @Volatile
    private var process: Process? = null

    @Volatile
    var port: Int = -1
        private set

    /**
     * Last startup failure, kept so the UI can render the real reason instead of
     * a generic "could not start". Cleared whenever a launch is attempted.
     */
    @Volatile
    var lastError: String? = null
        private set

    /** Outcome of the most recent health probe, for the failure report. */
    @Volatile
    var lastProbe: String? = null
        private set

    fun runtimeDir(ctx: Context): File = File(ctx.filesDir, "runtime").also { it.mkdirs() }

    fun homeDir(ctx: Context): File = File(homeBase(ctx), "home").also { it.mkdirs() }

    /**
     * The directory the backend runs in, and the one the UI should register as a
     * project.
     *
     * Without an explicit cwd the process inherits Android's default working
     * directory of `/`. The server resolves that as a project root, and `/` is
     * neither writable nor listable by an app — the failure surfaces as an opaque
     * HTTP 500 ("UnexpectedStatus") when the user tries to create a project.
     * A real, app-owned, writable directory removes that whole class of failure.
     */
    fun workspaceDir(ctx: Context): File = File(homeBase(ctx), "workspace").also { it.mkdirs() }

    private fun homeBase(ctx: Context): File = File(ctx.filesDir, "backend").also { it.mkdirs() }

    fun isExtracted(ctx: Context): Boolean {
        val dir = runtimeDir(ctx)
        val stamp = File(dir, ".stamp")
        return stamp.exists() &&
            stamp.readText() == BuildConfig.VERSION_CODE.toString() &&
            File(dir, "opencode").exists() &&
            File(dir, "ld-musl-aarch64.so.1").exists()
    }

    /** Copy bundled assets into app-private storage and mark executable. */
    fun extract(ctx: Context, onProgress: (Float) -> Unit = {}) {
        val dir = runtimeDir(ctx)
        dir.deleteRecursively()
        dir.mkdirs()
        val names = ctx.assets.list("runtime")?.toList() ?: emptyList()
        if (names.isEmpty()) {
            throw IllegalStateException("assets/runtime is empty — run scripts/prepare-assets.sh before building")
        }
        val total = names.size.toFloat()
        names.forEachIndexed { i, name ->
            ctx.assets.open("runtime/$name").use { input ->
                File(dir, name).outputStream().use { out -> input.copyTo(out) }
            }
            File(dir, name).setExecutable(true, true)
            onProgress((i + 1f) / total)
        }
        File(dir, ".stamp").writeText(BuildConfig.VERSION_CODE.toString())
        Prefs.extractStamp = BuildConfig.VERSION_CODE
        verifyRipgrep(ctx)
    }

    /**
     * Runs the bundled ripgrep shim once, after extraction, and records the
     * outcome. The agent's glob/grep tools depend on it, and a shim that is
     * present but cannot exec looks identical to a missing one from the outside.
     */
    private fun verifyRipgrep(ctx: Context) {
        val dir = runtimeDir(ctx)
        val rg = File(dir, "rg")
        if (!rg.exists()) {
            Prefs.ripgrepStatus = "missing (not bundled)"
            return
        }
        Prefs.ripgrepStatus = try {
            val proc = ProcessBuilder(rg.absolutePath, "--version")
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            val finished = proc.waitFor(20, TimeUnit.SECONDS)
            when {
                !finished -> "timeout"
                proc.exitValue() != 0 -> "exit=${proc.exitValue()} ${output.take(200)}"
                else -> output.lineSequence().firstOrNull()?.ifBlank { "ok" } ?: "ok"
            }
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun isRunning(): Boolean = process?.isAlive == true

    /** Find a free TCP port on loopback. */
    private fun findFreePort(): Int {
        for (candidate in 4096..4196) {
            try {
                ServerSocket(candidate, 50, InetAddress.getByName("127.0.0.1")).use { return it.localPort }
            } catch (_: Exception) {
                // port busy, try next
            }
        }
        throw IllegalStateException("No free loopback port in 4096..4196")
    }

    /** Launch the backend. Returns the port it is bound to. */
    @Synchronized
    fun start(ctx: Context): Int {
        if (isRunning() && health()) return port
        stop()
        lastError = null

        val dir = runtimeDir(ctx)
        val loader = File(dir, "ld-musl-aarch64.so.1")
        val bin = File(dir, "opencode")
        if (!loader.exists() || !bin.exists()) {
            val msg = "Backend not extracted: loader=${loader.exists()} bin=${bin.exists()} in ${dir.absolutePath}"
            lastError = msg
            throw IllegalStateException(msg)
        }

        val p = findFreePort()
        val logFile = File(ctx.cacheDir, "backend.log")

        val pb = ProcessBuilder(
            loader.absolutePath,
            "--library-path", dir.absolutePath,
            bin.absolutePath,
            "serve",
            "--hostname", "127.0.0.1",
            "--port", p.toString()
        )
        pb.redirectErrorStream(true)
        pb.redirectOutput(logFile)
        // Do not let the server inherit Android's cwd of `/`; see workspaceDir().
        pb.directory(workspaceDir(ctx))

        val home = homeDir(ctx)
        val env = pb.environment()
        env["HOME"] = home.absolutePath
        env["XDG_CONFIG_HOME"] = File(home, ".config").absolutePath
        env["XDG_DATA_HOME"] = File(File(home, ".local"), "share").absolutePath
        env["XDG_CACHE_HOME"] = File(home, ".cache").absolutePath
        env["TMPDIR"] = ctx.cacheDir.absolutePath
        env["PATH"] = dir.absolutePath + ":/system/bin:/bin"
        // Passed for defense-in-depth; current opencode does not enforce it, but
        // future versions may. The real protection is loopback-only + random port.
        env["OPENCODE_SERVER_USERNAME"] = "opencode"
        env["OPENCODE_SERVER_PASSWORD"] = Prefs.backendToken
        // Never let a system proxy intercept loopback traffic
        env.remove("HTTP_PROXY")
        env.remove("HTTPS_PROXY")
        env.remove("http_proxy")
        env.remove("https_proxy")
        env["NO_PROXY"] = "127.0.0.1,localhost"

        // Live session sharing: if a peer is paired, the forked backend follows
        // its durable event log and mirrors the upstream sessions locally.
        PeerStore.active()?.let { peer ->
            env["OPENCODE_UPSTREAM_URL"] = peer.baseUrl
            env["OPENCODE_UPSTREAM_TOKEN"] = peer.token
            env["OPENCODE_UPSTREAM_ID"] = peer.id
        }

        // Up before the backend: musl resolves to 127.0.0.1:53 by default, and
        // without this every outbound lookup in the agent times out.
        DnsForwarder.start()

        process = try {
            pb.start()
        } catch (e: Exception) {
            val msg = describeLaunchFailure(loader, bin, dir, e)
            lastError = msg
            throw IllegalStateException(msg, e)
        }
        port = p
        Prefs.backendPort = p
        Log.i(TAG, "Backend started pid=${process?.hashCode()} port=$p")
        return p
    }

    /**
     * A bare `Cannot run program ...` cannot be diagnosed from a notification: the
     * same wording covers EACCES (W^X policy), ENOEXEC (wrong arch / corrupt ELF)
     * and ENOENT (extraction never ran). Report the file state and the target SDK
     * so the cause is readable on the device itself.
     */
    private fun describeLaunchFailure(loader: File, bin: File, dir: File, e: Exception): String {
        fun probe(f: File): String =
            "${f.name}: exists=${f.exists()} canExecute=${f.canExecute()} len=${if (f.exists()) f.length() else -1}"
        return buildString {
            append("Backend launch failed: ").append(e.message).append('\n')
            append(probe(loader)).append(" | ").append(probe(bin)).append('\n')
            append("dir=").append(dir.absolutePath)
            append(" targetSdk=").append(BuildConfig.TARGET_SDK)
            append(" abi=").append(android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
        }
    }

    /** Last [maxLines] lines of the backend's captured stdout/stderr. */
    fun logTail(ctx: Context, maxLines: Int = 60): String {
        val f = File(ctx.cacheDir, "backend.log")
        if (!f.exists()) return ""
        return try {
            val lines = f.readLines()
            lines.drop(maxOf(0, lines.size - maxLines)).joinToString("\n")
        } catch (e: Exception) {
            "(could not read backend.log: ${e.message})"
        }
    }

    /**
     * The engine the UI runs on, which is what decides which JS APIs exist. On
     * Huawei/HarmonyOS there is no Play Store, so the WebView never moves forward
     * and this is the number that explains a missing-API TypeError.
     */
    fun webViewVersion(): String {
        val pkg = android.webkit.WebView.getCurrentWebViewPackage()
        return if (pkg == null) "unknown" else "${pkg.packageName} ${pkg.versionName}"
    }

    /**
     * Whether the ROM is free to freeze us in the background. Huawei's aggressive
     * management kills the server process, which looks like a random backend death.
     */
    fun batteryOptimized(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /**
     * Everything needed to diagnose a failed start as one string: the launch
     * error if exec threw, whether the process is alive, the JS engine the UI is
     * running on, and whether the ROM is free to freeze us. A process that
     * starts but never binds is a different failure from one that cannot exec at
     * all, and this distinguishes them.
     */
    fun failureReport(ctx: Context): String {
        val parts = mutableListOf<String>()
        parts.add(lastError?.let { "Launch error:\n$it" } ?: "No launch exception was raised.")
        parts.add(
            if (isRunning()) {
                "Process: alive on port $port, but the health probe never passed " +
                    "(last probe: ${lastProbe ?: "none"})"
            } else {
                "Process: not running"
            }
        )
        parts.add("UI engine: ${webViewVersion()}")
        parts.add(
            if (batteryOptimized(ctx))
                "Battery optimization: ACTIVE — the ROM may freeze or kill the backend"
            else
                "Battery optimization: exempt"
        )
        val tail = logTail(ctx)
        parts.add(
            if (tail.isBlank())
                "(backend.log is empty — the process produced no output at all)"
            else
                "backend.log, last lines:\n$tail"
        )
        return parts.joinToString("\n\n")
    }

    /**
     * The full state of the app and its backend as one copyable string. A 500
     * from a live server never reaches the failure overlay, so this is how the
     * log gets off the device without adb.
     */
    fun diagnosticsReport(ctx: Context): String = buildString {
        appendLine("AOpenCode ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine(
            "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                "android=${android.os.Build.VERSION.RELEASE} (sdk ${android.os.Build.VERSION.SDK_INT})",
        )
        appendLine(
            "targetSdk=${BuildConfig.TARGET_SDK} " +
                "abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}",
        )
        appendLine("engine=${webViewVersion()}")
        appendLine(
            if (batteryOptimized(ctx)) "battery=OPTIMIZED — the ROM may freeze the backend"
            else "battery=exempt",
        )
        val backendState = if (isRunning()) "running on port $port" else "not running"
        appendLine("backend=$backendState lastProbe=${lastProbe ?: "none"}")
        val ws = workspaceDir(ctx)
        appendLine("workspace=${ws.absolutePath} writable=${ws.canWrite()}")
        appendLine("mode=${PeerStore.active()?.let { "following ${it.name}" } ?: "local agent"}")
        appendLine(
            if (DnsForwarder.running) "dns=forwarder listening on 127.0.0.1:53"
            else "dns=NOT RUNNING (${DnsForwarder.lastError ?: "not started"}) — outbound lookups will fail",
        )
        appendLine("ripgrep=${Prefs.ripgrepStatus}")
        appendLine()
        appendLine("backend.log, last lines:")
        appendLine(logTail(ctx, 150).ifBlank { "(empty — the process produced no output)" })
    }

    /** Quick liveness probe of the HTTP server. */
    fun health(): Boolean {
        val p = port
        if (p <= 0) return false
        return try {
            val conn = URL("http://127.0.0.1:$p/").openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            // The server enforces Basic auth as soon as a password is configured,
            // and its username is hardcoded to "opencode" (ServerAuth.Config).
            // Sending no header meant every probe drew a 401, which the old
            // `code in 200..399` test read as "server not started".
            val raw = "opencode:" + Prefs.backendToken
            conn.setRequestProperty(
                "Authorization",
                "Basic " + Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)
            )
            val code = conn.responseCode
            lastProbe = "HTTP $code"
            conn.disconnect()
            // Any HTTP answer proves the listener is alive. 401/403 are included
            // deliberately: the WebView satisfies the challenge itself, so a
            // credentials mismatch must not look like a dead backend.
            code in 200..499
        } catch (e: Exception) {
            lastProbe = "${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    @Synchronized
    fun stop() {
        process?.let { proc ->
            try {
                proc.destroy()
                proc.waitFor(3, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
            if (proc.isAlive) {
                try {
                    proc.destroyForcibly()
                } catch (_: Exception) {
                }
            }
        }
        process = null
        port = -1
        DnsForwarder.stop()
    }
}
