package dev.opencode.mobile

import android.content.Context
import android.system.Os
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Unpacks the bundled Alpine userland and makes it reachable from a shell.
 *
 * Unzipping is the easy part. Two things stand between an Alpine package tree
 * and a working command on Android:
 *
 *  1. Every Alpine binary has `PT_INTERP = /lib/ld-musl-aarch64.so.1`, and
 *     that path does not exist here, so the kernel cannot start any of them.
 *     Each binary therefore gets a `#!/system/bin/sh` wrapper in a directory
 *     that is on `PATH`; the wrapper runs it through the musl loader we
 *     already ship for opencode itself.
 *
 *  2. `java.util.zip` cannot create a symlink, and Alpine's tree is held
 *     together by them — every busybox applet (`env`, `ls`, `sh`, ...) is a
 *     link to `/bin/busybox`. The archive carries a `__SYMLINKS__.txt`
 *     manifest instead and the links are recreated with `Os.symlink()`, which
 *     is allowed in app-private storage.
 *
 * The Alpine tree itself is never put on `PATH`: only the generated wrapper
 * directory is, so nothing in `root/` can be exec'd in a way that fails.
 */
object RootfsExtractor {
    private const val ASSET = "runtime/env.zip"
    private const val LINKS_ENTRY = "__SYMLINKS__.txt"

    /** The merged Alpine tree. Not on PATH. */
    fun rootDir(ctx: Context): File = File(BackendManager.envDir(ctx), "root")

    /** Generated `#!/system/bin/sh` entry points. This is the directory on PATH. */
    fun binDir(ctx: Context): File = File(BackendManager.envDir(ctx), "bin")

    /**
     * Multi-call binaries, which resolve their applet from argv[0] but also
     * accept it as the first argument. Everything else that was aliased ships as
     * a real copy in the tree, so it needs no special handling here.
     */
    private val MULTICALL = setOf("busybox", "coreutils")

    /** Where scripts with shebangs live in an Alpine tree. */
    private val SHEBANG_DIRS = listOf("bin", "usr/bin", "usr/sbin", "sbin", "usr/libexec")

    /**
     * End-to-end checks, run once after unpacking, so a broken tree is visible.
     * Each one covers a different mechanism: the multicall paths, an alias copy,
     * and plain binaries through the loader.
     */
    private val VERIFICATIONS = listOf(
        // The whole chain in one command: wrapper -> env -> PATH lookup -> bash.
        "env+bash" to arrayOf("env", "bash", "-c", "echo ok"),
        "coreutils" to arrayOf("cat", "/dev/null"),
        "busybox" to arrayOf("sh", "-c", "echo ok"),
        "git" to arrayOf("git", "--version"),
        "python3" to arrayOf("python3", "--version"),
        "curl" to arrayOf("curl", "--version"),
        "jq" to arrayOf("jq", "--version"),
        "ssh" to arrayOf("ssh", "-V"),
        "perl" to arrayOf("perl", "-e", "print qq{ok}"),
        "find" to arrayOf("find", "--version"),
        "xargs" to arrayOf("xargs", "--version"),
        "awk" to arrayOf("awk", "BEGIN{print \"ok\"}"),
        // Runtimes: actually evaluate something, not just print a version.
        "node" to arrayOf("node", "-e", "console.log(6*7)"),
        "npm" to arrayOf("npm", "--version"),
        "pip" to arrayOf("pip3", "--version"),
        "fd" to arrayOf("fd", "--version"),
        "sqlite" to arrayOf("sqlite3", ":memory:", "select 6*7;"),
        "rsync" to arrayOf("rsync", "--version"),
    )

    fun isReady(ctx: Context): Boolean =
        Prefs.rootfsStamp == BuildConfig.VERSION_NAME &&
            binDir(ctx).listFiles()?.isNotEmpty() == true

    /**
     * Extracts the bundled rootfs. A no-op when the current version already
     * unpacked it.
     *
     * @return a short human-readable summary, also stored in [Prefs] so the
     *   diagnostics report can show it without re-running anything.
     */
    fun extract(ctx: Context): String {
        val zip = try {
            ctx.assets.open(ASSET)
        } catch (_: Exception) {
            val msg = "rootfs not bundled (run scripts/build-rootfs.py)"
            Prefs.rootfsStatus = msg
            return msg
        }

        val root = rootDir(ctx)
        val bin = binDir(ctx)
        root.deleteRecursively()
        bin.deleteRecursively()
        root.mkdirs()
        bin.mkdirs()

        var files = 0
        var manifestBytes: ByteArray? = null

        try {
            zip.use { input ->
                val zin = ZipInputStream(input)
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (entry.isDirectory) continue
                    // Held back, not created here: the manifest is the first entry,
                    // so at that point none of the files it links to exist yet.
                    if (entry.name == LINKS_ENTRY) {
                        manifestBytes = zin.readBytes()
                        continue
                    }
                    val target = File(root, entry.name)
                    // Guard against a zip entry escaping the root (../).
                    if (!target.canonicalFile.path.startsWith(root.canonicalFile.path)) continue
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> zin.copyTo(out, 1 shl 16) }
                    target.setExecutable(true, true)
                    files++
                }
            }
        } catch (e: Exception) {
            val msg = "rootfs unzip failed: ${e.javaClass.simpleName}: ${e.message}"
            Prefs.rootfsStatus = msg
            return msg
        }

        val links = createLinks(manifestBytes ?: ByteArray(0), root)
        val wrappers = writeWrappers(ctx, root, bin)
        val shebangs = rewriteShebangs(root, bin)
        val failures = verify(bin)

        val summary = buildString {
            append("$files files, $links symlinks, $wrappers wrappers, $shebangs shebangs rewritten")
            if (failures.isNotEmpty()) append(" | FAILED: ").append(failures.joinToString(", "))
        }
        Prefs.rootfsStamp = BuildConfig.VERSION_NAME
        Prefs.rootfsStatus = summary
        return summary
    }

    /**
     * Recreates the symlink manifest.
     *
     * In rounds, because a link can point at another link
     * (`libfoo.so -> libfoo.so.1 -> libfoo.so.1.0`) and the intermediate has
     * to exist before the outer one resolves.
     */
    private fun createLinks(manifest: ByteArray, root: File): Int {
        var pending = manifest.toString(Charsets.UTF_8)
            .lineSequence()
            .map { it.split('\t') }
            .filter { it.size == 2 && it[0].isNotBlank() }
            .toList()
        val madeTargets = mutableSetOf<String>()
        var made = 0
        for (round in 0 until 6) {
            if (pending.isEmpty()) break
            val remaining = mutableListOf<List<String>>()
            val before = made
            for (parts in pending) {
                val target = File(root, parts[1])
                if (!target.exists() && parts[1] !in madeTargets) {
                    remaining.add(parts)
                    continue
                }
                val link = File(root, parts[0])
                link.parentFile?.mkdirs()
                try {
                    link.delete()
                    Os.symlink(target.absolutePath, link.absolutePath)
                    made++
                    madeTargets.add(parts[0])
                } catch (_: Exception) {
                    // A link we cannot make is a missing tool, not a fatal error;
                    // the verification pass below reports it.
                }
            }
            pending = remaining
            if (made == before) break
        }
        return made
    }

    /**
     * One wrapper per executable found in the Alpine `bin` and `usr/bin`.
     *
     * Busybox applets are the special case: busybox picks its behaviour from
     * argv[0], and going through the loader would set argv[0] to the busybox
     * binary itself. Passing the applet name as the first argument is the
     * documented equivalent (`busybox ls -l`), and `$(basename "$0")` keeps
     * one identical script for every applet.
     */
    private fun writeWrappers(ctx: Context, root: File, bin: File): Int {
        val runtime = BackendManager.runtimeDir(ctx)
        val loader = File(runtime, "ld-musl-aarch64.so.1").absolutePath
        val libraryPath = listOf(File(root, "usr/lib"), File(root, "lib"), runtime)
            .joinToString(":") { it.absolutePath }

        var written = 0
        for (dir in listOf(File(root, "bin"), File(root, "usr/bin"))) {
            val entries = dir.listFiles() ?: continue
            for (entry in entries) {
                val real = try {
                    entry.canonicalFile
                } catch (_: Exception) {
                    continue
                }
                if (!real.isFile || !isElf(real)) continue

                val applet =
                    if (real.name in MULTICALL && entry.name != real.name) " \"\$(basename \"\$0\")\""
                    else ""
                val script =
                    "#!/system/bin/sh\n" +
                        "exec \"$loader\" --library-path \"$libraryPath\" \"${real.absolutePath}\"$applet \"\$@\"\n"
                val wrapper = File(bin, entry.name)
                wrapper.writeText(script)
                wrapper.setExecutable(true, true)
                written++
            }
        }
        return written
    }

    /**
     * Repoints `#!` lines at the wrapper directory.
     *
     * The kernel resolves a shebang by absolute path, and `/usr/bin/env` does
     * not exist on Android — so `#!/usr/bin/env bash` cannot start no matter
     * what is on `PATH`. Pointing the interpreter at the same basename we
     * generated in [bin] makes the identical script work, and anything we
     * cannot satisfy is left exactly as it was.
     *
     * Operated on bytes rather than a decoded string: these are other people's
     * scripts, and only the interpreter line is rewritten.
     */
    private fun rewriteShebangs(root: File, bin: File): Int {
        var rewritten = 0
        for (dirName in SHEBANG_DIRS) {
            for (file in walk(File(root, dirName))) {
                if (!file.isFile || isElf(file)) continue
                val bytes = file.readBytes()
                if (bytes.size < 2 || bytes[0] != '#'.code.toByte() || bytes[1] != '!'.code.toByte()) continue
                val newline = bytes.indexOf('\n'.code.toByte())
                val lineEnd = if (newline == -1) bytes.size else newline
                val line = String(bytes, 2, lineEnd - 2, Charsets.UTF_8).trimEnd('\r', ' ', '\t')
                val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (tokens.isEmpty()) continue
                val name = File(tokens[0]).name
                val replacement = File(bin, name)
                if (!replacement.exists()) continue
                val rest = tokens.drop(1)
                val newLine = "#!" + replacement.absolutePath + if (rest.isEmpty()) "" else " " + rest.joinToString(" ")
                if (newLine == line) continue
                val newBytes = newLine.toByteArray(Charsets.UTF_8)
                val tail = if (newline == -1) ByteArray(0) else bytes.copyOfRange(newline, bytes.size)
                file.outputStream().use { it.write(newBytes + tail) }
                rewritten++
            }
        }
        return rewritten
    }

    /** Depth-limited walk: the tree contains symlinks and must not be looped through. */
    private fun walk(top: File, maxDepth: Int = 4): List<File> {
        val out = mutableListOf<File>()
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(top to 0)
        while (stack.isNotEmpty()) {
            val (dir, depth) = stack.removeLast()
            val entries = dir.listFiles() ?: continue
            for (entry in entries) {
                if (entry.isDirectory) {
                    if (depth < maxDepth) stack.addLast(entry to depth + 1)
                } else {
                    out.add(entry)
                }
            }
        }
        return out
    }

    private fun isElf(file: File): Boolean {
        if (file.length() < 4) return false
        val magic = ByteArray(4)
        file.inputStream().use { if (it.read(magic) != 4) return false }
        return magic[0] == 0x7f.toByte() && magic[1] == 'E'.code.toByte() &&
            magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
    }

    /**
     * Runs each check through the wrapper directory exactly the way the agent
     * would, so this exercises wrappers, symlinks, the loader and library
     * resolution together rather than any one of them in isolation.
     */
    private fun verify(bin: File): MutableList<String> {
        val failures = mutableListOf<String>()
        val results = VERIFICATIONS.map { (name, args) ->
            val outcome = try {
                val proc = ProcessBuilder(listOf(File(bin, args[0]).absolutePath) + args.drop(1))
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.bufferedReader().readText().trim()
                if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    failures.add(name)
                    "$name=timeout"
                } else if (proc.exitValue() != 0) {
                    failures.add(name)
                    "$name=exit=${proc.exitValue()} ${output.take(80)}"
                } else {
                    "$name=ok"
                }
            } catch (e: Exception) {
                failures.add(name)
                "$name=${e.javaClass.simpleName}"
            }
            outcome
        }
        Prefs.rootfsChecks = results.joinToString(" ")
        return failures
    }
}
