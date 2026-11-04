package dev.opencode.mobile.sync

import dev.opencode.mobile.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * A paired upstream OpenCode server (typically the home machine).
 *
 * The local opencode backend follows this peer's session event log and mirrors
 * its sessions into the local database as read-only replicas.
 */
data class Peer(
    val id: String,
    val name: String,
    val baseUrl: String,
    val token: String,
    val serverVersion: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val active: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("baseUrl", baseUrl)
        put("token", token)
        put("serverVersion", serverVersion)
        put("createdAt", createdAt)
        put("active", active)
    }

    companion object {
        fun fromJson(obj: JSONObject): Peer = Peer(
            id = obj.getString("id"),
            name = obj.getString("name"),
            baseUrl = obj.getString("baseUrl"),
            token = obj.getString("token"),
            serverVersion = obj.optString("serverVersion", ""),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            active = obj.optBoolean("active", true),
        )
    }
}

/**
 * Persisted registry of paired upstream servers. Exactly one peer may be active
 * at a time: the local backend follows a single upstream.
 */
object PeerStore {

    fun all(): List<Peer> {
        val arr = JSONArray(Prefs.peersJson)
        return (0 until arr.length()).map { Peer.fromJson(arr.getJSONObject(it)) }
    }

    private fun save(peers: List<Peer>) {
        val arr = JSONArray()
        peers.forEach { arr.put(it.toJson()) }
        Prefs.peersJson = arr.toString()
    }

    fun active(): Peer? = all().firstOrNull { it.active }

    /** Add a peer and make it the active upstream, deactivating the previous one. */
    fun addAndActivate(peer: Peer) {
        val peers = all().toMutableList()
        peers.removeAll { it.id == peer.id }
        val updated = peers.map { it.copy(active = false) } + peer.copy(active = true)
        save(updated)
    }

    fun setActive(id: String) {
        save(all().map { it.copy(active = it.id == id) })
    }

    fun remove(id: String) {
        save(all().filter { it.id != id })
    }
}
