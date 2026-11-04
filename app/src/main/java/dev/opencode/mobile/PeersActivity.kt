package dev.opencode.mobile

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.integration.android.IntentIntegrator
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

    private lateinit var binding: ActivityPeersBinding

    private val scanLauncher = registerForActivityResult(
        com.google.zxing.integration.android.IntentIntegrator.ScanContract()
    ) { result ->
        if (result.contents != null) {
            pairWith(result.contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPeersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.scanButton.setOnClickListener {
            IntentIntegrator(this).apply {
                setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                setBeepEnabled(false)
                setOrientationLocked(true)
            }.initiateScan()
        }

        binding.pasteButton.setOnClickListener { showPasteDialog() }

        binding.peerList.setOnItemLongClickListener { _, _, position, _ ->
            showPeerMenu(peerAt(position))
            true
        }

        render()
    }

    private fun peerAt(position: Int): Peer = allPeers()[position]

    private fun allPeers(): List<Peer> = PeerStore.all()

    private fun render() {
        val peers = allPeers()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, peers.map { label(it) })
        binding.peerList.adapter = adapter
        binding.emptyView.visibility = if (peers.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.peerList.emptyView = binding.emptyView
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
        val options = if (peer.active) {
            arrayOf(getString(R.string.peer_make_active_again), getString(R.string.peer_remove))
        } else {
            arrayOf(getString(R.string.peer_activate), getString(R.string.peer_remove))
        }
        AlertDialog.Builder(this)
            .setTitle(peer.name)
            .setItems(options) { _, which ->
                when (options[which]) {
                    getString(R.string.peer_activate) -> {
                        PeerStore.setActive(peer.id)
                        restartBackend()
                        render()
                    }
                    getString(R.string.peer_remove) -> {
                        PeerStore.remove(peer.id)
                        restartBackend()
                        render()
                    }
                }
            }
            .show()
    }

    private fun pairWith(input: String) {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { PairingClient.pair(input) }
                PeerStore.addAndActivate(result.peer)
                restartBackend()
                render()
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
