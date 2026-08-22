package com.greyrecon.app.mcp

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.util.collections.ConcurrentMap
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val MCP_SESSION_ID_HEADER = "mcp-session-id"

/**
 * MCP server (Streamable HTTP transport) exposing GreyRecon's scan data as tools a user's own
 * self-hosted nanobot instance -- or any MCP client -- can call. Pro-tier feature (see
 * SettingsScreen for the entitlement gate).
 *
 * Built on the official `io.modelcontextprotocol:kotlin-sdk-server` + Ktor (CIO engine, not the
 * SDK's own Netty-based sample default -- CIO is pure-Kotlin/coroutines with no native transport,
 * a better fit for Android) instead of a hand-rolled NanoHTTPD JSON-RPC dispatcher. Real protocol
 * compliance (session ids, SSE stream, capability negotiation) instead of a hardcoded
 * `protocolVersion` string with no update path.
 *
 * Requires a bearer token on every request: this exposes real scan data and scan-triggering to
 * anything on the local network, which matters for a pentesting tool. No token, no anonymous
 * access -- unlike the SDK's own sample, which allows an unauthenticated path when no token is
 * configured.
 */
fun Application.configureGreyReconMcp(authToken: String, dataSource: ScanDataSource) {
    install(SSE)
    install(ContentNegotiation) { json(McpJson) }
    install(Authentication) {
        bearer("mcp-bearer") {
            authenticate { credential ->
                if (credential.token == authToken) io.ktor.server.auth.UserIdPrincipal("mcp-client") else null
            }
        }
    }

    val transports = ConcurrentMap<String, StreamableHttpServerTransport>()

    routing {
        authenticate("mcp-bearer") {
            route("/mcp") {
                sse {
                    val transport = findTransport(call, transports) ?: return@sse
                    transport.handleRequest(this, call)
                }
                post {
                    val transport = getOrCreateTransport(call, transports, dataSource) ?: return@post
                    transport.handleRequest(null, call)
                }
                delete {
                    val transport = findTransport(call, transports) ?: return@delete
                    transport.handleRequest(null, call)
                }
            }
        }
    }
}

private suspend fun findTransport(
    call: ApplicationCall,
    transports: ConcurrentMap<String, StreamableHttpServerTransport>,
): StreamableHttpServerTransport? {
    val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
    if (sessionId.isNullOrEmpty()) {
        call.respond(HttpStatusCode.BadRequest, "Bad Request: No valid session ID provided")
        return null
    }
    val transport = transports[sessionId]
    if (transport == null) {
        call.respond(HttpStatusCode.NotFound, "Session not found")
    }
    return transport
}

private suspend fun getOrCreateTransport(
    call: ApplicationCall,
    transports: ConcurrentMap<String, StreamableHttpServerTransport>,
    dataSource: ScanDataSource,
): StreamableHttpServerTransport? {
    val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
    if (sessionId != null) {
        val transport = transports[sessionId]
        if (transport == null) call.respond(HttpStatusCode.NotFound, "Session not found")
        return transport
    }

    val transport = StreamableHttpServerTransport(StreamableHttpServerTransport.Configuration(enableJsonResponse = true))
    transport.setOnSessionInitialized { initializedSessionId -> transports[initializedSessionId] = transport }
    transport.setOnSessionClosed { closedSessionId -> transports.remove(closedSessionId) }

    val server = buildMcpServer(dataSource)
    server.onClose { transport.sessionId?.let { transports.remove(it) } }
    server.createSession(transport)

    return transport
}

private fun buildMcpServer(dataSource: ScanDataSource): Server {
    val server = Server(
        Implementation(name = "greyrecon", version = "1.0.0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    )

    McpTools.definitions().forEach { def ->
        server.addTool(
            name = def.name,
            description = def.description,
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    def.parameters.forEach { (key, desc) ->
                        putJsonObject(key) {
                            put("type", "string")
                            put("description", desc)
                        }
                    }
                },
                required = def.required,
            ),
        ) { request ->
            McpTools.call(def.name, request.arguments, dataSource).fold(
                onSuccess = { text -> CallToolResult(content = listOf(TextContent(text))) },
                onFailure = { e -> CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true) },
            )
        }
    }

    return server
}
