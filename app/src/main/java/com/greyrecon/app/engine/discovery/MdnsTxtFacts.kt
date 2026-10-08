package com.greyrecon.app.engine.discovery

/**
 * Pulls a human-readable model/firmware string out of mDNS TXT records.
 *
 * Discovery previously kept only the service *type* ("_googlecast._tcp"), which is enough to
 * classify a device as media-or-TV but tells the user nothing they did not already know by
 * looking at it. The TXT record attached to that same announcement very often carries the actual
 * model and firmware revision, and it is free -- the resolve already happened, the bytes were
 * already in memory, and they were being thrown away.
 *
 * The practical effect is the difference between a row reading "Signify Netherlands B.V." and one
 * reading "Philips hue bridge 2.1". Vendor-from-OUI is the weakest useful signal; this is close to
 * the strongest, because the device is volunteering it.
 *
 * Keys are the conventional ones across the common announcement types -- Google Cast (`md`, `fn`),
 * AirPlay/RAOP (`am`, `model`), HomeKit (`md`), Bonjour printing (`ty`, `product`, `usb_MDL`), and
 * the various firmware spellings (`fv`, `sw`, `srcvers`, `vs`). Unknown keys are ignored rather
 * than guessed at: a TXT record is arbitrary vendor-defined key/value data, and printing whatever
 * happens to be in there would produce noise far more often than insight.
 */
object MdnsTxtFacts {

    private val MODEL_KEYS = listOf("md", "model", "am", "ty", "product", "usb_MDL", "fn", "name")
    private val FIRMWARE_KEYS = listOf("fv", "firmware", "sw", "srcvers", "vs", "rv")

    /**
     * @param attributes raw TXT map as handed over by `NsdServiceInfo.getAttributes()`. Values may
     *   legitimately be null -- a TXT record is allowed to carry a bare key with no value.
     */
    fun describe(attributes: Map<String, ByteArray?>): String? {
        if (attributes.isEmpty()) return null
        val lower = attributes.entries.associate { (k, v) -> k.lowercase() to decode(v) }

        val model = MODEL_KEYS.firstNotNullOfOrNull { key -> lower[key.lowercase()]?.takeIf { it.isNotBlank() } }
        val firmware = FIRMWARE_KEYS.firstNotNullOfOrNull { key -> lower[key.lowercase()]?.takeIf { it.isNotBlank() } }

        return when {
            model != null && firmware != null -> "$model ($firmware)"
            model != null -> model
            // Firmware alone is not worth surfacing -- "1.50.2" with no model attached is noise.
            else -> null
        }
    }

    private fun decode(value: ByteArray?): String? {
        val text = value?.toString(Charsets.UTF_8)?.trim() ?: return null
        if (text.isEmpty() || text.length > MAX_LENGTH) return null
        // TXT values are arbitrary bytes; refuse anything that is not plainly printable rather
        // than rendering control characters into the device list.
        if (text.any { it.isISOControl() }) return null
        return text
    }

    private const val MAX_LENGTH = 64
}
