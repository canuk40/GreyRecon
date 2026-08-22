package com.greyrecon.app.ui.terminal

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/**
 * A tiny loopback-only TCP server bridging `greyrecon-pkg`'s shell script to real HTTPS fetches.
 * Toybox (see `setupToyboxBinDir()`) has no HTTP(S) client applet in this build, and hand-rolling TLS
 * for a shell-invoked native binary would mean shipping/maintaining our own TLS stack -- Android's own
 * `HttpURLConnection`/`HttpsURLConnection` already does this correctly (real certificate validation
 * against the system trust store), so the fetch itself happens here, in-process, and the shell just
 * asks for it over a local socket.
 *
 * Protocol (deliberately minimal, and deliberately never puts a downloaded file's bytes on the wire):
 * client connects to 127.0.0.1:[port], sends one line `<destPath>\t<url>\n`, the server performs the
 * GET and writes the body directly to `destPath` itself (it already has full access to app storage --
 * no need to stream bytes back over the socket at all), then replies with exactly one line -- `OK
 * <bytes>` or `ERR <message>` -- and closes. Because the body never crosses the socket, there's no
 * binary/text-mixing to get wrong on the shell side (see `greyrecon-pkg.sh`'s `do_fetch`, which just
 * captures that single reply line via toybox's own `nc`).
 *
 * Bound to 127.0.0.1 (not 0.0.0.0), so only processes already running on this device can reach it at
 * all -- and even then, `destPath` is validated to resolve under this app's own files dir before
 * anything is written, so a malicious local caller can't turn this into an arbitrary-file-write
 * primitive pointed outside app storage. Not meaningfully more attack surface than bundling a `curl`
 * binary would already carry (any app-local tool that fetches URLs to files is the same shape).
 */
class PkgFetchServer(private val filesDir: File) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        val socket = try {
            ServerSocket(FIXED_PORT, 50, InetAddress.getByName("127.0.0.1"))
        } catch (e: IOException) {
            // Most likely a leftover instance from a previous TerminalScreen composition still
            // holding the port -- treat as already-running rather than failing terminal setup.
            Log.w(TAG, "bind failed, assuming a server is already running", e)
            return
        }
        serverSocket = socket
        running = true
        thread(name = "greyrecon-pkg-fetch-accept", isDaemon = true) {
            while (running) {
                try {
                    val client = socket.accept()
                    thread(name = "greyrecon-pkg-fetch-client", isDaemon = true) { handleClient(client) }
                } catch (e: IOException) {
                    if (running) Log.w(TAG, "accept() failed", e)
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            // Already closing -- nothing to act on.
        }
        serverSocket = null
    }

    private fun handleClient(client: Socket) {
        client.use { s ->
            s.soTimeout = REQUEST_TIMEOUT_MS
            val line = s.getInputStream().bufferedReader().readLine() ?: return
            val parts = line.split("\t", limit = 2)
            if (parts.size != 2) {
                reply(s, "ERR malformed request")
                return
            }
            val (destPath, url) = parts
            val dest = File(destPath).canonicalFile
            if (!dest.path.startsWith(filesDir.canonicalPath)) {
                reply(s, "ERR destination outside app storage")
                return
            }
            try {
                val bytes = fetch(url)
                dest.parentFile?.mkdirs()
                dest.writeBytes(bytes)
                reply(s, "OK ${bytes.size}")
            } catch (e: Exception) {
                reply(s, "ERR ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun fetch(urlString: String): ByteArray {
        var url = URL(urlString)
        require(url.protocol == "https" || url.protocol == "http") { "unsupported scheme: ${url.protocol}" }

        var redirects = 0
        while (true) {
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.connect()

            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location == null) throw IOException("redirect with no Location header")
                if (++redirects > MAX_REDIRECTS) throw IOException("too many redirects")
                url = URL(url, location)
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("HTTP $code")
            }

            val body = conn.inputStream.use { readBounded(it, MAX_DOWNLOAD_BYTES) }
            conn.disconnect()
            return body
        }
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IOException("download exceeds ${maxBytes / (1024 * 1024)}MB limit")
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    private fun reply(s: Socket, message: String) {
        try {
            s.getOutputStream().write("$message\n".toByteArray())
        } catch (e: IOException) {
            // Client already gone -- nothing to act on.
        }
    }

    companion object {
        private const val TAG = "GreyReconPkgFetch"
        const val FIXED_PORT = 47823
        private const val REQUEST_TIMEOUT_MS = 30_000
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 5
        private const val MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024
    }
}
