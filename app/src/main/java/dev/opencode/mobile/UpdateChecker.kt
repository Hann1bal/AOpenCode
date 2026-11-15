package dev.opencode.mobile

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import dev.opencode.mobile.sync.Peer
import dev.opencode.mobile.sync.PairingClient
import dev.opencode.mobile.sync.PeerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Checks a GitHub Releases feed for a newer version and installs it in place.
 *
 * Disabled unless UPDATE_REPO is configured (owner/name) at build time.
 *
 * The APK path mirrors the runtime path below: a notification carries what is
 * needed, the tap does the work, and progress is reported rather than leaving the
 * user staring at a dead notification. Before this, tapping an update opened the
 * release page in a browser, which is not an install.
 */
object UpdateChecker {
    const val CHANNEL_UPDATES = "updates"
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val RENEW_AHEAD_DAYS = 7.0
    private const val WARN_DAYS = 1.0
    private const val NOTIF_UPDATE = 202
    private const val NOTIF_RUNTIME = 203
    private const val NOTIF_DOWNLOAD = 204
    private const val NOTIF_TOKEN = 205

    /** A release we could act on: which version, and where its APK actually is. */
    private data class Release(val tag: String, val apkUrl: String?, val apkBytes: Long)

    suspend fun checkIfNeeded(context: Context) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - Prefs.lastUpdateCheck < CHECK_INTERVAL_MS) return
        Prefs.lastUpdateCheck = now
        try {
            // Token renewal runs first and unconditionally. It is the one thing
            // here with a hard deadline: if the pairing token lapses, the sync
            // dies and only a fresh QR fixes it. Gating it behind "no APK update
            // pending" would let a pending update starve it for weeks.
            renewPeerTokens(context)
            val release = withContext(Dispatchers.IO) { fetchLatestRelease(repo) }
            if (release != null && isNewer(release.tag, BuildConfig.VERSION_NAME)) {
                notifyAvailable(context, release)
                return
            }
            // The backend binary is released on its own schedule, apart from the
            // APK. It is checked after the APK and skipped while an APK update is
            // pending: offering a 240 MB runtime download and then immediately
            // asking for a reinstall of the app would be a bad hour.
            checkRuntime(context)
        } catch (_: Exception) {
            // silent — update checks must never crash the app
        }
    }

    /**
     * Keeps a paired upstream's session token alive.
     *
     * Pairing tokens live 30 days and there is no renewal path, so a pairing
     * that worked in the morning silently stops syncing a month later, and the
     * only fix is to stand by the tablet and the home machine and scan a new QR.
     * Re-minting takes the current token as credential, so it only works while
     * that token is still valid -- hence renewing a week early rather than at
     * the deadline.
     *
     * Only the active peer is renewed: that is the one whose token the backend
     * actually authenticates with, and touching idle peers would multiply the
     * network calls for no benefit.
     */
    private suspend fun renewPeerTokens(context: Context) {
        val peer = PeerStore.active() ?: return
        val expiry = PairingClient.tokenExpiryEpochSec(peer.token) ?: return
        val daysLeft = (expiry - System.currentTimeMillis() / 1000) / 86400.0
        if (daysLeft > RENEW_AHEAD_DAYS) return
        val fresh = withContext(Dispatchers.IO) {
            PairingClient.renewToken(peer.baseUrl, peer.token)
        }
        if (fresh == null) {
            if (daysLeft < WARN_DAYS) notifyTokenExpiring(context, peer)
            return
        }
        PeerStore.replaceToken(peer.id, fresh)
        notifyTokenRenewed(context, peer)
    }

    /**
     * Downloads the release APK into the cache and hands it to the package
     * installer through the FileProvider.
     *
     * The cache path is what `file_paths.xml` already exposes as `updates`, so no
     * new storage surface is introduced. The file is deleted on any failure: a
     * truncated APK handed to the installer shows up as a confusing signature
     * error rather than a download problem.
     */
    suspend fun downloadAndInstall(context: Context, url: String, tag: String): Boolean =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "AOpenCode-$tag.apk")
            deleteQuietly(target)
            notifyProgress(context, 0, 0)
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                if (conn.responseCode !in 200..299) return@withContext fail(context, tag)
                val total = conn.contentLength.toLong().coerceAtLeast(0L)
                var done = 0L
                conn.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            notifyProgress(context, done, total)
                        }
                    }
                }
                conn.disconnect()
                // A ZIP local-file header starts with "PK". Anything else is an
                // error page saved under a .apk name.
                if (done < 1_000_000 || !startsWithPk(target)) return@withContext fail(context, tag)
                launchInstaller(context, target)
                notifyDone(context, tag)
                true
            } catch (_: Exception) {
                fail(context, tag)
            }
        }

    private fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun fail(context: Context, tag: String): Boolean {
        deleteQuietly(File(context.cacheDir, "updates/AOpenCode-$tag.apk"))
        val n = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setContentTitle("Update $tag failed")
            .setContentText("Download did not complete. Check the connection and try again.")
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setAutoCancel(true)
            .build()
        notify(context, NOTIF_DOWNLOAD, n)
        return false
    }

    private fun startsWithPk(f: File): Boolean {
        if (!f.exists() || f.length() < 2) return false
        val head = ByteArray(2)
        FileInputStream(f).use { it.read(head) }
        return head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
    }

    private fun deleteQuietly(f: File) {
        try {
            if (f.exists()) f.delete()
        } catch (_: Exception) {
        }
    }

    /**
     * Offers the backend binary when the fork has been rebuilt and the copy on
     * disk is stale. Compared by sha256, not by version string, so a rebuild
     * that produced identical bytes does not ask for 240 MB again.
     */
    private suspend fun checkRuntime(context: Context) {
        val manifest = withContext(Dispatchers.IO) { RuntimeInstaller.fetchManifest() } ?: return
        val installed = RuntimeInstaller.installedSha(context)
        // Empty means nothing is installed yet; the activity's own gate covers
        // that case with a progress bar, which beats a notification.
        if (installed.isEmpty() || installed == manifest.sha256) return
        notifyRuntime(context, manifest.size)
    }

    private fun notifyRuntime(context: Context, sizeBytes: Long) {
        val mb = sizeBytes / 1048576.0
        val notif = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setContentTitle("Backend runtime update")
            .setContentText("%.1f MB — tap to install".format(mb))
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 1,
                    Intent(context, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_FORCE_RUNTIME, true)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                        ),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        notify(context, NOTIF_RUNTIME, notif)
    }

    private fun notifyAvailable(context: Context, release: Release) {
        val url = release.apkUrl
        val text = if (url != null && release.apkBytes > 0) {
            "%.1f MB — tap to install".format(release.apkBytes / 1048576.0)
        } else if (url != null) {
            "Tap to install"
        } else {
            "Tap to open the release page"
        }
        val intent = if (url != null) {
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_UPDATE_URL, url)
                .putExtra(MainActivity.EXTRA_UPDATE_TAG, release.tag)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } else {
            // No APK attached to the release: the web page is the only route left.
            Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/${BuildConfig.UPDATE_REPO}/releases/tag/${release.tag}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setContentTitle("OpenCode update ${release.tag}")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            .build()
        notify(context, NOTIF_UPDATE, notif)
    }

    /**
     * Progress is only worth showing once the total is known; before that an
     * indeterminate bar is more honest than a percentage of nothing.
     */
    private fun notifyProgress(context: Context, done: Long, total: Long) {
        val b = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setContentTitle("Downloading update")
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setOngoing(true)
        if (total > 0) {
            val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
            b.setProgress(100, pct, false)
                .setContentText("%.1f of %.1f MB".format(done / 1048576.0, total / 1048576.0))
        } else {
            b.setProgress(0, 0, true)
                .setContentText("%.1f MB".format(done / 1048576.0))
        }
        notify(context, NOTIF_DOWNLOAD, b.build())
    }

    private fun notifyDone(context: Context, tag: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setContentTitle("Ready to install $tag")
            .setContentText("Finish in the installer prompt")
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setAutoCancel(true)
            .build()
        notify(context, NOTIF_DOWNLOAD, n)
    }

    private fun notifyTokenRenewed(context: Context, peer: Peer) {
        val n = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setContentTitle(context.getString(R.string.token_renewed_title))
            .setContentText(context.getString(R.string.token_renewed_body, peer.name))
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setAutoCancel(true)
            .build()
        notify(context, NOTIF_TOKEN, n)
    }

    private fun notifyTokenExpiring(context: Context, peer: Peer) {
        val n = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setContentTitle(context.getString(R.string.token_expiring_title))
            .setContentText(context.getString(R.string.token_expiring_body, peer.name))
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setAutoCancel(true)
            .build()
        notify(context, NOTIF_TOKEN, n)
    }

    private fun notify(context: Context, id: Int, n: android.app.Notification) {
        try {
            androidx.core.app.NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // notification permission not granted yet
        }
    }

    private fun fetchLatestRelease(repo: String): Release? {
        val conn = URL("https://api.github.com/repos/$repo/releases/latest")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name").ifBlank { return null }
            pickApk(json.optJSONArray("assets"))?.let { (url, size) -> Release(tag, url, size) }
                ?: Release(tag, null, 0)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The app is arm64-only and a release may carry several APKs, so prefer an
     * arm64-labelled asset and fall back to any APK.
     *
     * Written with firstOrNull rather than a loop with `break`: a `continue` that
     * sits inside a lambda such as `ifBlank { continue }` is not a loop-continue
     * but a return from the lambda, and Kotlin rejects the cross-boundary form as
     * an experimental feature.
     */
    private fun pickApk(assets: JSONArray?): Pair<String, Long>? {
        val apks = (0 until (assets?.length() ?: 0))
            .mapNotNull { assets?.optJSONObject(it) }
            .map {
                it.optString("name").lowercase(Locale.US) to
                    (it.optString("browser_download_url") to it.optLong("size"))
            }
            .filter { (name, pair) -> name.endsWith(".apk") && pair.first.isNotEmpty() }
        return apks.firstOrNull { (name, _) -> name.contains("arm64") || name.contains("aarch64") }?.second
            ?: apks.firstOrNull()?.second
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }
}
