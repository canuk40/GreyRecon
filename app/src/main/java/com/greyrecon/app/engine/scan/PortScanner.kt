package com.greyrecon.app.engine.scan

import com.greyrecon.app.engine.model.Port
import kotlinx.coroutines.flow.Flow

/** Scans a single host for open ports. Implementation TBD (raw sockets vs. native helper). */
interface PortScanner {
    fun scan(ipAddress: String, ports: IntRange = 1..1024): Flow<Port>
}
