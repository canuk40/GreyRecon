package com.greyrecon.app.mcp

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
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

// 1 MiB -- real MCP JSON-RPC request bodies (tool-call arguments) are tiny; this is purely a cap
// against a malicious or broken client exhausting this foreground service's memory with an
// oversized body, independent of whether it's authenticated. Modeled on the same protection the
// official kotlin-sdk-server added in v0.13.0/v0.14.0 -- not adopted here via a version bump
// because those releases require Kotlin 2.3.21+, and KSP (needed for Room's compiler) has no
// release for that Kotlin version yet (confirmed live against its Maven metadata and GitHub
// releases, latest is 2.3.11) -- so the protection is reimplemented directly against this app's
// own Ktor pipeline instead of waiting on that upstream bump.
private const val MAX_REQUEST_BODY_BYTES = 1 shl 20

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

    // Runs before auth so an oversized or rebinding-shaped request is rejected as cheaply as
    // possible, without doing the bearer-token comparison at all. A plugin (rather than a raw
    // pipeline intercept) is the idiomatic Ktor 3.x shape for a global before-routing check.
    install(RequestGuardPlugin)

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

/**
 * Body-size cap and DNS-rebinding guard, both applied before routing/auth. Modeled on the
 * protections the official kotlin-sdk-server added in v0.13.0/v0.14.0 -- not adopted via a version
 * bump because those releases require Kotlin 2.3.21+, and KSP (needed for Room's compiler) has no
 * release for that Kotlin version yet (confirmed live against its Maven metadata and GitHub
 * releases -- latest is 2.3.11), so the protections are reimplemented directly here instead of
 * waiting on that upstream bump.
 */
private val RequestGuardPlugin = createApplicationPlugin(name = "RequestGuard") {
    onCall { call ->
        val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (contentLength != null && contentLength > MAX_REQUEST_BODY_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge, "Request body exceeds $MAX_REQUEST_BODY_BYTES bytes")
            return@onCall
        }

        // DNS-rebinding guard: this server binds every interface (0.0.0.0) on the LAN, so a page
        // in the user's browser could get a DNS name pointed at this device's own address and
        // then have same-origin-policy-exempt script send it a request whose Host header carries
        // that attacker-controlled name instead of an IP or "localhost". Real MCP clients (the
        // Settings screen hands out "<lan-ip>:8642/mcp" or "localhost:8642/mcp" to paste into a
        // client's config) only ever send an IP-literal or "localhost" Host header, so rejecting
        // anything else costs no legitimate traffic. Defense-in-depth on top of the mandatory
        // bearer-token auth below, which already defeats the classic no-credential rebinding
        // attack on its own -- worth keeping in case auth is ever bypassed by a future bug, not
        // because the token requirement alone is insufficient today.
        val host = call.request.header(HttpHeaders.Host)?.substringBeforeLast(':')
        if (host != null && !isAllowedHost(host)) {
            call.respond(HttpStatusCode.Forbidden, "Host not allowed")
        }
    }
}

private val IPV4_LITERAL = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
private val IPV6_LITERAL = Regex("^[0-9a-fA-F:]+$")

// True for "localhost" or any syntactically valid IPv4/IPv6 literal -- false for anything that
// looks like a DNS hostname (the shape a DNS-rebinding attack's Host header takes). Deliberately a
// pure string check, not InetAddress.getByName(): that method only skips DNS for input that's
// already a literal, but for anything else -- i.e. exactly the hostnames this should reject -- it
// would perform a real (slow, blockable) DNS lookup on every request just to prove the input
// wasn't a literal.
private fun isAllowedHost(host: String): Boolean {
    val bracketless = host.removeSurrounding("[", "]")
    return bracketless.equals("localhost", ignoreCase = true) ||
        IPV4_LITERAL.matches(bracketless) ||
        (bracketless.contains(':') && IPV6_LITERAL.matches(bracketless))
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
