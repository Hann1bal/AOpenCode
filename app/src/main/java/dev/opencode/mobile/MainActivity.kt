package dev.opencode.mobile

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.opencode.mobile.databinding.ActivityMainBinding
import dev.opencode.mobile.sync.PeerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var readyJob: Job? = null
    private var loadedUrl: String? = null
    private var runtimeManifest: RuntimeInstaller.Manifest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Prefs.configured) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()
        setupWebView()
        setupRefreshAndError()
        renderModeChip()

        // The backend binary is not inside the APK. Nothing can run until it is
        // on disk, so the gate comes before the service is even woken. A
        // runtime-update notification forces the gate open even when a binary
        // is already installed, so the newer one can be fetched in its place.
        val forceRuntime = intent?.getBooleanExtra(EXTRA_FORCE_RUNTIME, false) == true
        if (RuntimeInstaller.isInstalled(this) && !forceRuntime) {
            startBackend()
        } else {
            showRuntimeGate()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.webView.canGoBack() -> binding.webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            // LOAD_DEFAULT lets the WebView answer the document from its HTTP
            // cache, and a cached main-frame load never reaches
            // shouldInterceptRequest — which is the only place the shim can be
            // installed. That is why inject= read "not run" while the SPA loaded
            // perfectly well. Everything here comes off loopback, so skipping
            // the cache costs nothing worth mentioning.
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            // A controlling service worker makes the WebView skip
            // shouldInterceptRequest entirely, so the shim cannot be installed
            // from here. androidx.webkit has no per-settings switch for this —
            // the only lever is keeping the worker from existing, which
            // WebViewShim.purgeServiceWorkers does before this view is created.
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
        }

        binding.webView.webViewClient = object : WebViewClient() {

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressOverlay.visibility = View.GONE
                binding.errorOverlay.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
                view?.let { WebViewShim.verify(it) }
            }

            /**
             * Installs the browser-API shim for older Android System WebView builds.
             * Only the HTML document is rewritten; subresources and the SSE stream
             * stay on the WebView's native path so nothing gets buffered.
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                val host = url.host
                if (host != "127.0.0.1" && host != "localhost") return null
                return WebViewShim.handle(url, request.isForMainFrame)
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: HttpAuthHandler?,
                host: String?,
                realm: String?
            ) {
                // Auto-fill the per-install backend token if the server ever challenges.
                handler?.proceed("opencode", Prefs.backendToken)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                WebDiagnostics.record(
                    "${request?.method ?: "?"} ${request?.url} -> ${error?.errorCode} ${error?.description ?: ""}",
                )
                if (request?.isForMainFrame == true) {
                    runOnUiThread {
                        binding.progressOverlay.visibility = View.GONE
                        binding.errorOverlay.visibility = View.GONE
                        binding.offlineBanner.visibility = View.VISIBLE
                        binding.swipeRefresh.isRefreshing = false
                    }
                }
            }

            /**
             * Every non-2xx the WebView saw, with its URL. This is what turns a
             * bare "Not Found" toast into something diagnosable from a copied
             * report. Note that `-> 404` on `/sw.js` is the shim blocking the
             * service worker on purpose, not a fault.
             */
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                WebDiagnostics.record(
                    "${request?.method ?: "?"} ${request?.url} -> ${errorResponse?.statusCode ?: 0}",
                )
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                val host = url.host ?: return false
                val isLocal = (host == "127.0.0.1" || host == "localhost") &&
                    url.port == BackendManager.port
                return if (isLocal) {
                    false // keep in-app
                } else {
                    openExternal(url)
                    true
                }
            }
        }
    }

    private fun openExternal(url: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    private fun setupRefreshAndError() {
        // Pull-to-refresh is disabled outright. The scroll-up guard cannot work
        // here: this SPA scrolls an inner `overflow-y: auto` container, not the
        // document, so the WebView's own scrollY stays 0 no matter how far the
        // conversation has been scrolled. The guard therefore always answered
        // "child cannot scroll up", SwipeRefreshLayout took over every downward
        // drag, and reading back through a long session fired a reload on each
        // gesture. There is no threshold that fixes a signal that is wrong at
        // every offset, so the gesture goes.
        //
        // Nothing is lost that matters: the page loads on open, the error
        // overlay has its own retry, and a stale view is one tap from the mode
        // chip away.
        binding.swipeRefresh.isEnabled = false
        binding.swipeRefresh.setOnRefreshListener {
            lifecycleScope.launch {
                // A probe is a socket connect: off the main thread, or it throws
                // NetworkOnMainThreadException and reads as "backend is down".
                if (withContext(Dispatchers.IO) { BackendManager.health() }) {
                    loadBackend()
                } else {
                    wakeBackendAndLoad()
                }
            }
        }
        binding.retryButton.setOnClickListener { wakeBackendAndLoad() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java).putExtra("edit", true))
        }
        binding.peersButton.setOnClickListener {
            startActivity(Intent(this, PeersActivity::class.java))
        }
        binding.modeChip.setOnClickListener {
            startActivity(Intent(this, PeersActivity::class.java))
        }
    }

    private fun startBackend() {
        binding.runtimeOverlay.visibility = View.GONE
        binding.progressOverlay.visibility = View.VISIBLE
        binding.errorOverlay.visibility = View.GONE
        startService(Intent(this, BackendService::class.java).setAction(BackendService.ACTION_WAKE))
        awaitBackendAndLoad()
    }

    private fun wakeBackendAndLoad() {
        if (!RuntimeInstaller.isInstalled(this)) {
            showRuntimeGate()
            return
        }
        startBackend()
    }

    /**
     * Asks for the runtime before spending the user's bandwidth on it.
     *
     * The manifest is read first because the question has to carry a number:
     * "download?" is not a choice, "download 241 MB?" is.
     */
    private fun showRuntimeGate() {
        binding.progressOverlay.visibility = View.GONE
        binding.errorOverlay.visibility = View.GONE
        binding.runtimeOverlay.visibility = View.VISIBLE
        binding.runtimeProgress.visibility = View.GONE
        binding.runtimeStatus.text = getString(R.string.runtime_checking)
        binding.runtimeDownloadButton.visibility = View.GONE
        binding.runtimeLaterButton.visibility = View.VISIBLE
        binding.runtimeDownloadButton.setOnClickListener { beginRuntimeDownload() }
        binding.runtimeLaterButton.setOnClickListener {
            binding.runtimeStatus.text = getString(R.string.runtime_dismissed)
        }

        lifecycleScope.launch {
            val manifest = withContext(Dispatchers.IO) { RuntimeInstaller.fetchManifest() }
            if (!isActive) return@launch
            if (manifest == null) {
                binding.runtimeStatus.text = getString(R.string.runtime_no_manifest) +
                    "\n" + (RuntimeInstaller.lastError ?: "")
                binding.runtimeDownloadButton.text = getString(R.string.retry)
                binding.runtimeDownloadButton.visibility = View.VISIBLE
                return@launch
            }
            runtimeManifest = manifest
            // Hashing 240 MB is seconds on flash and minutes on the main thread,
            // so it stays on IO. If the file already on disk matches the
            // manifest there is nothing to download and the gate closes itself.
            binding.runtimeStatus.text = getString(R.string.runtime_verifying)
            val adopted = withContext(Dispatchers.IO) {
                RuntimeInstaller.adoptExisting(this@MainActivity, manifest)
            }
            if (!isActive) return@launch
            if (adopted) {
                startBackend()
                return@launch
            }
            val installed = RuntimeInstaller.installedSha(this@MainActivity)
            val stale = installed.isNotEmpty() && installed != manifest.sha256
            binding.runtimeStatus.text = getString(
                if (stale) R.string.runtime_update else R.string.runtime_ready,
                megabytes(manifest.size),
            )
            binding.runtimeDownloadButton.text = getString(
                if (stale) R.string.runtime_download_update else R.string.runtime_download,
            )
            binding.runtimeDownloadButton.visibility = View.VISIBLE
        }
    }

    /**
     * Streams the binary in, off the main thread, and reports progress as it
     * lands. A failure keeps the partial file: the retry resumes from where this
     * one stopped instead of starting 240 MB over.
     */
    private fun beginRuntimeDownload() {
        val manifest = runtimeManifest ?: return
        Prefs.runtimeConsent = "granted"
        binding.runtimeDownloadButton.visibility = View.GONE
        binding.runtimeLaterButton.visibility = View.GONE
        binding.runtimeProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                RuntimeInstaller.install(this@MainActivity, manifest) { done, total ->
                    if (total <= 0) return@install
                    val percent = ((done * 100) / total).toInt()
                    runOnUiThread {
                        binding.runtimeProgress.progress =
                            ((done * 1000) / total).toInt().coerceIn(0, 1000)
                        binding.runtimeStatus.text = getString(
                            R.string.runtime_progress, megabytes(done), megabytes(total), percent,
                        )
                    }
                }
            }
            if (!isActive) return@launch
            if (ok) {
                binding.runtimeStatus.text = getString(R.string.runtime_done)
                binding.runtimeProgress.visibility = View.GONE
                startBackend()
            } else {
                binding.runtimeProgress.visibility = View.GONE
                binding.runtimeStatus.text = getString(
                    R.string.runtime_failed, RuntimeInstaller.lastError ?: "unknown",
                )
                binding.runtimeDownloadButton.text = getString(R.string.retry)
                binding.runtimeDownloadButton.visibility = View.VISIBLE
                binding.runtimeLaterButton.visibility = View.VISIBLE
            }
        }
    }

    private fun megabytes(bytes: Long): String = String.format("%.1f MB", bytes / 1048576.0)

    companion object {
        /** Opens the runtime gate even when a binary is already installed. */
        const val EXTRA_FORCE_RUNTIME = "force_runtime_update"
    }

    /**
     * The SPA gates itself: on boot it registers `location.origin` as its server
     * and probes `GET /api/info`. With no credential attached that probe draws a
     * 401, and the app renders its own "connect to server" screen instead of the
     * UI. The WebView's Basic-auth handler cannot rescue this — the probe is a
     * `fetch()` and the SPA decides from the response status itself.
     *
     * `?auth_token=` is the supported boot path: `entry.tsx` reads it, attaches
     * the password to the connection, and every request carries the Basic header
     * from then on. The SPA strips the param from the address bar afterwards, so
     * each load has to come through here rather than a plain `reload()`.
     */
    private fun backendUrl(): String {
        val credential = "opencode:" + Prefs.backendToken
        val token = Base64.encodeToString(credential.toByteArray(), Base64.NO_WRAP)
        // Percent-encoding is not optional: the SPA reads this with URLSearchParams,
        // which decodes a raw '+' as a space and would corrupt the credential.
        return "http://127.0.0.1:${BackendManager.port}/?auth_token=${Uri.encode(token)}"
    }

    private fun loadBackend() {
        val url = backendUrl()
        loadedUrl = url
        binding.webView.loadUrl(url)
    }

    /** Makes the current working mode visible: local agent, or following a peer. */
    private fun renderModeChip() {
        val peer = PeerStore.active()
        binding.modeChip.text = if (peer == null) {
            getString(R.string.mode_local)
        } else {
            getString(R.string.mode_following, peer.name)
        }
    }

    private fun awaitBackendAndLoad() {
        readyJob?.cancel()
        readyJob = lifecycleScope.launch {
            var ready = false
            repeat(120) {
                if (!isActive) return@launch
                // The probe has to leave the main thread: thrown on Main it is
                // caught inside health() and reported as "not healthy", which
                // made every start burn the full 60-second timeout.
                if (withContext(Dispatchers.IO) { BackendManager.health() }) {
                    ready = true
                    return@repeat
                }
                delay(500)
            }
            if (!isActive) return@launch
            if (ready) {
                if (loadedUrl == null) loadBackend()
                binding.progressOverlay.visibility = View.GONE
                binding.offlineBanner.visibility = View.GONE
            } else {
                binding.progressOverlay.visibility = View.GONE
                binding.errorDetail.text = BackendManager.failureReport(this@MainActivity)
                binding.errorOverlay.visibility = View.VISIBLE
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Let the service know the UI is gone; it will stop the backend after idle timeout
        startService(Intent(this, BackendService::class.java).setAction(BackendService.ACTION_IDLE))
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            if (RuntimeInstaller.isInstalled(this)) {
                startService(
                    Intent(this, BackendService::class.java).setAction(BackendService.ACTION_WAKE),
                )
            }
            renderModeChip()
            // Switching mode restarts the backend, possibly on a new port, so the
            // page we are holding is stale. The flag is explicit because the port
            // is not yet known here — the restart may still be in flight.
            if (PeersActivity.modeChanged) {
                PeersActivity.modeChanged = false
                loadedUrl = null
                wakeBackendAndLoad()
            }
        }
    }

    override fun onDestroy() {
        readyJob?.cancel()
        if (::binding.isInitialized) {
            binding.webView.destroy()
        }
        super.onDestroy()
    }
}
