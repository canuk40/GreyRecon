package com.greyrecon.app.billing

import android.app.Activity
import android.content.Context
import com.greyrecon.app.BuildConfig
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real Play Billing integration gating the MCP server as a Pro feature.
 * On-device entitlement only (no backend to do server-side receipt
 * verification against) -- `queryPurchasesAsync` reads Google Play's own
 * locally-cached, signed purchase record rather than a value GreyRecon
 * invented itself, which is the standard approach for apps without their
 * own server. A sufficiently motivated attacker on a rooted device could
 * still defeat this; that tradeoff is what "no backend" means in practice.
 *
 * Debug builds always report Pro (`BuildConfig.DEBUG`) -- the app is nowhere
 * near a Play Store listing, so gating Pro-only features (the MCP server)
 * behind real Play Billing during development just means no one can ever
 * test them without an actual purchase, which doesn't exist yet either.
 * Release builds are untouched and go through real entitlement checks --
 * this can't leak into a build a real user would install.
 */
class BillingManager(context: Context) {

    private val _isPro = MutableStateFlow(BuildConfig.DEBUG)
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            purchases?.forEach { handlePurchase(it) }
        }
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    /** Connects to Play Billing and refreshes entitlement once ready. Safe to call repeatedly (e.g. on every onResume). */
    fun start() {
        if (billingClient.isReady) {
            refreshEntitlement()
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    refreshEntitlement()
                }
            }

            override fun onBillingServiceDisconnected() {
                // Next start() call (e.g. from onResume) reconnects; deliberately not
                // retrying automatically here to avoid a background reconnect loop.
            }
        })
    }

    private fun refreshEntitlement() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            val owned = purchases.any {
                it.products.contains(PRODUCT_ID) && it.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            _isPro.value = owned || BuildConfig.DEBUG
            purchases.forEach { handlePurchase(it) }
        }
    }

    fun launchPurchase(activity: Activity, onFailure: (String) -> Unit) {
        val queryParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        billingClient.queryProductDetailsAsync(queryParams) { result, productDetailsList ->
            val productDetails: ProductDetails? = productDetailsList.firstOrNull()
            if (result.responseCode != BillingClient.BillingResponseCode.OK || productDetails == null) {
                onFailure("GreyRecon Pro isn't available yet -- the in-app product hasn't been configured in Play Console.")
                return@queryProductDetailsAsync
            }

            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .build()
                    )
                )
                .build()

            billingClient.launchBillingFlow(activity, flowParams)
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (!purchase.products.contains(PRODUCT_ID)) return

        _isPro.value = true

        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(params) { /* best-effort; unacknowledged purchases auto-refund after 3 days regardless */ }
        }
    }

    companion object {
        /** Must match the in-app product ID configured in Play Console. */
        const val PRODUCT_ID = "greyrecon_pro_unlock"
    }
}
