package dev.opencode.mobile

import android.content.Context
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

    fun runtimeDir(ctx: Context): File = File(ctx.filesDir, "runtime").also { it.mkdirs() }

    fun homeDir(ctx: Context): File = File(homeBase(ctx), "home").also { it.mkdirs() }

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
     * Everything needed to diagnose a failed start as one string: the launch
     * error if exec threw, whether the process is alive, and the backend's own
     * output. A process that starts but never binds is a different failure from
     * one that cannot exec at all, and this distinguishes them.
     */
    fun failureReport(ctx: Context): String {
        val parts = mutableListOf<String>()
        parts.add(lastError?.let { "Launch error:\n$it" } ?: "No launch exception was raised.")
        parts.add(
            if (isRunning()) {
                "Process: alive, but HTTP never answered on port $port"
            } else {
                "Process: not running"
            }
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

    /** Quick liveness probe of the HTTP server. */
    fun health(): Boolean {
        val p = port
        if (p <= 0) return false
        return try {
            val conn = URL("http://127.0.0.1:$p/").openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        } catch (_: Exception) {
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
    }
}
