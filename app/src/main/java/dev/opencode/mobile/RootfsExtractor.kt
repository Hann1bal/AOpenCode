package dev.opencode.mobile

import android.content.Context
import android.system.Os
import java.io.ByteArrayOutputStream
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

        val sources = listOf(File(root, "bin"), File(root, "usr/bin"))
            .flatMap { dir -> dir.listFiles()?.toList() ?: emptyList() }

        var written = 0

        // ELF first. Script wrappers below dispatch to an interpreter *wrapper*,
        // so those have to exist before the second pass goes looking for them.
        for (entry in sources) {
            val real = canonical(entry) ?: continue
            if (!real.isFile || !isElf(real)) continue

            val applet =
                if (real.name in MULTICALL && entry.name != real.name) " \"\$(basename \"\$0\")\""
                else ""
            putWrapper(
                File(bin, entry.name),
                "#!/system/bin/sh\n" +
                    "exec \"$loader\" --library-path \"$libraryPath\" \"${real.absolutePath}\"$applet \"\$@\"\n",
            )
            written++
        }

        // Scripts. This pass is what actually puts `npm` and `pip` on PATH: they
        // are not ELF at all but symlinks into node_modules and site-packages, so
        // an ELF-only pass silently skips the entire package manager.
        for (entry in sources) {
            val real = canonical(entry) ?: continue
            if (!real.isFile || isElf(real)) continue
            val interp = interpreterFor(real, bin) ?: continue
            // No `exec` here: it would replace the shell and skip the rehash.
            val body = "#!/system/bin/sh\n" +
                "\"${interp.absolutePath}\" \"${real.absolutePath}\" \"\$@\"\n" +
                "_rc=\$?\n" +
                rehashLoop(bin, ctx) +
                "exit \$_rc\n"
            putWrapper(File(bin, entry.name), body)
            written++
        }
        return written
    }

    /**
     * Resolves the interpreter a script asks for to a wrapper we already built.
     *
     * Handles the `#!/usr/bin/env X` form by taking the next token, since `env`
     * itself is not runnable here either. Returns null for anything we cannot
     * satisfy, and refuses to point a wrapper at itself.
     */
    private fun interpreterFor(script: File, bin: File): File? {
        val first = try {
            script.inputStream().use { ins ->
                val buf = ByteArrayOutputStream()
                while (true) {
                    val b = ins.read()
                    if (b == -1 || b == '\n'.code) break
                    buf.write(b)
                    if (buf.size() > 512) return null
                }
                buf.toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            return null
        }
        if (!first.startsWith("#!")) return null
        val tokens = first.substring(2).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        var name = File(tokens[0]).name
        if (name == "env") {
            if (tokens.size < 2) return null
            name = File(tokens[1]).name
        }
        val candidate = File(bin, name)
        if (!candidate.absoluteFile.equals(script.absoluteFile) && candidate.exists()) return candidate
        return null
    }

    /**
     * A shell loop that makes whatever the package manager just installed
     * reachable.
     *
     * `npm i -g` and `pip install` drop their entry points into a bin directory
     * that is not on `PATH`, and each one is a script whose `#!/usr/bin/env
     * node` shebang the kernel cannot resolve. Without this, a completely
     * successful install still leaves the command unrunnable, which is the
     * single most confusing failure mode of "I installed it, where is it".
     *
     * Written with shell builtins only — `read`, parameter expansion, `printf`,
     * redirection — plus `chmod`, because this runs under `/system/bin/sh`
     * where the bundled coreutils are exactly what we are trying to reach.
     *
     * Emitted after the real command runs, so it sees the installed files. The
     * exit status is preserved.
     */
    private fun rehashLoop(bin: File, ctx: Context): String {
        val dirs = listOf(
            File(rootDir(ctx), "usr/bin"),
            File(rootDir(ctx), "usr/local/bin"),
            File(BackendManager.homeDir(ctx), ".local/bin"),
        ).joinToString(" ") { "\"" + it.absolutePath + "/\"*" }
        val b = bin.absolutePath
        return listOf(
            "# rehash: materialise wrappers for anything installed above",
            "for _f in $dirs; do",
            "  [ -f \"\$_f\" ] || continue",
            "  _w=\"$b/\${_f##*/}\"",
            "  [ -e \"\$_w\" ] && continue",
            "  IFS= read -r _l <\"\$_f\" || _l=",
            "  case \"\$_l\" in '#!'*) _s=\${_l#'#!'} ;; *) continue ;; esac",
            "  set -- \$_s",
            "  _i=\${1##*/}",
            "  if [ \"\$_i\" = env ]; then shift; _i=\${1##*/}; fi",
            "  [ -n \"\$_i\" ] || continue",
            "  [ -x \"$b/\$_i\" ] || continue",
            "  printf '#!/system/bin/sh\\nexec \"%s/%s\" \"%s\" \"\$@\"\\n' \"$b\" \"\$_i\" \"\$_f\" >\"\$_w\"",
            "  chmod 755 \"\$_w\" 2>/dev/null",
            "done",
        ).joinToString("\n", postfix = "\n")
    }

    private fun putWrapper(wrapper: File, body: String) {
        wrapper.writeText(body)
        wrapper.setExecutable(true, true)
    }

    private fun canonical(entry: File): File? =
        try {
            entry.canonicalFile
        } catch (_: Exception) {
            null
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
