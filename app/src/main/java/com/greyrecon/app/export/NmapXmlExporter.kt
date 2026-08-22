package com.greyrecon.app.export

import android.util.Xml
import com.greyrecon.app.engine.model.Device
import org.xmlpull.v1.XmlSerializer
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Renders a scan as real Nmap XML (the `<nmaprun>` schema), not a bespoke format -- so scan
 * results can be fed directly into `msfconsole`'s `db_import`, Zenmap, or any other tool built
 * around Nmap's output rather than dead-ending in a GreyRecon-only CSV/JSON.
 *
 * Deliberately omits the `<os>` block Nmap XML supports. Real OS fingerprinting needs active
 * TCP/IP stack probing (raw packet crafting) GreyRecon fundamentally can't do as an unprivileged
 * Android app. Downstream tools like Metasploit can auto-select exploits based on OS data --
 * emitting a fabricated or guessed OS block wouldn't just be cosmetically wrong, it could actively
 * mislead a real engagement. Leaving the block out entirely is the honest choice.
 */
object NmapXmlExporter {

    private val timestampFormat = SimpleDateFormat("EEE MMM d HH:mm:ss yyyy", Locale.US)

    fun toNmapXml(devices: List<Device>): String {
        val writer = StringWriter()
        val serializer: XmlSerializer = Xml.newSerializer()
        serializer.setOutput(writer)
        serializer.startDocument("UTF-8", true)

        val nowSeconds = System.currentTimeMillis() / 1000
        val nowStr = timestampFormat.format(System.currentTimeMillis())

        serializer.startTag(null, "nmaprun")
        // scanner="nmap" is a deliberate compatibility shim, not a false claim about GreyRecon's
        // origin -- Metasploit's db_import and other Nmap-XML consumers gate parsing on this
        // attribute, so a real, honest hostname/port/vendor export would otherwise be silently
        // rejected by the exact tools this format exists to feed.
        serializer.attribute(null, "scanner", "nmap")
        serializer.attribute(null, "args", "greyrecon-scan")
        serializer.attribute(null, "start", nowSeconds.toString())
        serializer.attribute(null, "startstr", nowStr)
        serializer.attribute(null, "version", "7.94")
        serializer.attribute(null, "xmloutputversion", "1.05")

        serializer.startTag(null, "scaninfo")
        serializer.attribute(null, "type", "connect")
        serializer.attribute(null, "protocol", "tcp")
        serializer.attribute(null, "numservices", devices.sumOf { it.openPorts.size }.toString())
        serializer.attribute(null, "services", "")
        serializer.endTag(null, "scaninfo")

        devices.forEach { device -> writeHost(serializer, device, nowSeconds) }

        serializer.startTag(null, "runstats")
        serializer.startTag(null, "finished")
        serializer.attribute(null, "time", nowSeconds.toString())
        serializer.attribute(null, "timestr", nowStr)
        serializer.attribute(null, "elapsed", "0.00")
        serializer.attribute(null, "summary", "GreyRecon scan; Hosts: ${devices.size} (${devices.size} up)")
        serializer.attribute(null, "exit", "success")
        serializer.endTag(null, "finished")
        serializer.startTag(null, "hosts")
        serializer.attribute(null, "up", devices.size.toString())
        serializer.attribute(null, "down", "0")
        serializer.attribute(null, "total", devices.size.toString())
        serializer.endTag(null, "hosts")
        serializer.endTag(null, "runstats")

        serializer.endTag(null, "nmaprun")
        serializer.endDocument()
        return writer.toString()
    }

    private fun writeHost(serializer: XmlSerializer, device: Device, nowSeconds: Long) {
        serializer.startTag(null, "host")
        serializer.attribute(null, "starttime", nowSeconds.toString())
        serializer.attribute(null, "endtime", nowSeconds.toString())

        serializer.startTag(null, "status")
        serializer.attribute(null, "state", "up")
        serializer.attribute(null, "reason", if (device.macAddress != null) "arp-response" else "echo-reply")
        serializer.attribute(null, "reason_ttl", "0")
        serializer.endTag(null, "status")

        serializer.startTag(null, "address")
        serializer.attribute(null, "addr", device.ipAddress)
        serializer.attribute(null, "addrtype", "ipv4")
        serializer.endTag(null, "address")

        device.macAddress?.let { mac ->
            serializer.startTag(null, "address")
            serializer.attribute(null, "addr", mac)
            serializer.attribute(null, "addrtype", "mac")
            device.vendor?.let { serializer.attribute(null, "vendor", it) }
            serializer.endTag(null, "address")
        }

        serializer.startTag(null, "hostnames")
        device.hostname?.let { name ->
            serializer.startTag(null, "hostname")
            serializer.attribute(null, "name", name)
            serializer.attribute(null, "type", "PTR")
            serializer.endTag(null, "hostname")
        }
        serializer.endTag(null, "hostnames")

        serializer.startTag(null, "ports")
        device.openPorts.forEach { port ->
            serializer.startTag(null, "port")
            serializer.attribute(null, "protocol", port.protocol)
            serializer.attribute(null, "portid", port.number.toString())
            serializer.startTag(null, "state")
            serializer.attribute(null, "state", "open")
            serializer.attribute(null, "reason", "syn-ack")
            serializer.attribute(null, "reason_ttl", "0")
            serializer.endTag(null, "state")
            port.serviceName?.let { name ->
                serializer.startTag(null, "service")
                serializer.attribute(null, "name", name)
                serializer.attribute(null, "method", "table")
                serializer.attribute(null, "conf", "3")
                serializer.endTag(null, "service")
            }
            serializer.endTag(null, "port")
        }
        serializer.endTag(null, "ports")

        serializer.endTag(null, "host")
    }
}
