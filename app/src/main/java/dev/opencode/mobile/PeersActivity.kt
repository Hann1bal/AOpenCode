package dev.opencode.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.opencode.mobile.databinding.ActivityPeersBinding
import dev.opencode.mobile.sync.PairingClient
import dev.opencode.mobile.sync.Peer
import dev.opencode.mobile.sync.PeerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manage paired upstream OpenCode servers. The active peer is the one the local
 * backend follows for live session sharing.
 */
class PeersActivity : AppCompatActivity() {

    companion object {
        /**
         * Set whenever the working mode changes. The backend restart is still in
         * flight when the user returns to MainActivity, so the new port is not
         * known yet — an explicit flag is more reliable than comparing URLs.
         */
        @Volatile
        var modeChanged: Boolean = false
    }

    private lateinit var binding: ActivityPeersBinding

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { pairWith(it) }
    }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchScanner() else showCameraUnavailable()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPeersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.scanButton.setOnClickListener { ensureCameraThenScan() }

        binding.pasteButton.setOnClickListener { showPasteDialog() }

        binding.workLocallyButton.setOnClickListener { confirmWorkLocally() }

        binding.peerList.setOnItemClickListener { _, _, position, _ ->
            showPeerMenu(peerAt(position))
        }

        binding.peerList.setOnItemLongClickListener { _, _, position, _ ->
            showPeerMenu(peerAt(position))
            true
        }

        render()
    }

    /**
     * CAMERA is a dangerous permission: declaring it in the manifest is not enough.
     * Until it is actually requested, the app does not even appear under
     * Settings → Apps → Permissions → Camera on many ROMs, which is why the
     * scanner looked unusable.
     */
    private fun ensureCameraThenScan() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchScanner()
            return
        }

        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.camera_needed_title)
                .setMessage(R.string.camera_needed_body)
                .setPositiveButton(R.string.allow) { _, _ ->
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
                .setNegativeButton(R.string.use_paste_link) { _, _ -> showPasteDialog() }
                .show()
            return
        }

        // First ask — or already locked out by the user. The request surfaces the
        // system dialog; a permanent denial lands in showCameraUnavailable().
        cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun launchScanner() {
        scanLauncher.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setBeepEnabled(false)
                setOrientationLocked(true)
            }
        )
    }

    /**
     * Camera is not available. Rather than dead-ending, hand the user straight to
     * the app's settings page and keep the paste path one tap away — pairing never
     * actually requires the camera.
     */
    private fun showCameraUnavailable() {
        AlertDialog.Builder(this)
            .setTitle(R.string.camera_unavailable_title)
            .setMessage(R.string.camera_unavailable_body)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null),
                    )
                )
            }
            .setNegativeButton(R.string.use_paste_link) { _, _ -> showPasteDialog() }
            .show()
    }

    private fun peerAt(position: Int): Peer = allPeers()[position]

    private fun allPeers(): List<Peer> = PeerStore.all()

    private fun render() {
        val peers = allPeers()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, peers.map { label(it) })
        binding.peerList.adapter = adapter
        binding.emptyView.visibility = if (peers.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.peerList.emptyView = binding.emptyView

        val active = PeerStore.active()
        binding.modeStatus.text = if (active == null) {
            getString(R.string.peers_mode_local)
        } else {
            getString(R.string.peers_mode_following, active.name)
        }
        binding.workLocallyButton.visibility =
            if (active == null) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun label(peer: Peer): String {
        val mark = if (peer.active) "● " else "○ "
        val ver = if (peer.serverVersion.isNotEmpty()) " · v${peer.serverVersion}" else ""
        return "$mark${peer.name}$ver"
    }

    private fun showPasteDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.peers_paste_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.peers_paste_link)
            .setView(input)
            .setPositiveButton(R.string.pair) { _, _ -> pairWith(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPeerMenu(peer: Peer) {
        if (peer.active) {
            AlertDialog.Builder(this)
                .setTitle(peer.name)
                .setItems(
                    arrayOf(
                        getString(R.string.peer_deactivate),
                        getString(R.string.peer_remove),
                    )
                ) { _, which ->
                    when (which) {
                        0 -> confirmWorkLocally()
                        else -> {
                            PeerStore.remove(peer.id)
                            afterModeChange()
                        }
                    }
                }
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(peer.name)
            .setItems(
                arrayOf(
                    getString(R.string.peer_activate),
                    getString(R.string.peer_remove),
                )
            ) { _, which ->
                when (which) {
                    0 -> {
                        PeerStore.setActive(peer.id)
                        Toast.makeText(
                            this,
                            getString(R.string.mode_switched_following, peer.name),
                            Toast.LENGTH_SHORT,
                        ).show()
                        afterModeChange()
                    }
                    else -> {
                        PeerStore.remove(peer.id)
                        afterModeChange()
                    }
                }
            }
            .show()
    }

    /**
     * Unfollowing is reversible — the pairing token survives — so this only has
     * to explain the change, not warn about losing anything.
     */
    private fun confirmWorkLocally() {
        AlertDialog.Builder(this)
            .setTitle(R.string.work_locally_title)
            .setMessage(R.string.work_locally_body)
            .setPositiveButton(R.string.work_locally) { _, _ ->
                PeerStore.deactivateAll()
                Toast.makeText(this, R.string.mode_switched_local, Toast.LENGTH_SHORT).show()
                afterModeChange()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun afterModeChange() {
        modeChanged = true
        restartBackend()
        render()
    }

    private fun pairWith(input: String) {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { PairingClient.pair(input) }
                PeerStore.addAndActivate(result.peer)
                afterModeChange()
                Toast.makeText(
                    this@PeersActivity,
                    getString(R.string.peer_paired_ok, result.hostname),
                    Toast.LENGTH_LONG,
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@PeersActivity,
                    getString(R.string.peer_paired_fail, e.message ?: "unknown"),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** Re-launch the local backend so it picks up the new upstream env. */
    private fun restartBackend() {
        startService(
            Intent(this, BackendService::class.java).setAction(BackendService.ACTION_STOP),
        )
        startService(
            Intent(this, BackendService::class.java).setAction(BackendService.ACTION_WAKE),
        )
    }
}
