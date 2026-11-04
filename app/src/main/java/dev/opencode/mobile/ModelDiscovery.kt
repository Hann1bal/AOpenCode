package dev.opencode.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lists the models an OpenAI-compatible endpoint exposes, via `GET {base}/models`.
 *
 * Two response shapes are accepted because self-hosted gateways disagree:
 *   {"data":[{"id":"a"},{"id":"b"}]}   — OpenAI style
 *   ["a","b"]                          — bare array
 * Object entries that carry no usable `id` are skipped rather than guessed at.
 */
object ModelDiscovery {

    sealed class Result {
        data class Success(val models: List<String>) : Result()
        data class Failure(val message: String) : Result()
    }

    fun fetch(base: String, key: String): Result {
        return try {
            val conn = URL("$base/models").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/json")
                if (key.isNotEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer $key")
                }
                val code = conn.responseCode
                if (code == 401 || code == 403) {
                    return Result.Failure("key rejected (HTTP $code)")
                }
                if (code !in 200..299) {
                    // Some gateways answer 404 on /models without supporting listing.
                    return Result.Failure("HTTP $code")
                }
                val body = conn.inputStream.bufferedReader().readText()
                val models = parseModels(body)
                if (models.isEmpty()) Result.Failure("no models in response") else Result.Success(models)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result.Failure(e.message ?: "network error")
        }
    }

    /** Pure so the parser can be reasoned about without a live endpoint. */
    fun parseModels(body: String): List<String> {
        val trimmed = body.trim()
        val out = LinkedHashSet<String>()
        try {
            if (trimmed.startsWith("[")) {
                collect(JSONArray(trimmed), out)
                return out.toList()
            }
            val obj = JSONObject(trimmed)
            val list = obj.optJSONArray("data") ?: obj.optJSONArray("models") ?: return out.toList()
            collect(list, out)
        } catch (_: Exception) {
            // Malformed JSON: treat as "nothing listed" and let the caller fall
            // back to manual entry instead of failing the whole setup.
        }
        return out.toList()
    }

    private fun collect(arr: JSONArray, into: LinkedHashSet<String>) {
        for (i in 0 until arr.length()) {
            when (val v = arr.opt(i)) {
                is String -> if (v.isNotBlank()) into.add(v.trim())
                is JSONObject -> v.optString("id").trim().let { if (it.isNotEmpty()) into.add(it) }
            }
        }
    }
}
