package dev.opencode.mobile

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * A loopback DNS forwarder.
 *
 * The backend is a musl binary. musl reads `/etc/resolv.conf` to find name
 * servers, and Android has no such file for apps — so musl falls back to its
 * compiled-in default of `127.0.0.1:53`. Nothing listens there, every lookup
 * times out, and Bun reports `getaddrinfo EAI_AGAIN`. That is why `curl` from the
 * shell resolves fine (bionic asks Android's own resolver) while `webfetch`,
 * `websearch` and Code Mode `fetch` all fail on the same box.
 *
 * We cannot give musl a resolv.conf — the path is fixed at its build time and
 * `/etc` is not writable. Instead we answer the fallback directly: bind
 * 127.0.0.1:53 and resolve each query through Android's resolver, which does
 * know the network's nameservers.
 *
 * Android 10+ sets `ip_unprivileged_port_start=0`, so binding port 53 needs no
 * special privilege. If the bind still fails the forwarder reports it through
 * [lastError] rather than taking the app down.
 */
object DnsForwarder {

    private const val TAG = "DnsForwarder"
    private const val PORT = 53
    private const val TTL_SECONDS = 60

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var socket: DatagramSocket? = null

    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (running) return
        val sock = try {
            DatagramSocket(PORT, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            lastError = "bind 127.0.0.1:$PORT failed: ${e.javaClass.simpleName}: ${e.message}"
            return
        }
        socket = sock
        running = true
        lastError = null
        thread = Thread({ serve(sock) }, "dns-forwarder").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        socket?.close()
        socket = null
        thread?.interrupt()
        thread = null
    }

    private fun serve(sock: DatagramSocket) {
        val buffer = ByteArray(4096)
        while (running && !sock.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                sock.receive(packet)
            } catch (e: Exception) {
                if (running) lastError = "receive: ${e.javaClass.simpleName}: ${e.message}"
                continue
            }
            val reply = try {
                respond(packet.data, packet.length)
            } catch (e: Exception) {
                lastError = "respond: ${e.javaClass.simpleName}: ${e.message}"
                null
            } ?: continue
            try {
                sock.send(
                    DatagramPacket(reply, reply.size, packet.address, packet.port),
                )
            } catch (e: Exception) {
                if (running) lastError = "send: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28

    /**
     * Answers one DNS query. Only the first question is handled, and only
     * uncompressed names — a compressed QNAME would mean a re-query, which no
     * resolver sends in a question.
     */
    private fun respond(data: ByteArray, length: Int): ByteArray? {
        if (length < 17) return null

        val id = u16(data, 0)
        val flags = u16(data, 2)

        var pos = 12
        val name = StringBuilder()
        while (true) {
            if (pos >= length) return null
            val label = data[pos].toInt() and 0xFF
            if (label == 0) {
                pos++
                break
            }
            // Top bits set means a compression pointer, which we do not expect here.
            if (label and 0xC0 != 0) return null
            if (pos + 1 + label > length) return null
            if (name.isNotEmpty()) name.append('.')
            name.appendString(data, pos + 1, label)
            pos += 1 + label
        }
        if (pos + 4 > length) return null
        val qtype = u16(data, pos)
        val qclass = u16(data, pos + 2)
        val host = name.toString()
        if (host.isEmpty()) return null

        val resolved = try {
            InetAddress.getAllByName(host)
        } catch (e: UnknownHostException) {
            null
        }

        val answers = when {
            resolved == null -> emptyList()
            qtype == TYPE_A -> resolved.filterIsInstance<Inet4Address>()
            qtype == TYPE_AAAA -> resolved.filterIsInstance<Inet6Address>()
            else -> resolved.toList()
        }

        val out = ByteArrayOutputStream()
        write16(out, id)
        // QR=1, RA=1, RD copied from the query; NXDOMAIN only when nothing answered.
        var responseFlags = 0x8000 or 0x0080 or (flags and 0x0100)
        if (resolved == null) responseFlags = responseFlags or 3
        write16(out, responseFlags)
        write16(out, 1) // QDCOUNT
        write16(out, answers.size) // ANCOUNT
        write16(out, 0) // NSCOUNT
        write16(out, 0) // ARCOUNT

        writeName(out, host)
        write16(out, qtype)
        write16(out, qclass)

        for (addr in answers) {
            // Pointer back to the QNAME, which sits at offset 12 in our reply.
            out.write(0xC0)
            out.write(0x0C)
            write16(out, qtype)
            write16(out, qclass)
            write32(out, TTL_SECONDS.toLong())
            val rdata = addr.address
            write16(out, rdata.size)
            out.write(rdata)
        }
        return out.toByteArray()
    }

    private fun u16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun StringBuilder.appendString(bytes: ByteArray, at: Int, len: Int) {
        for (i in 0 until len) {
            append((bytes[at + i].toInt() and 0xFF).toChar())
        }
    }

    private fun writeName(out: ByteArrayOutputStream, host: String) {
        for (label in host.split('.')) {
            if (label.isEmpty()) continue
            val bytes = label.toByteArray(Charsets.US_ASCII)
            val capped = if (bytes.size > 63) bytes.copyOfRange(0, 63) else bytes
            out.write(capped.size)
            out.write(capped)
        }
        out.write(0)
    }

    private fun write16(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun write32(out: ByteArrayOutputStream, value: Long) {
        out.write(((value shr 24) and 0xFF).toInt())
        out.write(((value shr 16) and 0xFF).toInt())
        out.write(((value shr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }
}
