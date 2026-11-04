package dev.opencode.mobile

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.opencode.mobile.databinding.ActivityOnboardingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * First-run (or "edit") screen: configure the inference endpoint, key and model,
 * then write the opencode config + auth files into the backend's XDG home.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val editing = intent.getBooleanExtra("edit", false)
        if (editing && Prefs.configured) {
            binding.providerName.setText(Prefs.providerName)
            binding.serverUrl.setText(Prefs.serverUrl)
            binding.modelId.setText(Prefs.modelId)
            if (Prefs.apiKey.isNotEmpty()) {
                binding.apiKey.hint = "•••••••• (leave empty to keep current key)"
            }
        }

        binding.testButton.setOnClickListener { runTest() }
        binding.saveButton.setOnClickListener { saveAndFinish() }
    }

    private fun collectInputs(): Triple<String, String, String>? {
        val base = binding.serverUrl.text.toString().trim().trimEnd('/')
        val key = binding.apiKey.text.toString().trim().ifEmpty { Prefs.apiKey }
        val model = binding.modelId.text.toString().trim()
        val name = binding.providerName.text.toString().trim().ifEmpty { "My Server" }

        if (base.isEmpty() || !base.startsWith("http")) {
            binding.serverUrl.error = "Enter a full URL, e.g. https://example.com/v1"
            return null
        }
        if (key.isEmpty()) {
            binding.apiKey.error = "API key is required"
            return null
        }
        if (model.isEmpty()) {
            binding.modelId.error = "Model id is required"
            return null
        }
        return Triple(base, key, model)
    }

    private fun runTest() {
        val inputs = collectInputs() ?: return
        val (base, key, _) = inputs
        binding.testButton.isEnabled = false
        binding.testResult.text = "Testing connection…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { probeEndpoint(base, key) }
            binding.testButton.isEnabled = true
            binding.testResult.text = result.first
            binding.testResult.setTextColor(
                if (result.second) 0xFF2E7D32.toInt() else 0xFFC62828.toInt()
            )
        }
    }

    private fun probeEndpoint(base: String, key: String): Pair<String, Boolean> {
        return try {
            val conn = URL("$base/models").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $key")
            val code = conn.responseCode
            conn.disconnect()
            when (code) {
                200 -> "OK — endpoint reachable, key accepted" to true
                401, 403 -> "Failed — key rejected (HTTP $code)" to false
                else -> "HTTP $code — check the base URL" to false
            }
        } catch (e: Exception) {
            "Network error: ${e.message}" to false
        }
    }

    private fun saveAndFinish() {
        val inputs = collectInputs() ?: return
        val (base, key, model) = inputs
        val name = binding.providerName.text.toString().trim().ifEmpty { "My Server" }

        Prefs.providerName = name
        Prefs.serverUrl = base
        Prefs.apiKey = key
        Prefs.modelId = model

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { writeOpencodeConfig(base, key, model, name) }
                Prefs.configured = true
                startService(
                    Intent(this@OnboardingActivity, BackendService::class.java)
                        .setAction(BackendService.ACTION_START)
                )
                startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                finish()
            } catch (e: Exception) {
                Toast.makeText(
                    this@OnboardingActivity,
                    "Failed to write config: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Write provider config + auth into the backend's XDG home so `opencode serve`
     * picks them up on launch. Mirrors the layout opencode expects:
     *   $XDG_CONFIG_HOME/opencode/opencode.json
     *   $XDG_CONFIG_HOME/opencode/auth.json
     */
    private fun writeOpencodeConfig(base: String, key: String, model: String, name: String) {
        val configDir = java.io.File(
            BackendManager.homeDir(this),
            ".config/opencode"
        )
        configDir.mkdirs()

        val settings = JSONObject().put("baseURL", base)
        val modelEntry = JSONObject()
            .put("modelID", model)
            .put("name", model)
        val models = JSONObject().put(model, modelEntry)
        val provider = JSONObject()
            .put("name", name)
            .put("package", "aisdk:@ai-sdk/openai-compatible")
            .put("settings", settings)
            .put("models", models)
        val providers = JSONObject().put("custom", provider)
        val config = JSONObject()
            .put("$schema", "https://opencode.ai/config.json")
            .put("providers", providers)

        java.io.File(configDir, "opencode.json").writeText(config.toString(2))

        val auth = JSONObject().put(
            "custom",
            JSONObject().put("type", "api").put("key", key)
        )
        java.io.File(configDir, "auth.json").writeText(auth.toString(2))
    }
}
