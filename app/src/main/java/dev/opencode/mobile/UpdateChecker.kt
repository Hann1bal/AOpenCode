package dev.opencode.mobile

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks a GitHub Releases feed for a newer version and offers an in-app install.
 *
 * Disabled unless UPDATE_REPO is configured (owner/name) at build time.
 */
object UpdateChecker {
    const val CHANNEL_UPDATES = "updates"
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    suspend fun checkIfNeeded(context: Context) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - Prefs.lastUpdateCheck < CHECK_INTERVAL_MS) return
        Prefs.lastUpdateCheck = now
        try {
            val latest = withContext(Dispatchers.IO) { fetchLatestTag(repo) }
            if (latest != null && isNewer(latest, BuildConfig.VERSION_NAME)) {
                notifyAvailable(context, latest, repo)
                return
            }
            // The backend binary is released on its own schedule, apart from the
            // APK. It is checked second and skipped while an APK update is
            // pending: offering a 240 MB runtime download and then immediately
            // asking for a reinstall of the app would be a bad hour.
            checkRuntime(context)
        } catch (_: Exception) {
            // silent — update checks must never crash the app
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
                android.app.PendingIntent.getActivity(
                    context, 1,
                    Intent(context, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_FORCE_RUNTIME, true)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                        ),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(context).notify(203, notif)
        } catch (_: SecurityException) {
            // notification permission not granted yet
        }
    }

    private fun fetchLatestTag(repo: String): String? {
        val conn = URL("https://api.github.com/repos/$repo/releases/latest")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            JSONObject(body).optString("tag_name").ifBlank { null }
        } finally {
            conn.disconnect()
        }
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

    private fun notifyAvailable(context: Context, tag: String, repo: String) {
        val notif = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setContentTitle("OpenCode update $tag")
            .setContentText("Tap to download and install")
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setAutoCancel(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    context, 0,
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$repo/releases/tag/$tag"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(context)
                .notify(202, notif)
        } catch (_: SecurityException) {
            // notification permission not granted yet
        }
    }
}
