package dev.opencode.mobile

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the `opencode` backend binary out of band instead of carrying it in
 * the APK.
 *
 * The binary was 240 MB of a 324 MB APK and changed only when the fork was
 * rebuilt: every Kotlin-only release re-shipped those bytes untouched. Serving it
 * from the fork's rolling release makes an app update roughly 83 MB and a
 * runtime update roughly 240 MB, and only when the runtime really changed — the
 * sha256 doubles as the version, so a rebuild that produces byte-identical
 * output asks nobody to download anything.
 *
 * The download is resumable because 240 MB over a phone network will not finish
 * in one go, and it is hash-verified before it is ever executed. A truncated or
 * corrupt partial is deleted rather than resumed: no range trick can turn wrong
 * bytes into right ones.
 */
object RuntimeInstaller {

    const val MANIFEST_URL =
        "https://github.com/Hann1bal/opencode-mesh/releases/download/fork-sync-dev/runtime.json"

    private const val BINARY = "opencode"
    private const val PART = "opencode.part"
    private const val SHA_STAMP = ".opencode-sha"

    class Manifest(val sha256: String, val size: Long, val url: String)

    @Volatile
    var lastError: String? = null
        private set

    fun binaryFile(ctx: Context): File = File(BackendManager.runtimeDir(ctx), BINARY)

    private fun partFile(ctx: Context): File = File(BackendManager.runtimeDir(ctx), PART)

    private fun shaFile(ctx: Context): File = File(BackendManager.runtimeDir(ctx), SHA_STAMP)

    /**
     * True when a backend binary and its recorded digest are both present. The
     * digest is required, not optional: without it there is no way to tell a
     * downloaded runtime from a stale one, which is the failure this whole
     * module exists to avoid.
     */
    fun isInstalled(ctx: Context): Boolean =
        binaryFile(ctx).exists() && shaFile(ctx).exists()

    /** sha256 recorded for the binary on disk; empty when nothing is installed. */
    fun installedSha(ctx: Context): String =
        if (isInstalled(ctx)) shaFile(ctx).readText().trim() else ""

    fun fetchManifest(): Manifest? = try {
        val conn = open(MANIFEST_URL, 15000, 15000)
        if (conn.responseCode != 200) {
            lastError = "manifest: HTTP ${conn.responseCode}"
            null
        } else {
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            Manifest(o.getString("sha256"), o.getLong("size"), o.getString("url"))
        }
    } catch (e: Exception) {
        lastError = "manifest: ${e.javaClass.simpleName}: ${e.message}"
        null
    }

    /**
     * Downloads the binary into place, resuming a partial file if one exists.
     *
     * [onProgress] receives (bytes on disk, expected total). Returns false on
     * any failure with [lastError] set; a partial file is left behind so the
     * next attempt continues where this one stopped.
     */
    fun install(ctx: Context, m: Manifest, onProgress: (Long, Long) -> Unit = { _, _ -> }): Boolean {
        BackendManager.runtimeDir(ctx)
        val part = partFile(ctx)
        var downloaded = if (part.exists()) part.length() else 0L

        val conn = try {
            open(m.url, 20000, 60000).apply {
                if (downloaded > 0) setRequestProperty("Range", "bytes=$downloaded-")
            }
        } catch (e: Exception) {
            lastError = "connect: ${e.javaClass.simpleName}: ${e.message}"
            return false
        }

        val code = try {
            conn.responseCode
        } catch (e: Exception) {
            lastError = "response: ${e.javaClass.simpleName}: ${e.message}"
            return false
        }
        // 206 resumes the partial. 200 means the Range header was ignored and
        // the body is the whole file, so appending to what is already on disk
        // would produce a corrupt binary that then fails the hash.
        if (code == 200 && downloaded > 0) {
            part.delete()
            downloaded = 0
        }
        if (code != 200 && code != 206) {
            lastError = "HTTP $code"
            return false
        }

        try {
            conn.inputStream.use { input ->
                FileOutputStream(part, downloaded > 0).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, m.size)
                    }
                }
            }
        } catch (e: Exception) {
            lastError = "download: ${e.javaClass.simpleName}: ${e.message} " +
                "($downloaded/${m.size})"
            return false
        } finally {
            conn.disconnect()
        }

        val actual = try {
            sha256(part)
        } catch (e: Exception) {
            lastError = "hash: ${e.javaClass.simpleName}: ${e.message}"
            return false
        }
        if (actual != m.sha256) {
            part.delete()
            lastError = "sha256 mismatch: got $actual, expected ${m.sha256}"
            return false
        }

        val target = binaryFile(ctx)
        if (target.exists() && !target.delete()) {
            lastError = "could not replace the existing backend binary"
            return false
        }
        if (!part.renameTo(target)) {
            lastError = "could not move the downloaded file into place"
            return false
        }
        target.setExecutable(true, true)
        shaFile(ctx).writeText(m.sha256)
        lastError = null
        return true
    }

    /**
     * Adopts an already-present binary that has no recorded digest.
     *
     * Every install predating this module is in that state: the file is there,
     * nothing ever wrote its hash down. Without this the upgrade asks for
     * 240 MB the device already holds, purely because of a missing stamp. A
     * sha256 match against the manifest is the verification, so the cost is
     * one pass over the file instead of one download.
     */
    fun adoptExisting(ctx: Context, m: Manifest): Boolean {
        val bin = binaryFile(ctx)
        if (!bin.exists() || shaFile(ctx).exists()) return false
        val actual = try {
            sha256(bin)
        } catch (e: Exception) {
            lastError = "adopt: ${e.javaClass.simpleName}: ${e.message}"
            return false
        }
        if (actual != m.sha256) return false
        shaFile(ctx).writeText(actual)
        bin.setExecutable(true, true)
        lastError = null
        return true
    }

    private fun open(spec: String, connectMs: Int, readMs: Int): HttpURLConnection =
        (URL(spec).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectMs
            readTimeout = readMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AOpenCode")
        }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
