package com.greyrecon.app.engine.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.Ndef

data class NdefRecordInfo(val type: String, val text: String)

data class NfcTagInfo(
    val uid: String,
    val technologies: List<String>,
    val ndefRecords: List<NdefRecordInfo>,
    /** Non-null only if a MifareClassic sector-0 read with the well-known default key succeeded. */
    val mifareClassicSector0Dump: List<String>?,
)

/**
 * Reads whatever a discovered [Tag] will actually give up via Android's native `android.nfc` APIs
 * -- no third-party dependency needed for the common cases (NDEF text/URI records cover most
 * real-world tags: transit info, business-card-style tags, etc.).
 *
 * Deliberately does NOT attempt Mifare Classic key recovery/cracking (nested/hardnested/darkside
 * attacks) or any form of tag emulation/cloning -- both are genuine Android platform walls, not
 * missing effort: Android's NFC controller doesn't expose the raw-frame timing control those
 * attacks need, and HCE (Host Card Emulation) only supports ISO 14443-4/APDU-based emulation, which
 * Mifare Classic isn't. What this *can* do safely: read a Mifare Classic tag's sector 0 if (and only
 * if) it still uses the factory-default key (0xFFFFFFFFFFFF) -- a legitimate, common case (many
 * tags in the wild are never rekeyed), attempted read-only and non-destructively.
 */
object NfcTagReader {

    fun read(tag: Tag): NfcTagInfo {
        val uid = tag.id.joinToString(":") { "%02X".format(it) }
        val technologies = tag.techList.map { it.substringAfterLast('.') }

        val ndefRecords = readNdef(tag)
        val mifareDump = if (MifareClassic::class.java.name in tag.techList) readMifareClassicSector0(tag) else null

        return NfcTagInfo(
            uid = uid,
            technologies = technologies,
            ndefRecords = ndefRecords,
            mifareClassicSector0Dump = mifareDump,
        )
    }

    private fun readNdef(tag: Tag): List<NdefRecordInfo> {
        val ndef = Ndef.get(tag) ?: return emptyList()
        return try {
            ndef.connect()
            val message: NdefMessage = ndef.cachedNdefMessage ?: ndef.ndefMessage ?: return emptyList()
            message.records.mapNotNull { record -> parseRecord(record) }
        } catch (e: Exception) {
            emptyList()
        } finally {
            try { ndef.close() } catch (e: Exception) { /* already disconnected -- nothing to clean up */ }
        }
    }

    private fun parseRecord(record: NdefRecord): NdefRecordInfo? {
        if (record.tnf != NdefRecord.TNF_WELL_KNOWN) {
            return NdefRecordInfo("raw", record.payload.joinToString("") { "%02x".format(it) })
        }
        return when {
            record.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                // Text record layout: [status byte][language code][UTF-8 or UTF-16 text].
                // Bit 6 of the status byte selects the encoding; the low 6 bits are the language-code length.
                val payload = record.payload
                if (payload.isEmpty()) return null
                val statusByte = payload[0].toInt()
                val languageCodeLength = statusByte and 0x3F
                val isUtf16 = (statusByte and 0x80) != 0
                val charset = if (isUtf16) Charsets.UTF_16 else Charsets.UTF_8
                val text = String(payload, 1 + languageCodeLength, payload.size - 1 - languageCodeLength, charset)
                NdefRecordInfo("text", text)
            }
            record.type.contentEquals(NdefRecord.RTD_URI) -> {
                NdefRecordInfo("uri", record.toUri()?.toString() ?: "")
            }
            else -> NdefRecordInfo("well-known", record.payload.joinToString("") { "%02x".format(it) })
        }
    }

    private fun readMifareClassicSector0(tag: Tag): List<String>? {
        val mifare = MifareClassic.get(tag) ?: return null
        return try {
            mifare.connect()
            if (!mifare.authenticateSectorWithKeyA(0, MifareClassic.KEY_DEFAULT)) return null
            val firstBlock = mifare.sectorToBlock(0)
            val blockCount = mifare.getBlockCountInSector(0)
            (0 until blockCount).map { i ->
                mifare.readBlock(firstBlock + i).joinToString(" ") { "%02X".format(it) }
            }
        } catch (e: Exception) {
            null // wrong key, tag moved away mid-read, or an unsupported variant -- not an error to surface
        } finally {
            try { mifare.close() } catch (e: Exception) { /* already disconnected -- nothing to clean up */ }
        }
    }
}
