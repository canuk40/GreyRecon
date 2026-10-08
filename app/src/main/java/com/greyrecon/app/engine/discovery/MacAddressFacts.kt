package com.greyrecon.app.engine.discovery

/**
 * Facts readable from a MAC address itself, before any registry lookup.
 *
 * The one that matters in practice is the locally-administered bit. Android has randomised the
 * MAC it presents per network since 10, iOS since 14, and desktop OSes increasingly do the same,
 * so a large share of the phones and laptops on any home network now advertise an address that
 * was generated rather than assigned. Those addresses have no OUI, so [VendorLookup] returns null
 * and the device lands in the scan list as an unidentified blank -- which is the single most
 * common complaint levelled at network scanners generally ("everything shows as generic").
 *
 * The address is not useless though: the bit tells you *why* it is unidentifiable, which is a
 * better answer than silence and is also reassuring rather than alarming. Labelling these
 * correctly is more accurate than Fing, which reports many of them as unknown devices.
 *
 * Bit layout of the first octet (IEEE 802):
 *   bit 0 (0x01) -- multicast/group address
 *   bit 1 (0x02) -- locally administered, i.e. not assigned by the IEEE to a manufacturer
 */
object MacAddressFacts {

    /** True when the address was locally generated (randomised privacy MAC) rather than assigned. */
    fun isLocallyAdministered(macAddress: String?): Boolean {
        val firstOctet = firstOctet(macAddress) ?: return false
        return (firstOctet and 0x02) != 0
    }

    fun isMulticast(macAddress: String?): Boolean {
        val firstOctet = firstOctet(macAddress) ?: return false
        return (firstOctet and 0x01) != 0
    }

    private fun firstOctet(macAddress: String?): Int? {
        val cleaned = macAddress?.replace(":", "")?.replace("-", "")?.trim() ?: return null
        if (cleaned.length < 2) return null
        return cleaned.substring(0, 2).toIntOrNull(16)
    }
}
