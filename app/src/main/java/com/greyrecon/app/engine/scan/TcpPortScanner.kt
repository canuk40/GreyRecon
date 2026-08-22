package com.greyrecon.app.engine.scan

import com.greyrecon.app.engine.model.Port
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** Same TCP-connect approach as ActiveScanDiscoveryService (no ICMP -- unprivileged Android apps can't use raw sockets). */
class TcpPortScanner(
    private val portServiceLookup: PortServiceLookup,
    private val maxConcurrent: Int = 32,
    private val connectTimeoutMs: Int = 400,
) : PortScanner {

    override fun scan(ipAddress: String, ports: IntRange): Flow<Port> = channelFlow {
        val semaphore = Semaphore(maxConcurrent)

        coroutineScope {
            ports.forEach { portNumber ->
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (isOpen(ipAddress, portNumber)) {
                            send(
                                Port(
                                    number = portNumber,
                                    protocol = "tcp",
                                    serviceName = portServiceLookup.lookup(portNumber, "tcp"),
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun isOpen(ip: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), connectTimeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
