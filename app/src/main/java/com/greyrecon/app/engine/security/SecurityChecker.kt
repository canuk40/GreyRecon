package com.greyrecon.app.engine.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class TlsInfo(
    val issuer: String,
    val subject: String,
    val notAfter: String,
    val expired: Boolean,
    val selfSigned: Boolean,
    val sanEntries: List<String>,
    val protocol: String?,
    val cipherSuite: String?,
    val weakCipherOrProtocol: Boolean,
)

data class SecurityCheckResult(
    val url: String,
    val statusCode: Int,
    val presentHeaders: Map<String, String>,
    val missingHeaders: List<String>,
    val tls: TlsInfo?,
    val detectedTech: List<String> = emptyList(),
)

/**
 * Checks a device's HTTP security headers and, over HTTPS, its presented TLS
 * certificate -- deliberately connects with certificate/hostname verification
 * DISABLED so a self-signed, expired, or IP-mismatched cert (extremely common
 * on LAN devices: routers, cameras, IoT admin panels) can still be inspected
 * and reported on, rather than the connection just failing outright. This is
 * the same reason `openssl s_client` and browser dev tools don't refuse to
 * show you an invalid certificate. This trust-everything client exists ONLY
 * for this inspection purpose and must never be reused for any other network
 * call in the app.
 */
object SecurityChecker {

    private val RELEVANT_HEADERS = listOf(
        "Strict-Transport-Security", "Content-Security-Policy", "X-Frame-Options",
        "X-Content-Type-Options", "Referrer-Policy", "Permissions-Policy", "X-XSS-Protection",
        // Cross-origin isolation headers -- confirmed missing from the original 7-header list via
        // GitHub research (andrewlock/NetEscapades.AspNetCore.SecurityHeaders, MIT) against real
        // security-scanner header checklists.
        "Cross-Origin-Opener-Policy", "Cross-Origin-Embedder-Policy", "Cross-Origin-Resource-Policy",
    )

    // Cipher suite substrings and TLS protocol versions with known, unambiguous real-world breaks
    // (RC4 biases, 3DES/DES's 64-bit block size, NULL/EXPORT-grade ciphers, MD5 collisions) --
    // deliberately NOT flagging CBC-mode alone, since many still-acceptable TLS 1.2 suites use it
    // and over-flagging would make the signal noisy rather than trustworthy.
    private val WEAK_CIPHER_SUBSTRINGS = listOf("RC4", "3DES", "_DES_", "NULL", "EXPORT", "_MD5")
    private val WEAK_PROTOCOLS = setOf("SSLv3", "SSLv2", "TLSv1", "TLSv1.1")

    // Web-tech/WAF fingerprinting -- deliberately shallow (header substrings + a handful of body
    // markers) rather than a full signature-database library (e.g. Wappalyzer's thousands of
    // regex rules): this reuses the response [tryUrl] already fetches for the header check, so it's
    // a same-request addition, not a new subsystem. (server-header value, substring to match) pairs
    // and (header name, label) pairs are all standard, widely-documented identification signals.
    private val SERVER_HEADER_SIGNATURES = listOf(
        "cloudflare" to "Cloudflare (CDN/WAF)", "nginx" to "nginx", "apache" to "Apache",
        "microsoft-iis" to "IIS", "lighttpd" to "lighttpd", "caddy" to "Caddy",
    )
    private val PRESENCE_HEADER_SIGNATURES = listOf(
        "cf-ray" to "Cloudflare", "x-sucuri-id" to "Sucuri WAF", "x-akamai-transformed" to "Akamai",
        "x-varnish" to "Varnish", "x-drupal-cache" to "Drupal",
    )
    private val BODY_SIGNATURES = listOf(
        "wp-content" to "WordPress", "wp-includes" to "WordPress",
        "Joomla!" to "Joomla", "/media/jui/" to "Joomla",
        "Drupal.settings" to "Drupal", "data-drupal-selector" to "Drupal",
    )

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    // OkHttp's Response.handshake goes through an Android-specific certificate-chain-cleaning
    // step (reflection against the platform's real TrustManager) before exposing peerCertificates.
    // With a custom trust-all X509TrustManager that cleaning step can't recognize the manager and
    // silently yields an empty list -- confirmed live (real TLS 1.2/1.3 handshakes to cloudflare.com
    // and github.com both returned handshake.peerCertificates=[] despite a fully successful
    // connection). HostnameVerifier.verify() gets the raw, unfiltered SSLSession mid-handshake,
    // before that cleaning runs, so we capture the real chain there instead.
    private data class CapturedHandshake(
        val certificates: Array<out java.security.cert.Certificate>?,
        val protocol: String?,
        val cipherSuite: String?,
    )

    private val capturedHandshake = ThreadLocal<CapturedHandshake?>()

    private val inspectionClient: OkHttpClient by lazy {
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier(HostnameVerifier { _: String, session: SSLSession ->
                capturedHandshake.set(
                    CapturedHandshake(
                        certificates = runCatching { session.peerCertificates }.getOrNull(),
                        protocol = runCatching { session.protocol }.getOrNull(),
                        cipherSuite = runCatching { session.cipherSuite }.getOrNull(),
                    )
                )
                true
            })
            .build()
    }

    suspend fun check(ipAddress: String): Result<SecurityCheckResult> = withContext(Dispatchers.IO) {
        runCatching {
            val result = tryUrl("https://$ipAddress") ?: tryUrl("http://$ipAddress")
                ?: throw IOException("No HTTP(S) service responded on $ipAddress")
            result
        }
    }

    private fun tryUrl(url: String): SecurityCheckResult? {
        return try {
            inspectionClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val present = mutableMapOf<String, String>()
                val missing = mutableListOf<String>()
                RELEVANT_HEADERS.forEach { name ->
                    val value = response.header(name)
                    if (value != null) present[name] = value else missing.add(name)
                }

                val bodyBytes = response.body?.bytes() // drain so the connection can be reused cleanly
                val tech = detectTech(response, bodyBytes)
                val handshake = capturedHandshake.get()
                capturedHandshake.remove()
                val cert = handshake?.certificates?.firstOrNull() as? X509Certificate
                val tls = cert?.let {
                    TlsInfo(
                        issuer = it.issuerX500Principal.name,
                        subject = it.subjectX500Principal.name,
                        notAfter = dateFormat.format(it.notAfter),
                        expired = it.notAfter.before(Date()),
                        selfSigned = it.issuerX500Principal == it.subjectX500Principal,
                        sanEntries = runCatching {
                            it.subjectAlternativeNames?.mapNotNull { entry -> entry.getOrNull(1)?.toString() } ?: emptyList()
                        }.getOrDefault(emptyList()),
                        protocol = handshake.protocol,
                        cipherSuite = handshake.cipherSuite,
                        weakCipherOrProtocol = isWeak(handshake.protocol, handshake.cipherSuite),
                    )
                }

                SecurityCheckResult(url, response.code, present, missing, tls, tech)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isWeak(protocol: String?, cipherSuite: String?): Boolean {
        if (protocol != null && protocol in WEAK_PROTOCOLS) return true
        if (cipherSuite != null && WEAK_CIPHER_SUBSTRINGS.any { cipherSuite.contains(it, ignoreCase = true) }) return true
        return false
    }

    private fun detectTech(response: okhttp3.Response, bodyBytes: ByteArray?): List<String> {
        val found = LinkedHashSet<String>()

        response.header("Server")?.let { server ->
            SERVER_HEADER_SIGNATURES.forEach { (needle, label) ->
                if (server.contains(needle, ignoreCase = true)) found += label
            }
        }
        response.header("X-Powered-By")?.let { found += "X-Powered-By: $it" }
        PRESENCE_HEADER_SIGNATURES.forEach { (header, label) ->
            if (response.header(header) != null) found += label
        }

        // Capped and best-effort: a device's homepage HTML rarely exceeds a few hundred KB, but
        // this is untrusted response data, so bound the scan rather than trust the size implied
        // by any header.
        val bodyText = bodyBytes?.let { runCatching { String(it, 0, minOf(it.size, 65_536), Charsets.UTF_8) }.getOrNull() }
        if (bodyText != null) {
            BODY_SIGNATURES.forEach { (needle, label) ->
                if (bodyText.contains(needle)) found += label
            }
        }

        return found.toList()
    }
}
