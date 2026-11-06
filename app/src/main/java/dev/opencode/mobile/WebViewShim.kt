package dev.opencode.mobile

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The bundled web UI targets desktop Chrome and uses APIs that Android System WebView
 * only gained recently — `URL.canParse` is Chrome 120, static `URL.parse` is 126,
 * `AbortSignal.any` is 116, `Array.prototype.toSorted` is 110, `Object.groupBy`
 * is 117. On an older WebView these are plain `TypeError`s that take down the SPA:
 * `URL.canParse` inside `ServerConnection.local()` breaks server registration,
 * which leaves the server list empty and renders the "connect to server" screen
 * even though the backend is healthy and paired, and `URL.parse` in the pairing
 * dialog kills the submit before the request is sent.
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

    /** Set by the shim itself; lets the UI prove the injection actually landed. */
    @Volatile
    var installedState: String = "not verified"
        private set

    fun verify(webView: android.webkit.WebView) {
        try {
            webView.evaluateJavascript(
                "(window.__ocShimInstalled===true)+'/'+(typeof URL.canParse)" +
                    "+'/'+(typeof URL.parse)",
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
     * The provider directory is not a constant. Stock System WebView uses
     * `app_webview`; this HUAWEI ROM keeps everything under `app_hws_webview`,
     * one level deeper still at `Default/Service Worker`. Hardcoding
     * `app_webview/<name>` — which is what this used to do — deleted nothing at
     * all while the worker went on serving the unpatched document, which is why
     * the shim reported "not run" forever despite running on every cold start.
     * So: find every WebView data directory and hunt the worker inside it.
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
        val roots = dataDir.listFiles()?.filter {
            it.isDirectory && it.name.lowercase().contains("webview")
        } ?: emptyList()
        if (roots.isEmpty()) {
            lastPurge = "no webview data dir under ${dataDir.name}"
            return
        }
        val removed = mutableListOf<String>()
        for (root in roots) {
            for (dir in serviceWorkerDirs(root)) {
                if (dir.deleteRecursively()) removed.add(dir.absolutePath)
            }
        }
        lastPurge = if (removed.isEmpty()) {
            "no worker found under " + roots.joinToString(",") { it.name }
        } else {
            "removed ${removed.size}: " + removed.joinToString("; ") {
                it.removePrefix(dataDir.absolutePath + "/")
            }
        }
    }

    /**
     * Every `Service Worker` directory beneath [root], up to three levels down.
     *
     * Depth-bounded rather than a full walk: the layouts that matter are
     * `<root>/Service Worker` and `<root>/Default/Service Worker`, and a
     * recursive sweep of the whole provider directory would be slow and could
     * reach something unrelated.
     */
    private fun serviceWorkerDirs(root: File): List<File> {
        val found = mutableListOf<File>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.addLast(root to 0)
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            if (depth > 3) continue
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (!child.isDirectory) continue
                if (child.name == "Service Worker" || child.name == "ServiceWorker") {
                    found.add(child)
                } else {
                    queue.addLast(child to depth + 1)
                }
            }
        }
        return found
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
    /**
     * What the last document interception actually did.
     *
     * The shim's own probe can only report "not in the page". This names the
     * reason we did not put it there — a non-HTML content type, a missing
     * `<head>`, a failed fetch — which is the difference between guessing and
     * knowing.
     */
    @Volatile
    var lastInject: String = "not run"
        private set

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
            lastInject = "connect failed: ${e.javaClass.simpleName}: ${e.message}"
            return null
        }

        val html: String
        val csp: String?
        try {
            if (conn.responseCode != 200) {
                lastInject = "status ${conn.responseCode}"
                return null
            }
            val type = conn.contentType
            if (type == null) {
                lastInject = "no content type"
                return null
            }
            if (!type.contains("text/html")) {
                lastInject = "not html: $type"
                return null
            }
            csp = conn.getHeaderField("Content-Security-Policy")
            html = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            lastInject = "read failed: ${e.javaClass.simpleName}: ${e.message}"
            return null
        } finally {
            conn.disconnect()
        }

        val patched = injectShim(html)
        if (patched == null) {
            lastInject = "no <head> in ${html.length} bytes"
            return null
        }
        lastInject = "patched ${html.length} -> ${patched.length} bytes"
        val headers = HashMap<String, String>()
        headers["Cache-Control"] = "no-cache"
        // Propagating the policy verbatim refused our own inline block: this
        // backend ships script-src without 'unsafe-inline', so the splice was
        // dead on arrival and only the external tag was left standing. Listing
        // the shim's digest makes the inline path valid too instead of hoping
        // one fallback never races the module graph.
        if (!csp.isNullOrEmpty()) headers["Content-Security-Policy"] = allowShim(csp)

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

    private fun cspHash(body: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(Charsets.UTF_8))
        return "sha256-" + Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    /**
     * base64(SHA-256(SHIM_JS)) — the CSP digest of the inline block.
     *
     * Computed over the exact bytes between `<script>` and `</script>`, which is
     * what the browser hashes. The app already relies on this mechanism for its
     * own theme-preload script, so adding one more listed script weakens nothing:
     * the script it permits is the one we wrote.
     */
    private val shimHash: String by lazy { cspHash(SHIM_JS) }

    /**
     * CSP digest of the inline service-worker purge.
     *
     * injectShim writes three script tags but only the shim's hash was listed, so
     * the purge was refused on every single load — the browser hashed the exact
     * bytes of the unregister block and found nothing matching in script-src.
     * Both inline blocks are ours, so listing both weakens nothing.
     */
    private val unregisterHash: String by lazy {
        cspHash(UNREGISTER_TAG.removePrefix("<script>").removeSuffix("</script>"))
    }

    /**
     * Adds the app's inline script digests to `script-src`, leaving every other
     * directive alone.
     *
     * When `script-src` is absent the browser falls back to `default-src`, so a
     * bare hash would be ignored and an explicit directive has to be added
     * instead.
     */
    private fun allowShim(csp: String): String {
        val needed = listOf(shimHash, unregisterHash)
        val match = Regex("(?i)\\bscript-src\\b[^;]*").find(csp)
            ?: return "$csp; script-src 'self' " + needed.joinToString(" ") { "'$it'" }
        val missing = needed.filterNot { match.value.contains(it) }
        if (missing.isEmpty()) return csp
        return csp.replaceRange(
            match.range,
            match.value.trimEnd() + " " + missing.joinToString(" ") { "'$it'" },
        )
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

    // Chrome 126. This is the static parse, not the constructor: it returns a
    // URL or null instead of throwing. The servers dialog in settings runs it on
    // whatever was typed into the pairing field, so on this WebView the dialog
    // dies with "URL.parse is not a function" before the pairing request is
    // ever sent -- which reads as the link being rejected when nothing was
    // actually attempted.
    define(URL, 'parse', function parse(input, base) {
      try {
        return new URL(input, base);
      } catch (e) {
        return null;
      }
    });
  }

  // Chrome 116. The goal plugin calls AbortSignal.any and dies with "is not a
  // function" on the Chrome 114 WebView that ships on Android 12, which takes
  // the whole plugin down at load. Semantics: the returned signal aborts as soon
  // as any input does, carrying that input's reason.
  if (typeof AbortSignal !== 'undefined' && typeof AbortController !== 'undefined') {
    define(AbortSignal, 'any', function any(signals) {
      var controller = new AbortController();
      var list = Array.prototype.slice.call(signals || []);
      if (list.length === 0) return controller.signal;
      var bind = function (source) {
        return function () {
          controller.abort(source.reason);
        };
      };
      for (var i = 0; i < list.length; i++) {
        var source = list[i];
        if (source && source.aborted) {
          controller.abort(source.reason);
          break;
        }
        if (source && typeof source.addEventListener === 'function') {
          source.addEventListener('abort', bind(source), { once: true });
        }
      }
      return controller.signal;
    });
  }

  // Take away the service worker, not just the one on disk.
  //
  // A controlling worker answers navigations from its own precache and the
  // WebView never calls shouldInterceptRequest, so the document that comes up
  // is the unpatched one and canParse is missing again. Purging the worker at
  // cold start covers the first load only: the SPA registers a fresh one
  // immediately afterwards, and that one wins every later navigation inside
  // the session. Removing the ability to register is the only lever that
  // holds for as long as the page lives, and this runs before any page script.
  try {
    var sw = navigator.serviceWorker;
    if (sw) {
      Object.defineProperty(sw, 'register', {
        value: function () {
          return Promise.reject(new Error('service workers are disabled in this app'));
        },
        writable: false,
        configurable: true
      });
      Object.defineProperty(sw, 'getRegistrations', {
        value: function () { return Promise.resolve([]); },
        writable: false,
        configurable: true
      });
      Object.defineProperty(sw, 'getRegistration', {
        value: function () { return Promise.resolve(undefined); },
        writable: false,
        configurable: true
      });
    }
  } catch (e) {}

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

  // Route uncaught JS errors and unhandled promise rejections into the console
  // so the app's WebChromeClient captures them into WebConsole. Without this a
  // crash inside the SPA is silent: it never reaches the backend log, and there
  // is no other record of it. This is what makes a failing tool call inside the
  // page legible at all.
  if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    try {
      window.addEventListener('error', function (e) {
        var where = (e.filename || '?') + ':' + (e.lineno || 0) + ':' + (e.colno || 0);
        var detail = e.message || 'unknown error';
        if (e.error && e.error.stack) {
          detail += '  <' + String(e.error.stack).split('\n').slice(0, 4).join(' <- ') + '>';
        }
        console.error('[window.error] ' + detail + '  @ ' + where);
      });
      window.addEventListener('unhandledrejection', function (e) {
        var r = e.reason;
        var detail = (r && (r.message || r.stack)) ? String(r.message || r.stack) : String(r);
        console.error('[unhandledrejection] ' + detail);
      });
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
