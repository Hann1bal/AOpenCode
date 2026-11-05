package dev.opencode.mobile

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The bundled web UI targets desktop Chrome and uses APIs that Android System WebView
 * only gained recently — `URL.canParse` is Chrome 120, `Array.prototype.toSorted`
 * is 110, `Object.groupBy` is 117. On an older WebView these are plain `TypeError`s
 * that take down the SPA: `URL.canParse` inside `ServerConnection.local()` breaks
 * server registration, which leaves the server list empty and renders the
 * "connect to server" screen even though the backend is healthy and paired.
 *
 * Patching the fork is the wrong tool here — the call sites are scattered across
 * dozens of files and more will appear with every upstream update. Instead the shim
 * is installed before any page script runs, by rewriting the HTML document we
 * serve to the WebView.
 *
 * The shim is loaded as a same-origin external script, so the page's strict CSP
 * (`script-src 'self' …`) accepts it without being weakened.
 */
object WebViewShim {

    private const val SHIM_PATH = "/__mobile-shim.js"

    /**
     * The web UI registers a Workbox service worker in production:
     *
     *   entry.tsx:54  navigator.serviceWorker.register("/sw.js")
     *
     * A controlling service worker answers navigations and asset requests out of
     * its own cache and never touches the WebView network stack, so
     * shouldInterceptRequest — and with it the shim — is skipped entirely. Failing
     * the registration keeps every request on a path we can rewrite.
     */
    private val BLOCKED_PATHS = setOf("/sw.js", "/registerSW.js")

    /** Chromium keeps worker registrations under "Service Worker"; older builds vary. */
    private val SW_DIRS = listOf("Service Worker", "ServiceWorker")

    /** Set by the shim itself; lets the UI prove the injection actually landed. */
    @Volatile
    var installedState: String = "not verified"
        private set

    fun verify(webView: android.webkit.WebView) {
        try {
            webView.evaluateJavascript(
                "(window.__ocShimInstalled===true)+'/'+(typeof URL.canParse)",
            ) { value -> installedState = value ?: "no response" }
        } catch (e: Exception) {
            installedState = "verify failed: ${e.javaClass.simpleName}"
        }
    }

    /**
     * Removes service worker registrations and their caches from disk, once per
     * version.
     *
     * The bundled web UI registers a Workbox worker with
     * `navigateFallback: "/index.html"`, so once it activates it answers
     * navigations out of its own precache and the WebView never asks us for the
     * document — the only place the shim can be installed. WebView exposes no API
     * to unregister a worker, so its storage is removed directly. Only the
     * worker's own directory goes: localStorage, cookies and the HTTP cache stay.
     *
     * Must run before any WebView instance exists.
     */
    /** What the last purge actually removed, for the diagnostics report. */
    @Volatile
    var lastPurge: String = "not run"
        private set

    /**
     * Removes service worker registrations and their caches from disk.
     *
     * The bundled web UI registers a Workbox worker with
     * `navigateFallback: "/index.html"`, so once it activates it answers
     * navigations out of its own precache and the WebView never asks us for the
     * document — the only place the shim can be installed. WebView exposes no
     * API to unregister a worker, so its storage is removed directly. Only the
     * worker's own directory goes: localStorage, cookies and the HTTP cache
     * stay.
     *
     * Runs on every cold start, not once per version: a controlling worker is
     * never wanted here, and re-removing an empty directory costs nothing.
     *
     * Must run before any WebView instance exists.
     */
    fun purgeServiceWorkers(ctx: Context) {
        val dataDir = ctx.filesDir.parentFile ?: run {
            lastPurge = "no data dir"
            return
        }
        val removed = mutableListOf<String>()
        for (name in SW_DIRS) {
            val dir = File(dataDir, "app_webview/$name")
            if (dir.exists() && dir.deleteRecursively()) removed.add(name)
        }
        lastPurge = if (removed.isEmpty()) "nothing found in app_webview" else "removed ${removed.joinToString(",")}"
    }

    /**
     * @param mainFrame only the document itself is rewritten; every other resource
     *   (including the `/api/event` SSE stream) is left for the WebView to handle
     *   natively, because proxying a stream here would buffer it.
     */
    fun handle(url: Uri, mainFrame: Boolean): WebResourceResponse? {
        if (url.path in BLOCKED_PATHS) return blockedServiceWorker()
        if (url.path == SHIM_PATH) return shimScript()
        if (!mainFrame) return null
        return documentWithShim(url)
    }

    private fun blockedServiceWorker(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            404,
            "Service worker disabled",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )

    private fun shimScript(): WebResourceResponse =
        response(SHIM_JS.toByteArray(Charsets.UTF_8), "application/javascript")

    /**
     * Fetches the shell from the local backend and splices the shim into `<head>`.
     * Returns null when the response is not HTML, letting the WebView re-request it
     * untouched rather than mis-wrapping a binary or a script.
     */
    private fun documentWithShim(url: Uri): WebResourceResponse? {
        val conn = try {
            (URL(url.toString()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 15000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "text/html")
                // The server would answer with brotli; we need the bytes to edit.
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Authorization", basicAuth())
            }
        } catch (e: Exception) {
            return null
        }

        val html: String
        val csp: String?
        try {
            if (conn.responseCode != 200) return null
            val type = conn.contentType ?: return null
            if (!type.contains("text/html")) return null
            csp = conn.getHeaderField("Content-Security-Policy")
            html = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            return null
        } finally {
            conn.disconnect()
        }

        val patched = injectShim(html) ?: return null
        val headers = HashMap<String, String>()
        headers["Cache-Control"] = "no-cache"
        if (!csp.isNullOrEmpty()) headers["Content-Security-Policy"] = csp

        val res = WebResourceResponse(
            "text/html",
            "utf-8",
            ByteArrayInputStream(patched.toByteArray(Charsets.UTF_8)),
        )
        res.responseHeaders = headers
        return res
    }

    private fun response(bytes: ByteArray, contentType: String): WebResourceResponse {
        val res = WebResourceResponse(contentType, "utf-8", ByteArrayInputStream(bytes))
        res.responseHeaders = mapOf("Cache-Control" to "no-cache")
        return res
    }

    private fun basicAuth(): String =
        "Basic " + Base64.encodeToString(
            ("opencode:" + Prefs.backendToken).toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )

    private val HEAD = Regex("(?i)<head[^>]*>")

    /**
     * Belt and braces: if a worker survived the on-disk purge, drop it as soon as
     * the document runs, so the next load comes back through us.
     */
    private const val UNREGISTER_TAG =
        "<script>if(navigator.serviceWorker&&navigator.serviceWorker.getRegistrations){" +
            "navigator.serviceWorker.getRegistrations().then(function(rs){" +
            "rs.forEach(function(r){r.unregister();});});}</script>"

    private fun injectShim(html: String): String? {
        val match = HEAD.find(html) ?: return null
        val end = match.range.last + 1
        // Inlined first: a separate request can be served from cache, blocked, or
        // simply arrive after the modules that need it. The `src` tag stays as the
        // fallback for a document that does carry a CSP without 'unsafe-inline',
        // where the inline block would be refused but a 'self' script is allowed.
        val inline = "<script>${SHIM_JS}</script>"
        val external = "<script src=\"$SHIM_PATH\"></script>"
        return html.substring(0, end) + inline + external + UNREGISTER_TAG + html.substring(end)
    }

    /**
     * Each shim is installed only when the platform really lacks the method, so a
     * newer WebView keeps its native implementation. Definitions are
     * non-enumerable to avoid leaking into `for…in` loops.
     */
    private const val SHIM_JS = """
(function () {
  'use strict';

  function define(obj, name, value) {
    if (!obj || typeof obj[name] === 'function') return;
    try {
      Object.defineProperty(obj, name, {
        value: value,
        writable: true,
        configurable: true,
        enumerable: false
      });
    } catch (e) {}
  }

  // Chrome 120. Without it, ServerConnection.local() throws and the SPA never
  // registers its own server, which surfaces as the connect screen.
  if (typeof URL !== 'undefined') {
    define(URL, 'canParse', function canParse(input, base) {
      try {
        new URL(input, base);
        return true;
      } catch (e) {
        return false;
      }
    });
  }

  var AP = Array.prototype;

  // Chrome 92 / 97
  define(AP, 'at', function (n) {
    var len = this.length;
    var i = Math.trunc(n) || 0;
    if (i < 0) i += len;
    if (i < 0 || i >= len) return undefined;
    return this[i];
  });
  define(AP, 'findLast', function (fn, thisArg) {
    for (var i = this.length - 1; i >= 0; i--) {
      if (fn.call(thisArg, this[i], i, this)) return this[i];
    }
    return undefined;
  });
  define(AP, 'findLastIndex', function (fn, thisArg) {
    for (var i = this.length - 1; i >= 0; i--) {
      if (fn.call(thisArg, this[i], i, this)) return i;
    }
    return -1;
  });

  // Chrome 110: change-array-by-copy. Used all over the app's sorting.
  define(AP, 'toSorted', function (cmp) {
    return this.slice().sort(cmp);
  });
  define(AP, 'toReversed', function () {
    return this.slice().reverse();
  });
  define(AP, 'toSpliced', function () {
    var copy = this.slice();
    Array.prototype.splice.apply(copy, arguments);
    return copy;
  });

  // Chrome 93
  define(Object, 'hasOwn', function (obj, key) {
    return Object.prototype.hasOwnProperty.call(obj, key);
  });

  // Chrome 117
  define(Object, 'groupBy', function (items, callback) {
    var out = {};
    var index = 0;
    var it = items[Symbol.iterator]();
    for (var step = it.next(); !step.done; step = it.next()) {
      var key = String(callback(step.value, index++));
      if (!Object.prototype.hasOwnProperty.call(out, key)) out[key] = [];
      out[key].push(step.value);
    }
    return out;
  });

  // Chrome 98. The JSON round trip is not a true structured clone, but the app
  // only uses it on plain data; a missing method is worse than a lossy one.
  if (typeof window !== 'undefined' && typeof window.structuredClone !== 'function') {
    try {
      window.structuredClone = function (value) {
        return JSON.parse(JSON.stringify(value));
      };
    } catch (e) {}
  }

  // Marker so the app can confirm the shim actually ran, rather than assuming the
  // interception reached the document.
  if (typeof window !== 'undefined') {
    window.__ocShimInstalled = true;
  }
})();
"""
}
