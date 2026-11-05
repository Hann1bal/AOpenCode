package dev.opencode.mobile

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * All persistent state lives in EncryptedSharedPreferences.
 * Never back this file up (see data_extraction_rules.xml).
 */
object Prefs {
    private lateinit var prefs: SharedPreferences

    private const val KEY_CONFIGURED = "configured"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL_ID = "model_id"
    private const val KEY_PROVIDER_NAME = "provider_name"
    private const val KEY_BACKEND_TOKEN = "backend_token"
    private const val KEY_BACKEND_PORT = "backend_port"
    private const val KEY_EXTRACT_STAMP_V2 = "extract_stamp_v2"
    private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
    private const val KEY_PEERS_JSON = "peers_json"
    private const val KEY_RIPGREP_STATUS = "ripgrep_status"
    private const val KEY_SW_PURGE_STAMP = "sw_purge_stamp_v2"
    private const val KEY_ROOTFS_STAMP = "rootfs_stamp"
    private const val KEY_ROOTFS_STATUS = "rootfs_status"
    private const val KEY_ROOTFS_CHECKS = "rootfs_checks"

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        val masterKey = MasterKey.Builder(context, "opencode_master_key")
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var configured: Boolean
        get() = prefs.getBoolean(KEY_CONFIGURED, false)
        set(v) = prefs.edit().putBoolean(KEY_CONFIGURED, v).apply()

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SERVER_URL, v).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_API_KEY, v).apply()

    var modelId: String
        get() = prefs.getString(KEY_MODEL_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_MODEL_ID, v).apply()

    var providerName: String
        get() = prefs.getString(KEY_PROVIDER_NAME, "My Server") ?: "My Server"
        set(v) = prefs.edit().putString(KEY_PROVIDER_NAME, v).apply()

    /** Stable per-install random token. Passed to the backend as its server password. */
    val backendToken: String
        get() {
            var t = prefs.getString(KEY_BACKEND_TOKEN, null)
            if (t.isNullOrEmpty()) {
                val bytes = ByteArray(32)
                SecureRandom().nextBytes(bytes)
                t = bytes.joinToString("") { "%02x".format(it) }
                prefs.edit().putString(KEY_BACKEND_TOKEN, t).apply()
            }
            return t
        }

    var backendPort: Int
        get() = prefs.getInt(KEY_BACKEND_PORT, -1)
        set(v) = prefs.edit().putInt(KEY_BACKEND_PORT, v).apply()

    /**
     * Version name the bundled runtime was last unpacked for. Keyed on the name,
     * not the code: versionCode is pinned at 1, so an int stamp made every
     * install after the first look already-extracted and the backend binary was
     * never refreshed by an update.
     */
    var extractStamp: String
        get() = prefs.getString(KEY_EXTRACT_STAMP_V2, "") ?: ""
        set(v) = prefs.edit().putString(KEY_EXTRACT_STAMP_V2, v).apply()

    /** Version name the Alpine userland was last unpacked for. */
    var rootfsStamp: String
        get() = prefs.getString(KEY_ROOTFS_STAMP, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ROOTFS_STAMP, v).apply()

    /** Counts from unpacking the userland: files, links, wrappers, shebangs. */
    var rootfsStatus: String
        get() = prefs.getString(KEY_ROOTFS_STATUS, "not extracted") ?: "not extracted"
        set(v) = prefs.edit().putString(KEY_ROOTFS_STATUS, v).apply()

    /** Per-tool results of the post-unpack smoke test. */
    var rootfsChecks: String
        get() = prefs.getString(KEY_ROOTFS_CHECKS, "not run") ?: "not run"
        set(v) = prefs.edit().putString(KEY_ROOTFS_CHECKS, v).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, v).apply()

    /** Serialized list of paired upstream OpenCode servers (see sync.PeerStore). */
    var peersJson: String
        get() = prefs.getString(KEY_PEERS_JSON, "[]") ?: "[]"
        set(v) = prefs.edit().putString(KEY_PEERS_JSON, v).apply()

    /**
     * Result of running the bundled ripgrep shim at extract time. Finding the file
     * is not proof it executes, and without this the difference between "absent"
     * and "present but broken" is invisible from the UI.
     */
    var ripgrepStatus: String
        get() = prefs.getString(KEY_RIPGREP_STATUS, "not checked") ?: "not checked"
        set(v) = prefs.edit().putString(KEY_RIPGREP_STATUS, v).apply()

    /**
     * Version name for which the service worker storage was last purged. A new key
     * rather than the int one v1.0.18 wrote: reading an Int-backed entry as a
     * String throws ClassCastException.
     */
    var swPurgeStamp: String
        get() = prefs.getString(KEY_SW_PURGE_STAMP, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SW_PURGE_STAMP, v).apply()
}
