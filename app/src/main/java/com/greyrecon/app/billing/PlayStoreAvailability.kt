package com.greyrecon.app.billing

import android.content.Context
import android.content.pm.PackageManager

/**
 * Whether Google Play Store is present and usable enough to host a Play Core UI flow
 * (in-app review, in-app purchase).
 *
 * Both flows launch one of Play's own Activities, and that Activity crashes if it cannot resolve
 * the Play Store package:
 *  - in-app review  -> IllegalStateException "targetPackageName is null" from HsdpShimActivity
 *  - in-app billing -> NullPointerException on PendingIntent.getIntentSender() in ProxyBillingActivity
 * The throw happens on Play's Activity stack, on the main looper, after our code has returned, so no
 * try/catch of ours can intercept it. Not starting the flow is the only defence.
 *
 * GreyRecon is a network-scanning tool, so it attracts technical / de-Googled / rooted devices
 * where Play Store may be absent or disabled -- exactly the population that trips these crashes.
 */
object PlayStoreAvailability {

    const val PLAY_STORE_PACKAGE = "com.android.vending"

    /**
     * FAIL-CLOSED check for OPTIONAL Play Core flows (in-app review): true only when the Play Store
     * is definitely installed AND enabled. Any lookup failure -- including a genuinely absent Store
     * (NameNotFound) -- returns false, so we never hand an optional flow to Play Core when it might
     * fail to resolve the Store and crash. Skipping an optional review prompt costs nothing.
     */
    fun isPresentAndEnabled(context: Context): Boolean {
        val info = try {
            context.packageManager.getApplicationInfo(PLAY_STORE_PACKAGE, 0)
        } catch (e: Exception) {
            return false
        }
        return info.enabled
    }
}
