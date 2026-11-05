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
import java.net.URI
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

    /** Where the Alpine userland lives: `root/` is the tree, `bin/` is on PATH. */
    fun envDir(ctx: Context): File = File(homeBase(ctx), "env").also { it.mkdirs() }

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

    /**
     * Whether the bundled runtime on disk belongs to *this* version.
     *
     * Keyed on versionName because versionCode never moves: with the code as the
     * stamp, an update installed new assets into the APK and then reused the old
     * extracted binaries forever.
     */
    fun isExtracted(ctx: Context): Boolean {
        val dir = runtimeDir(ctx)
        val stamp = File(dir, ".stamp")
        return stamp.exists() &&
            stamp.readText() == BuildConfig.VERSION_NAME &&
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
        File(dir, ".stamp").writeText(BuildConfig.VERSION_NAME)
        Prefs.extractStamp = BuildConfig.VERSION_NAME
        verifyRipgrep(ctx)
        // The userland is the slow part (~30 MB, 1.5k files, then wrappers and
        // shebangs). It runs here, on the service's IO dispatcher, and its
        // failures are recorded rather than thrown: a missing rootfs costs tools,
        // not the app.
        RootfsExtractor.extract(ctx)
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
        // The wrapper directory goes first so the bundled tools win over
        // Android's toybox: consistent GNU behaviour is what scripts assume.
        val root = RootfsExtractor.rootDir(ctx)
        val envBin = RootfsExtractor.binDir(ctx)
        env["PATH"] = listOf(envBin.absolutePath, dir.absolutePath, "/system/bin", "/bin")
            .filter { File(it).isDirectory }
            .joinToString(":")
        // musl ships no trust store and /etc/ssl does not exist here, so every
        // TLS client in the tree has to be pointed at the bundled bundle by hand.
        val caBundle = File(root, "etc/ssl/certs/ca-certificates.crt")
        if (caBundle.isFile) {
            env["SSL_CERT_FILE"] = caBundle.absolutePath
            env["SSL_CERT_DIR"] = caBundle.parentFile.absolutePath
            env["CURL_CA_BUNDLE"] = caBundle.absolutePath
            env["GIT_SSL_CAINFO"] = caBundle.absolutePath
        }
        // Perl resolves its modules from a compiled-in /usr prefix that is not
        // where we unpacked it.
        existing(
            File(root, "usr/share/perl5/core_perl"),
            File(root, "usr/share/perl5/vendor_perl"),
            File(root, "usr/share/perl5/site_perl"),
        )?.let { env["PERL5LIB"] = it }
        // Alpine marks its Python as externally managed, so `pip install` refuses
        // by default. Here the whole tree is app-writable and thrown away on
        // every version bump, so the guard only blocks the useful case.
        env["PIP_BREAK_SYSTEM_PACKAGES"] = "1"
        // Pin npm's global prefix into the unpacked tree rather than let it
        // resolve against a prefix it cannot see.
        env["NPM_CONFIG_PREFIX"] = File(root, "usr").absolutePath
        // ssh refuses to run without a readable, writable ~/.ssh.
        File(home, ".ssh").mkdirs()
        // Passed for defense-in-depth; current opencode does not enforce it, but
        // future versions may. The real protection is loopback-only + random port.
        env["OPENCODE_SERVER_USERNAME"] = "opencode"
        env["OPENCODE_SERVER_PASSWORD"] = Prefs.backendToken

        // Outbound name resolution. musl reads /etc/resolv.conf, which Android
        // does not give us, so it falls back to 127.0.0.1:53 — and this ROM
        // refuses to bind that port, so a DNS forwarder cannot work. The proxy
        // sidesteps DNS entirely: it sends the *name* through Android's own
        // resolver. Bun's fetch honours these variables.
        HttpConnectProxy.start()
        val proxy = if (HttpConnectProxy.running) "http://127.0.0.1:${HttpConnectProxy.port}" else null
        if (proxy != null) {
            env["HTTP_PROXY"] = proxy
            env["HTTPS_PROXY"] = proxy
            env["http_proxy"] = proxy
            env["https_proxy"] = proxy
        } else {
            env.remove("HTTP_PROXY")
            env.remove("HTTPS_PROXY")
            env.remove("http_proxy")
            env.remove("https_proxy")
        }
        // Loopback must never round-trip through the proxy, and neither must the
        // paired upstream: that connection is already known-good on the LAN and
        // carries the live SSE feed we do not want rewritten.
        val noProxy = mutableListOf("127.0.0.1", "localhost", "::1")
        PeerStore.active()?.let { peer ->
            URI(peer.baseUrl).host?.let { noProxy.add(it) }
        }
        val noProxyList = noProxy.joinToString(",")
        env["NO_PROXY"] = noProxyList
        env["no_proxy"] = noProxyList

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
        // Also before the backend: glob and grep must find our rg instead of
        // trying to download a glibc build they cannot run.
        seedRipgrep(ctx)

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

    /** The directories among [dirs] that exist, joined as a `:`-separated list. */
    private fun existing(vararg dirs: File): String? =
        dirs.filter { it.isDirectory }.takeIf { it.isNotEmpty() }?.joinToString(":") { it.absolutePath }

    /**
     * Places the bundled ripgrep where opencode looks for it *before* it decides
     * to download one.
     *
     * `RipgrepBinary.filepath` tries PATH, then `<global.bin>/rg`, and only then
     * falls back to a GitHub release. On this platform that fallback is worse
     * than useless: `arm64-linux` maps to `aarch64-unknown-linux-gnu`, a glibc
     * build that cannot run under our musl loader, and a failed fetch surfaces
     * in the UI as a bare "Not Found" from the glob and grep tools.
     *
     * Seeding the file makes the download branch unreachable. Written with
     * absolute paths because the bundled `runtime/rg` shim resolves its loader
     * relative to itself and would not survive being copied here.
     */
    private fun seedRipgrep(ctx: Context) {
        val runtime = runtimeDir(ctx)
        val loader = File(runtime, "ld-musl-aarch64.so.1")
        val rg = File(runtime, "rg.musl")
        if (!loader.exists() || !rg.exists()) return
        val target = File(File(File(homeDir(ctx), ".cache"), "opencode"), "bin")
        if (!target.mkdirs() && !target.isDirectory) return
        val script = File(target, "rg")
        script.writeText(
            "#!/system/bin/sh\n" +
                "exec \"${loader.absolutePath}\" --library-path \"${runtime.absolutePath}\" " +
                "\"${rg.absolutePath}\" \"\$@\"\n",
        )
        script.setExecutable(true, false)
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
            else "dns=forwarder unavailable (${DnsForwarder.lastError ?: "not started"}) — the proxy below covers this",
        )
        appendLine(
            if (HttpConnectProxy.running)
                "proxy=http://127.0.0.1:${HttpConnectProxy.port} — outbound names resolve through Android"
            else
                "proxy=NOT RUNNING (${HttpConnectProxy.lastError ?: "not started"}) — outbound fetch will fail",
        )
        appendLine("ripgrep=${Prefs.ripgrepStatus}")
        appendLine("rootfs=${Prefs.rootfsStatus}")
        appendLine("storage=${File(ctx.filesDir.absolutePath).usableSpace / 1048576} MB free")
        appendLine("tools=${Prefs.rootfsChecks}")
        // "true/function" means the JS shim reached the document and URL.canParse exists.
        appendLine("shim=${WebViewShim.installedState}")
        appendLine("sw=${WebViewShim.lastPurge}")
        appendLine("web.errors=${WebDiagnostics.dump()}")
        // The last steps the process took before it died, newest last. If the app
        // closed without a message, this names the step it was in.
        appendLine("trace=${CrashLog.recentPhases()}")
        appendLine("crash=${CrashLog.lastErrorLine()}")
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
        HttpConnectProxy.stop()
    }
}
