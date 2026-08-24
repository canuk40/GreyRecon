package com.greyrecon.app.ui.terminal

import android.content.Context
import android.util.Log
import com.greyrecon.app.ai.agent.AgentEvent
import com.greyrecon.app.ai.agent.AgentFactory
import com.greyrecon.app.data.SecureKeyStore
import com.greyrecon.app.mcp.ScanDataSource
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Loopback bridge that makes the on-device AI agent reachable from the terminal, via an `ai` shell
 * command (see `assets/ai.sh`). Same shape and rationale as [PkgFetchServer]: toybox has no way to
 * call into the app's Kotlin, so the shell talks to an in-process server over 127.0.0.1 instead.
 *
 * Protocol: the client sends the user's prompt as one line; the server runs one full agent turn
 * (using the provider/key from Settings and the same [ScanDataSource]/tool registry the chat screen
 * and MCP server use), streaming each step back as it happens -- "> tool..." while a tool runs, then
 * the final answer -- and closes. Each `ai` invocation is an independent one-shot turn (no cross-call
 * memory), which is the natural shell semantics; the chat screen is where multi-turn context lives.
 *
 * Bound to 127.0.0.1 only, so nothing off-device can reach it. It never writes files or takes a path
 * from the client -- it only runs the agent's own read-only recon tools -- so it carries less surface
 * than [PkgFetchServer] does.
 */
class AgentBridgeServer(private val context: Context) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        val socket = try {
            ServerSocket(FIXED_PORT, 50, InetAddress.getByName("127.0.0.1"))
        } catch (e: IOException) {
            Log.w(TAG, "bind failed, assuming a server is already running", e)
            return
        }
        serverSocket = socket
        running = true
        thread(name = "greyrecon-agent-accept", isDaemon = true) {
            while (running) {
                try {
                    val client = socket.accept()
                    thread(name = "greyrecon-agent-client", isDaemon = true) { handleClient(client) }
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
            // Already closing.
        }
        serverSocket = null
    }

    private fun handleClient(client: Socket) {
        client.use { s ->
            s.soTimeout = REQUEST_TIMEOUT_MS
            val out = s.getOutputStream()
            fun send(text: String) {
                try {
                    out.write(text.toByteArray())
                    out.flush()
                } catch (e: IOException) {
                    // Client gone (e.g. nc timed out) -- nothing to do.
                }
            }

            val prompt = try {
                s.getInputStream().bufferedReader().readLine()?.trim()
            } catch (e: IOException) {
                null
            }
            if (prompt.isNullOrEmpty()) {
                send("usage: ai <question>\n")
                return
            }

            val backend = AgentFactory.create(SecureKeyStore(context), ScanDataSource(context)).getOrElse { e ->
                send("${e.message ?: "AI Assistant isn't configured."}\n")
                return
            }

            try {
                runBlocking {
                    backend.send(prompt) { event ->
                        send(
                            when (event) {
                                is AgentEvent.ToolStart ->
                                    CYAN + "> " + event.tool +
                                        (if (event.argsPreview.isBlank()) "" else "(${event.argsPreview})") + "..." + RESET + "\n"
                                is AgentEvent.ToolEnd ->
                                    CYAN + "  done " + event.tool +
                                        (if (event.resultPreview.isBlank()) "" else " -- ${event.resultPreview}") + RESET + "\n"
                                is AgentEvent.Answer -> "\n" + event.text + "\n"
                                is AgentEvent.Failed -> RED + "error: " + event.message + RESET + "\n"
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                send("error: ${e.message ?: e.javaClass.simpleName}\n")
            }
        }
    }

    companion object {
        private const val TAG = "GreyReconAgentBridge"
        const val FIXED_PORT = 47824
        private const val REQUEST_TIMEOUT_MS = 180_000
        private const val CYAN = "[36m"
        private const val RED = "[31m"
        private const val RESET = "[0m"
    }
}
