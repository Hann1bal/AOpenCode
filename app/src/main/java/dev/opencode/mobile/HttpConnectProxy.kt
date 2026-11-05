package dev.opencode.mobile

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A loopback HTTP/CONNECT proxy.
 *
 * The backend is a musl binary, and musl reads name servers from
 * `/etc/resolv.conf`. Android gives apps no such file, so musl falls back to its
 * compiled-in `127.0.0.1:53` — and on this ROM binding that port is refused
 * (`EACCES`), so a DNS forwarder cannot work at all.
 *
 * A proxy sidesteps DNS entirely. The client sends `CONNECT host:443`, and *we*
 * resolve the name through Android's own resolver, which does know the network's
 * nameservers. Loopback is excluded with `NO_PROXY` so the app's calls to its own
 * server never round-trip through here.
 */
object HttpConnectProxy {

    @Volatile
    var port: Int = -1
        private set

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private var server: ServerSocket? = null

    @Synchronized
    fun start() {
        if (running) return
        val srv = try {
            ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            lastError = "bind failed: ${e.javaClass.simpleName}: ${e.message}"
            return
        }
        server = srv
        port = srv.localPort
        running = true
        lastError = null
        Thread({ acceptLoop(srv) }, "http-proxy").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        running = false
        port = -1
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
    }

    private fun acceptLoop(srv: ServerSocket) {
        while (running && !srv.isClosed) {
            val client = try {
                srv.accept()
            } catch (e: Exception) {
                if (running) lastError = "accept: ${e.javaClass.simpleName}: ${e.message}"
                continue
            }
            Thread({ handle(client) }, "http-proxy-conn").apply { isDaemon = true; start() }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use {
                val requestLine = readLine(it.inputStream) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val target = parts[1]
                val headers = readHeaders(it.inputStream)

                if (method == "CONNECT") {
                    val endpoint = splitHostPort(target, 443)
                    if (endpoint == null) {
                        reject(client, 400, "Bad Request")
                        return
                    }
                    val upstream = connect(endpoint.first, endpoint.second)
                    if (upstream == null) {
                        reject(client, 502, "Bad Gateway")
                        return
                    }
                    client.getOutputStream()
                        .write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    pump(client, upstream)
                    return
                }

                forwardPlain(client, method, target, headers)
            }
        } catch (_: Exception) {
            // A dropped tunnel is normal; the next request is unaffected.
        }
    }

    /** Plain HTTP arrives with an absolute URI, which the upstream must see as origin-form. */
    private fun forwardPlain(
        client: Socket,
        method: String,
        target: String,
        headers: List<Pair<String, String>>,
    ) {
        val withoutScheme = target.substringAfter("://")
        val slash = withoutScheme.indexOf('/')
        val authority = if (slash == -1) withoutScheme else withoutScheme.substring(0, slash)
        val path = if (slash == -1) "/" else withoutScheme.substring(slash)
        val endpoint = splitHostPort(authority, 80)
        if (endpoint == null) {
            reject(client, 400, "Bad Request")
            return
        }
        val upstream = connect(endpoint.first, endpoint.second)
        if (upstream == null) {
            reject(client, 502, "Bad Gateway")
            return
        }
        val out = upstream.getOutputStream()
        writeAscii(out, "$method $path HTTP/1.1")
        for ((name, value) in headers) writeAscii(out, "$name: $value")
        out.write(CRLF)
        out.flush()
        pump(client, upstream)
    }

    private fun connect(host: String, port: Int): Socket? = try {
        // Android's resolver runs here, not musl's — that is the whole point.
        Socket(host, port)
    } catch (e: Exception) {
        lastError = "$host:$port unreachable: ${e.javaClass.simpleName}"
        null
    }

    private fun pump(client: Socket, upstream: Socket) {
        val up = Thread {
            try {
                client.getInputStream().copyTo(upstream.getOutputStream())
            } catch (_: Exception) {
            }
        }
        up.isDaemon = true
        up.start()
        try {
            upstream.getInputStream().copyTo(client.getOutputStream())
        } catch (_: Exception) {
        }
        try {
            upstream.close()
        } catch (_: Exception) {
        }
        try {
            client.close()
        } catch (_: Exception) {
        }
    }

    private fun reject(client: Socket, code: Int, reason: String) {
        try {
            client.getOutputStream().write(
                "HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII),
            )
        } catch (_: Exception) {
        }
        try {
            client.close()
        } catch (_: Exception) {
        }
    }

    private fun splitHostPort(value: String, defaultPort: Int): Pair<String, Int>? {
        val colon = value.lastIndexOf(':')
        if (colon == -1) return value to defaultPort
        val host = value.substring(0, colon)
        val port = value.substring(colon + 1).toIntOrNull() ?: return null
        if (host.isEmpty()) return null
        return host to port
    }

    private fun readHeaders(input: InputStream): List<Pair<String, String>> {
        val headers = mutableListOf<Pair<String, String>>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers.add(line.substring(0, idx).trim() to line.substring(idx + 1).trim())
        }
        return headers
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var byte = input.read()
        if (byte == -1) return null
        while (byte != -1 && byte != '\n'.code) {
            buffer.write(byte)
            byte = input.read()
        }
        val line = String(buffer.toByteArray(), Charsets.US_ASCII)
        return if (line.endsWith("\r")) line.dropLast(1) else line
    }

    private fun writeAscii(out: java.io.OutputStream, line: String) {
        out.write(line.toByteArray(Charsets.US_ASCII))
        out.write(CRLF)
    }

    private val CRLF = byteArrayOf(13, 10)
}
