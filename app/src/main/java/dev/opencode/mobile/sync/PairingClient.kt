package dev.opencode.mobile.sync

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/**
 * Pairs with a remote OpenCode server using a pairing link produced by
 * `opencode pair` on the home machine.
 *
 * Supported inputs:
 *  - A pairing link:  http(s)://host:port/auth/connect/<code>
 *  - The QR payload printed by `opencode pair`:
 *      {"code":"<code>","urls":["http(s)://host:port", ...]}
 *
 * The link is single-use and expires in ~5 minutes. Redeeming it yields a
 * session token (valid ~30 days) used as the Basic-auth password.
 */
object PairingClient {

    class PairingException(message: String) : Exception(message)

    data class PairingResult(
        val peer: Peer,
        val hostname: String,
    )

    fun pair(input: String): PairingResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw PairingException("Пустая ссылка")

        val (base, code) = parseInput(trimmed)
        val connectUrl = "$base/auth/connect/$code"

        val token = getJson(connectUrl).optString("token")
        if (token.isNullOrEmpty()) {
            throw PairingException("Сервер не выдал токен. Ссылка устарела или уже использована.")
        }

        // /api/info is not public once a server password is set, so it has to carry
        // the token we just redeemed - unauthenticated it answers 401
        // UnauthorizedError. Pairing is already complete by then: the credential is
        // in hand, so losing the informational call must not throw it away. Before
        // this, a perfectly good pairing died here and the error blamed the code.
        val info = runCatching { getJson("$base/api/info", token) }.getOrNull()
        val version = info?.optString("version", "").orEmpty()
        // hostname is optional in ServerInfo: runtimes without one omit it.
        val hostname = info?.optString("hostname", "").takeUnless { it.isNullOrEmpty() } ?: base

        val peer = Peer(
            id = UUID.randomUUID().toString(),
            name = hostname,
            baseUrl = base,
            token = token,
            serverVersion = version,
        )
        return PairingResult(peer, hostname)
    }

    /** Resolve (baseUrl, code) from a pairing link or the QR JSON payload. */
    private fun parseInput(input: String): Pair<String, String> {
        if (input.startsWith("{")) {
            val obj = try {
                JSONObject(input)
            } catch (e: Exception) {
                throw PairingException("Не удалось разобрать QR- payload как JSON")
            }
            val code = obj.optString("code")
            val urls = obj.optJSONArray("urls")
            if (code.isEmpty() || urls == null || urls.length() == 0) {
                throw PairingException("В QR нет code/urls")
            }
            // A QR carries every address the server advertises, and it may well be
            // a screenshot of somebody's terminal. Taking the first entry blindly
            // can route the pairing through a public relay when a private path
            // was sitting right there, so order by how far each address reaches
            // and take the most private one.
            val ranked = (0 until urls.length())
                .map { urls.getString(it) }
                .sortedBy { reachScope(it) }
            return stripTrailingSlash(ranked.first()) to code
        }

        val uri = try {
            URI(input)
        } catch (e: Exception) {
            throw PairingException("Некорректная ссылка")
        }
        val path = uri.path ?: throw PairingException("В ссылке нет пути /auth/connect/<code>")
        val marker = "/auth/connect/"
        val idx = path.indexOf(marker)
        if (idx < 0) throw PairingException("Ожидается ссылка вида $marker<code>")
        val code = path.substring(idx + marker.length).substringBefore("/")
        if (code.isEmpty()) throw PairingException("Пустой код в ссылке")
        val base = "${uri.scheme}://${uri.authority}"
        return stripTrailingSlash(base) to code
    }

    private fun stripTrailingSlash(s: String) = s.removeSuffix("/")

    private val tailnetV4 = Regex("^100\\.(6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\.")
    private val privateV4 = Regex("^(10\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.|169\\.254\\.)")

    /**
     * How far a pairing address reaches; lower is preferred.
     *
     * Mirrors the classifier `opencode pair` uses so the two agree on what counts
     * as public. Loopback ranks above the public internet on purpose: it only
     * works when the peer is this very device, but at least the traffic never
     * leaves it, while a tunnel address hands it to a third party.
     */
    private fun reachScope(url: String): Int {
        val host = runCatching { URI(url).host }.getOrNull()
            ?.trim('[', ']')
            ?.lowercase()
            ?: return 3
        return when {
            host.startsWith("127.") || host == "localhost" || host == "::1" -> 2
            tailnetV4.matches(host) || host.startsWith("fd") || host.startsWith("fc") -> 0
            privateV4.matches(host) || host.endsWith(".local") -> 1
            else -> 3
        }
    }

    /**
     * GETs a JSON body. When [token] is given it is sent as HTTP Basic with the
     * username the server hardcodes (`opencode`), which is how a pairing session
     * token is meant to be presented.
     *
     * `Accept` stays `application/json` deliberately: the connect route answers a
     * browser-looking `Accept: text/html` with a 302 and a cookie instead of the
     * JSON body we need.
     */
    private fun getJson(url: String, token: String? = null): JSONObject {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) {
                val creds = Base64.encodeToString(
                    ("opencode:" + token).toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP,
                )
                conn.setRequestProperty("Authorization", "Basic $creds")
            }
            val code = conn.responseCode
            if (code == 401 || code == 403) {
                throw PairingException("Сервер отклонил пару (HTTP $code). Проверь, что `opencode pair` запущен и код свежий.")
            }
            if (code !in 200..299) {
                throw PairingException("HTTP $code от $url")
            }
            val body = conn.inputStream.bufferedReader().readText()
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }
}
