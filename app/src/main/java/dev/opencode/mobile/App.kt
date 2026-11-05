package dev.opencode.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        // Before any WebView exists: a service worker left from an earlier version
        // would otherwise keep serving the unpatched document from its precache.
        WebViewShim.purgeServiceWorkers(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        val backend = NotificationChannel(
            BackendService.CHANNEL_BACKEND,
            "OpenCode backend",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows that the local OpenCode server is running"
            setShowBadge(false)
        }
        val updates = NotificationChannel(
            UpdateChecker.CHANNEL_UPDATES,
            "Updates",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifies about new app versions"
        }
        nm.createNotificationChannels(listOf(backend, updates))
    }
}
