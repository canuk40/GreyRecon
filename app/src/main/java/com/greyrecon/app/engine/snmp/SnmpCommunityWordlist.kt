package com.greyrecon.app.engine.snmp

import android.content.Context

/**
 * Bundled default/common SNMP community strings -- the real
 * `danielmiessler/SecLists` (MIT) `Discovery/SNMP/common-snmp-community-strings.txt`
 * file, unmodified. 118 real-world entries, not fabricated. Only used by the
 * explicit, on-demand "Try Common Community Strings" pentest action -- never
 * folded into the automatic scan, same reasoning as [SnmpClient.query]'s own
 * single-community-string default: trying 118 strings against every device on
 * a typical scan would be real, unwelcome network noise for a feature most
 * devices won't even answer.
 */
class SnmpCommunityWordlist(private val context: Context) {

    val strings: List<String> by lazy { loadAsset() }

    private fun loadAsset(): List<String> =
        context.assets.open("snmp_community_strings.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
}
