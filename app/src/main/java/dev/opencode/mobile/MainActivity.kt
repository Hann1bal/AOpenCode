package dev.opencode.mobile

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.opencode.mobile.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var readyJob: Job? = null

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

        // Bring the backend up (or wake it from idle)
        startService(Intent(this, BackendService::class.java).setAction(BackendService.ACTION_WAKE))

        awaitBackendAndLoad()

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
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
        }

        binding.webView.webViewClient = object : WebViewClient() {

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressOverlay.visibility = View.GONE
                binding.errorOverlay.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
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
                if (request?.isForMainFrame == true) {
                    runOnUiThread {
                        binding.progressOverlay.visibility = View.GONE
                        binding.errorOverlay.visibility = View.GONE
                        binding.offlineBanner.visibility = View.VISIBLE
                        binding.swipeRefresh.isRefreshing = false
                    }
                }
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
        binding.swipeRefresh.setOnRefreshListener {
            if (BackendManager.health()) {
                binding.webView.reload()
            } else {
                wakeBackendAndLoad()
            }
        }
        binding.retryButton.setOnClickListener { wakeBackendAndLoad() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java).putExtra("edit", true))
        }
    }

    private fun wakeBackendAndLoad() {
        binding.progressOverlay.visibility = View.VISIBLE
        binding.errorOverlay.visibility = View.GONE
        startService(Intent(this, BackendService::class.java).setAction(BackendService.ACTION_WAKE))
        awaitBackendAndLoad()
    }

    private fun awaitBackendAndLoad() {
        readyJob?.cancel()
        readyJob = lifecycleScope.launch {
            var ready = false
            repeat(120) {
                if (!isActive) return@launch
                if (BackendManager.health()) {
                    ready = true
                    return@repeat
                }
                delay(500)
            }
            if (!isActive) return@launch
            if (ready) {
                val url = "http://127.0.0.1:${BackendManager.port}/"
                if (binding.webView.url == null) {
                    binding.webView.loadUrl(url)
                }
                binding.progressOverlay.visibility = View.GONE
                binding.offlineBanner.visibility = View.GONE
            } else {
                binding.progressOverlay.visibility = View.GONE
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
            startService(Intent(this, BackendService::class.java).setAction(BackendService.ACTION_WAKE))
            if (binding.webView.url == null && BackendManager.health()) {
                binding.webView.loadUrl("http://127.0.0.1:${BackendManager.port}/")
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
