package dev.opencode.mobile

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that owns the local opencode backend.
 *
 * Lifecycle:
 *  - ACTION_START  → extract (first run) + launch + wait until healthy
 *  - ACTION_IDLE   → app went to background; stop the backend after IDLE_TIMEOUT
 *  - ACTION_WAKE   → app came back; cancel idle timer, ensure backend is up
 *  - ACTION_STOP   → stop immediately
 */
class BackendService : Service() {

    companion object {
        const val CHANNEL_BACKEND = "backend"
        const val ACTION_START = "dev.opencode.mobile.action.START_BACKEND"
        const val ACTION_IDLE = "dev.opencode.mobile.action.IDLE"
        const val ACTION_WAKE = "dev.opencode.mobile.action.WAKE"
        const val ACTION_STOP = "dev.opencode.mobile.action.STOP_BACKEND"

        private const val NOTIF_ID = 101
        /** Keep the backend alive this long after the UI goes away. */
        private const val IDLE_TIMEOUT_MS = 30L * 60 * 1000
    }

    /**
     * IO, not Main: extraction copies ~250 MB, and every health probe is a real
     * socket connect. On Dispatchers.Main those calls drew NetworkOnMainThread-
     * Exception, which health() swallows as "not healthy" — so the wait loop ran
     * its full 60 seconds against a backend that was already up.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var idleJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground("Starting OpenCode backend…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_IDLE -> {
                scheduleIdleStop()
                return START_STICKY
            }
            ACTION_WAKE -> {
                idleJob?.cancel()
                idleJob = null
                ensureBackend()
                return START_STICKY
            }
            else -> {
                ensureBackend()
                return START_STICKY
            }
        }
    }

    private fun ensureBackend() {
        scope.launch {
            try {
                if (!BackendManager.isExtracted(this@BackendService)) {
                    CrashLog.mark("extract backend")
                    withContext(Dispatchers.Main) { updateNotification("Unpacking backend… 0%") }
                    BackendManager.extract(this@BackendService) { frac ->
                        scope.launch(Dispatchers.Main) {
                            updateNotification("Unpacking backend… ${(frac * 100).toInt()}%")
                        }
                    }
                }
                // Checked on its own because a rootfs that failed halfway must be
                // retried without dragging the whole backend through a re-extract.
                if (!RootfsExtractor.isReady(this@BackendService)) {
                    withContext(Dispatchers.Main) { updateNotification("Installing environment…") }
                    RootfsExtractor.extract(this@BackendService)
                }
                if (!BackendManager.isRunning() || !BackendManager.health()) {
                    CrashLog.mark("start backend")
                    withContext(Dispatchers.Main) { updateNotification("Starting OpenCode…") }
                    BackendManager.start(this@BackendService)
                }
                // DIAGNOSTIC: remote exec endpoint, gated on Prefs.diagnosticMode.
                // Idempotent, so safe to call on every ensure pass. Remove with the
                // DIAGNOSTIC_MODE block in Prefs before release.
                DiagServer.start(this@BackendService)
                // Wait for the HTTP server to answer
                var ready = false
                repeat(120) {
                    if (BackendManager.health()) {
                        ready = true
                        return@repeat
                    }
                    delay(500)
                }
                if (ready && isActive) {
                    withContext(Dispatchers.Main) {
                        updateNotification("OpenCode ready · port ${BackendManager.port}")
                    }
                } else if (isActive) {
                    withContext(Dispatchers.Main) {
                        updateNotification("Backend failed to start — tap to retry")
                    }
                }
            } catch (e: Throwable) {
                // Throwable, not Exception. An OutOfMemoryError or a LinkageError
                // used to fall straight through here and take the process with it,
                // which is what "it just closes during install" actually was.
                CrashLog.note("FAILED: ${e.javaClass.name}: ${e.message}")
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        updateNotification("Backend error: ${e.javaClass.simpleName}: ${e.message ?: "unknown"} — tap to retry")
                    }
                }
            }
        }
    }

    private fun scheduleIdleStop() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            stopEverything()
        }
    }

    private fun stopEverything() {
        idleJob?.cancel()
        idleJob = null
        // destroy() + waitFor() blocks up to three seconds; never on the caller's
        // thread, which is usually the main one.
        scope.launch {
            BackendManager.stop()
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun goForeground(text: String) {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_BACKEND)
            .setContentTitle("OpenCode")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setOngoing(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(this, NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun updateNotification(text: String) {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_BACKEND)
            .setContentTitle("OpenCode")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setOngoing(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIF_ID, notif)
    }

    override fun onDestroy() {
        BackendManager.stop()
        scope.cancel()
        super.onDestroy()
    }
}
