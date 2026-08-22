package com.greyrecon.app.engine.discovery

/**
 * JNI bridge to a native netlink (RTM_GETNEIGH) query of the kernel's
 * ARP/neighbor table -- see `app/src/main/cpp/ipneigh.cpp` for why: reading
 * /proc/net/arp as a file is blocked for regular apps on Android 10+
 * (confirmed via `run-as cat /proc/net/arp` -> "Permission denied"), but a
 * netlink route socket hits a different SELinux permission class and is
 * commonly allowed for ordinary apps, since they're used for legitimate
 * things like connectivity monitoring.
 */
object NativeArpScanner {
    init {
        System.loadLibrary("ipneigh")
    }

    /** Returns raw "ip,mac\n" lines for every complete neighbor-table entry, or an empty string on failure. */
    external fun queryNeighborTable(): String
}
