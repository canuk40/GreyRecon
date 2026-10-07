package com.greyrecon.app.ads

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/** AdMob banner unit for this app (AdMob console -> GreyRecon -> Ad units -> "GreyRecon - Banner"). */
private const val BANNER_AD_UNIT_ID = "ca-app-pub-4408909409191600/7873022016"

/**
 * Persistent bottom banner, mirroring ObsidianBox Modern's working setup.
 *
 * Pro-gated by returning early rather than rendering-then-hiding, so a Pro user never costs an
 * ad request. Note [com.greyrecon.app.billing.BillingManager] seeds `isPro` from
 * `BuildConfig.DEBUG`, so this is invisible in debug builds by design - verifying it needs a
 * release build.
 *
 * Placed as a real Compose sibling below the nav content rather than as a native overlay, so it
 * reserves its own layout space: scan results and other scrollable content are never covered and
 * no manual safe-area padding is needed.
 */
@Composable
fun BannerAdBar(isPro: Boolean) {
    if (isPro) return

    val context = LocalContext.current
    val adView = remember {
        AdView(context).apply {
            setAdSize(AdSize.BANNER)
            adUnitId = BANNER_AD_UNIT_ID
        }
    }

    DisposableEffect(Unit) {
        adView.loadAd(AdRequest.Builder().build())
        onDispose { adView.destroy() }
    }

    AndroidView(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        factory = { adView }
    )
}
