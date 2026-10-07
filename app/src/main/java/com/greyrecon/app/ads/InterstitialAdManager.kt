package com.greyrecon.app.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * Interstitial ads shown on screen transitions, mirroring ObsidianBox Modern's working setup.
 *
 * A plain object rather than ObsidianBox's Hilt `@Singleton` - GreyRecon has no DI - and Pro
 * status is passed in per call instead of being injected, because [com.greyrecon.app.billing.BillingManager]
 * is owned by MainActivity rather than the Application.
 *
 * Frequency-capped deliberately: a full-page ad on every navigation in a utility people open in
 * short bursts would be disruptive, would cost retention GreyRecon can't spare, and runs against
 * AdMob's own ad placement policy. Both caps are tunable.
 */
object InterstitialAdManager {

    private const val TAG = "InterstitialAd"
    private const val AD_UNIT_ID = "ca-app-pub-4408909409191600/9448069970"
    private const val MIN_TRANSITIONS_BETWEEN_ADS = 2
    private const val MIN_INTERVAL_MS = 60_000L

    private var appContext: Context? = null
    private var interstitialAd: InterstitialAd? = null
    private var isLoading = false
    private var lastShownAtMs = 0L
    private var transitionsSinceLastShown = 0

    /**
     * Call once from Application.onCreate(). This is also what initialises the Mobile Ads SDK for
     * the whole app, so the banner in [BannerAdBar] depends on it too.
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        MobileAds.initialize(context.applicationContext)
        loadAd()
    }

    private fun loadAd() {
        val context = appContext ?: return
        if (isLoading || interstitialAd != null) return
        isLoading = true
        InterstitialAd.load(
            context,
            AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    isLoading = false
                    interstitialAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    interstitialAd = null
                    Log.w(TAG, "Interstitial failed to load: ${error.message}")
                }
            }
        )
    }

    /**
     * Call on a screen transition. No-ops for Pro users, before an ad has finished loading, or
     * until both [MIN_TRANSITIONS_BETWEEN_ADS] and [MIN_INTERVAL_MS] have elapsed since the last
     * one shown. The caller is expected to skip the very first transition so an ad never appears
     * on app launch itself.
     */
    fun maybeShowOnTransition(activity: Activity, isPro: Boolean) {
        if (isPro) return

        transitionsSinceLastShown++
        val intervalElapsed = System.currentTimeMillis() - lastShownAtMs >= MIN_INTERVAL_MS
        if (transitionsSinceLastShown < MIN_TRANSITIONS_BETWEEN_ADS || !intervalElapsed) return

        val ad = interstitialAd
        if (ad == null) {
            loadAd()
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAd = null
                loadAd()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                interstitialAd = null
                loadAd()
            }
        }
        ad.show(activity)
        lastShownAtMs = System.currentTimeMillis()
        transitionsSinceLastShown = 0
    }
}
