package com.greyrecon.app.engine.tools

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.URL
import java.util.concurrent.TimeUnit

data class PortMapping(
    val protocol: String,
    val externalPort: String,
    val internalClient: String,
    val internalPort: String,
    val description: String,
    val enabled: Boolean,
)

data class UpnpIgdResult(val externalIp: String?, val mappings: List<PortMapping>)

/**
 * Audits the user's own router over UPnP-IGD: what port forwards has UPnP opened, and what's the
 * external IP. This answers a real defensive question none of the other tools do -- "did an app or
 * some malware silently punch a hole through my router's firewall?" -- since any LAN device can ask
 * the router to open an inbound port via UPnP with no authentication.
 *
 * Goes further than [com.greyrecon.app.engine.discovery.UpnpDiscoveryService], which is deliberately
 * SSDP-discovery-only: this is a real UPnP control point. It SSDP-searches for the InternetGatewayDevice,
 * fetches its device description, finds the WANIP/WANPPPConnection service's control URL, then calls
 * GetExternalIPAddress and loops GetGenericPortMappingEntry until the router says there are no more.
 * Lightweight regex tag extraction rather than a full SOAP/XML stack, matching this codebase's bias.
 */
class UpnpIgdClient(private val context: Context) {

    suspend fun audit(): Result<UpnpIgdResult> = withContext(Dispatchers.IO) {
        runCatching {
            val location = discoverIgdLocation() ?: throw IOException("No UPnP InternetGatewayDevice found (router may have UPnP disabled)")
            val descXml = httpGet(location)
            val base = tag(descXml, "URLBase")?.takeIf { it.isNotBlank() } ?: location
            val (serviceType, controlPath) = findWanConnectionService(descXml)
                ?: throw IOException("Router advertises UPnP but exposes no WAN connection service to query")
            val controlUrl = URL(URL(base), controlPath).toString()

            val externalIp = runCatching {
                val resp = soap(controlUrl, serviceType, "GetExternalIPAddress", "")
                tag(resp, "NewExternalIPAddress")
            }.getOrNull()

            val mappings = mutableListOf<PortMapping>()
            for (i in 0 until MAX_MAPPINGS) {
                val resp = try {
                    soap(controlUrl, serviceType, "GetGenericPortMappingEntry", "<NewPortMappingIndex>$i</NewPortMappingIndex>")
                } catch (e: IOException) {
                    break // SpecifiedArrayIndexInvalid (713) once we run past the last mapping -- normal stop
                }
                val ext = tag(resp, "NewExternalPort") ?: break
                mappings.add(
                    PortMapping(
                        protocol = tag(resp, "NewProtocol") ?: "?",
                        externalPort = ext,
                        internalClient = tag(resp, "NewInternalClient") ?: "?",
                        internalPort = tag(resp, "NewInternalPort") ?: "?",
                        description = tag(resp, "NewPortMappingDescription").orEmpty(),
                        enabled = tag(resp, "NewEnabled") == "1",
                    )
                )
            }
            UpnpIgdResult(externalIp, mappings)
        }
    }

    /** SSDP M-SEARCH specifically for the IGD, returning its device-description LOCATION URL. */
    private fun discoverIgdLocation(): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifiManager.createMulticastLock("greyrecon-igd")
        lock.acquire()
        try {
            MulticastSocket().use { socket ->
                socket.soTimeout = SSDP_WINDOW_MS
                val group = InetAddress.getByName("239.255.255.250")
                val search = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n"
                    ).toByteArray()
                socket.send(DatagramPacket(search, search.size, group, 1900))

                val deadline = System.currentTimeMillis() + SSDP_WINDOW_MS
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        val loc = text.lineSequence()
                            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                            ?.substringAfter(":", "")?.trim()
                        if (!loc.isNullOrBlank()) return loc
                    } catch (_: java.net.SocketTimeoutException) {
                        break
                    }
                }
            }
        } finally {
            lock.release()
        }
        return null
    }

    /** Finds the WANIPConnection (or WANPPPConnection) service in the device-description XML: its serviceType + controlURL. */
    private fun findWanConnectionService(xml: String): Pair<String, String>? {
        val serviceBlocks = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
        for (match in serviceBlocks) {
            val block = match.groupValues[1]
            val type = tag(block, "serviceType") ?: continue
            if (type.contains("WANIPConnection") || type.contains("WANPPPConnection")) {
                val control = tag(block, "controlURL") ?: continue
                return type to control
            }
        }
        return null
    }

    private fun soap(controlUrl: String, serviceType: String, action: String, innerXml: String): String {
        val body = (
            "<?xml version=\"1.0\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body><u:$action xmlns:u=\"$serviceType\">$innerXml</u:$action></s:Body></s:Envelope>"
            ).toRequestBody("text/xml; charset=\"utf-8\"".toMediaType())

        val request = Request.Builder()
            .url(controlUrl)
            .addHeader("SOAPAction", "\"$serviceType#$action\"")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            // A SOAP fault (e.g. 713 SpecifiedArrayIndexInvalid) comes back as HTTP 500 -- treat as end-of-list.
            if (!response.isSuccessful) throw IOException("SOAP $action -> HTTP ${response.code}")
            return text
        }
    }

    private fun httpGet(url: String): String {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Fetching UPnP device description failed: HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("Empty device description")
        }
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<(?:[\\w-]+:)?$name>(.*?)</(?:[\\w-]+:)?$name>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()

    companion object {
        private const val SSDP_WINDOW_MS = 3_000
        private const val MAX_MAPPINGS = 60
        private val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
