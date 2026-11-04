package dev.opencode.mobile.sync

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

        val info = getJson("$base/api/info")
        val version = info.optString("version", "")
        val hostname = info.optString("hostname", base)

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
            return stripTrailingSlash(urls.getString(0)) to code
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

    private fun getJson(url: String): JSONObject {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
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
