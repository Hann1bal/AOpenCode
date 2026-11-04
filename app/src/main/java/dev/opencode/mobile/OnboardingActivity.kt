package dev.opencode.mobile

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.opencode.mobile.databinding.ActivityOnboardingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * First-run (or "edit") screen: point the app at an inference endpoint, pick a
 * model from the ones that endpoint actually serves, then write the opencode
 * config + auth files into the backend's XDG home.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    /** Catalog last fetched, and the endpoint it belongs to. */
    private var models: List<String> = emptyList()
    private var loadedFor: String? = null
    private var loading = false

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

        setupModelField()
        binding.testButton.setOnClickListener { runTest() }
        binding.saveButton.setOnClickListener { saveAndFinish() }
        binding.reloadModels.setOnClickListener { loadModels(show = true) }
    }

    private fun currentBase(): String = binding.serverUrl.text.toString().trim().trimEnd('/')

    private fun currentKey(): String =
        binding.apiKey.text.toString().trim().ifEmpty { Prefs.apiKey }

    private fun fingerprint(base: String, key: String): String = "$base|$key"

    private fun setupModelField() {
        // Focusing the field is the natural moment to fetch: the endpoint and key
        // are filled in by then, and the list is cached for subsequent opens.
        binding.modelId.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) loadModels(show = true)
        }

        val invalidate = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // The cached catalog belongs to one endpoint. Drop the fingerprint
                // so a stale list can never be saved against a different server.
                loadedFor = null
            }
        }
        binding.serverUrl.addTextChangedListener(invalidate)
        binding.apiKey.addTextChangedListener(invalidate)
    }

    private fun loadModels(show: Boolean) {
        if (loading) return
        val base = currentBase()
        val key = currentKey()
        if (base.isEmpty() || !base.startsWith("http")) {
            setStatus(getString(R.string.models_need_endpoint), R.color.setup_neutral)
            return
        }

        val fp = fingerprint(base, key)
        if (models.isNotEmpty() && loadedFor == fp) {
            if (show) binding.modelId.showDropDown()
            return
        }

        loading = true
        binding.modelProgress.visibility = View.VISIBLE
        setStatus(getString(R.string.models_status_loading), R.color.setup_neutral)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { ModelDiscovery.fetch(base, key) }
            loading = false
            binding.modelProgress.visibility = View.GONE

            when (result) {
                is ModelDiscovery.Result.Success -> {
                    models = result.models
                    loadedFor = fp
                    binding.modelId.setSimpleItems(models.toTypedArray())
                    setStatus(getString(R.string.models_status_loaded, models.size), R.color.setup_ok)
                    if (binding.modelId.text.isNullOrEmpty()) {
                        binding.modelId.setText(models.first(), false)
                    }
                    if (show) binding.modelId.showDropDown()
                }

                is ModelDiscovery.Result.Failure -> {
                    setStatus(
                        getString(R.string.models_status_failed, result.message) +
                            " " + getString(R.string.model_manual_note),
                        R.color.setup_error,
                    )
                }
            }
        }
    }

    private fun setStatus(text: String, colorRes: Int) {
        binding.modelStatus.text = text
        binding.modelStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun collectInputs(): Triple<String, String, String>? {
        val base = currentBase()
        val key = currentKey()
        val model = binding.modelId.text.toString().trim()
        val name = binding.providerName.text.toString().trim().ifEmpty { "My Server" }

        if (base.isEmpty() || !base.startsWith("http")) {
            binding.serverUrl.error = "Enter a full URL, e.g. http://192.168.1.10:8080/v1"
            return null
        }
        if (key.isEmpty()) {
            binding.apiKey.error = "API key is required"
            return null
        }
        if (model.isEmpty()) {
            binding.modelId.error = "Pick a model from the list or type its id"
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
            val conn = java.net.URL("$base/models").openConnection() as java.net.HttpURLConnection
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

        // Register the whole fetched catalog, not just the picked model, so the
        // backend can switch models without reconfiguring. Only trust the cache
        // when it was fetched against the endpoint we are about to save.
        val catalog = if (loadedFor == fingerprint(base, key)) models else emptyList()

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { writeOpencodeConfig(base, key, model, name, catalog) }
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
    private fun writeOpencodeConfig(
        base: String,
        key: String,
        model: String,
        name: String,
        catalog: List<String>,
    ) {
        val configDir = File(BackendManager.homeDir(this), ".config/opencode")
        configDir.mkdirs()

        val settings = JSONObject().put("baseURL", base)

        val modelsJson = JSONObject()
        for (id in (listOf(model) + catalog).filter { it.isNotBlank() }.distinct()) {
            modelsJson.put(id, JSONObject().put("modelID", id).put("name", id))
        }

        val provider = JSONObject()
            .put("name", name)
            .put("package", "aisdk:@ai-sdk/openai-compatible")
            .put("settings", settings)
            .put("models", modelsJson)

        val config = JSONObject()
            .put("\$schema", "https://opencode.ai/config.json")
            .put("providers", JSONObject().put("custom", provider))

        File(configDir, "opencode.json").writeText(config.toString(2))

        val auth = JSONObject().put(
            "custom",
            JSONObject().put("type", "api").put("key", key)
        )
        File(configDir, "auth.json").writeText(auth.toString(2))
    }
}
