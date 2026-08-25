package com.greyrecon.app.engine.nfc

import android.nfc.Tag

/**
 * Bridges NFC tag discovery (delivered to [com.greyrecon.app.ui.main.MainActivity.onNewIntent], the
 * only place Android's foreground-dispatch API can deliver it) to whichever Compose screen currently
 * cares -- [com.greyrecon.app.ui.tools.NfcInspectorScreen] registers itself here while visible and
 * clears it on dispose, same lightweight object-with-a-callback shape already used elsewhere in this
 * app for cross-layer bridges (see `AgentBridgeServer`/`PkgFetchServer`).
 */
object NfcTagBus {
    var onTagDiscovered: ((Tag) -> Unit)? = null
}
