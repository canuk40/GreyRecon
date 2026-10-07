package com.greyrecon.app.ui.main

import android.app.Activity
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.play.core.review.ReviewManagerFactory
import com.greyrecon.app.billing.PlayStoreAvailability
import java.util.concurrent.TimeUnit

/**
 * Asks Google Play to show its in-app review sheet after the user has had a real chance to see
 * value, and never more often than Play would show it anyway.
 *
 * Rules:
 *  - only after [MIN_SCANS] scans that actually found devices (an empty scan is not a good moment),
 *  - only once the app has been in use for [MIN_DAYS_SINCE_FIRST] days,
 *  - at most one request per [MIN_DAYS_BETWEEN_REQUESTS] days.
 *
 * Play enforces its own quota and may show nothing; that is expected and not an error. There is
 * deliberately no "do you like the app?" pre-question: Play's policy forbids gating the review
 * prompt on sentiment.
 */
object ReviewPrompter {
    private const val PREFS = "review_prompter"
    private const val KEY_SCANS = "successful_scans"
    private const val KEY_FIRST_SEEN = "first_seen_ms"
    private const val KEY_LAST_REQUEST = "last_request_ms"

    private const val MIN_SCANS = 3
    private const val MIN_DAYS_SINCE_FIRST = 3L
    private const val MIN_DAYS_BETWEEN_REQUESTS = 90L

    fun onScanSucceeded(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val firstSeen = prefs.getLong(KEY_FIRST_SEEN, 0L).takeIf { it != 0L } ?: now
        val scans = prefs.getInt(KEY_SCANS, 0) + 1
        prefs.edit().putLong(KEY_FIRST_SEEN, firstSeen).putInt(KEY_SCANS, scans).apply()

        val lastRequest = prefs.getLong(KEY_LAST_REQUEST, 0L)
        val oldEnough = now - firstSeen >= TimeUnit.DAYS.toMillis(MIN_DAYS_SINCE_FIRST)
        val notAskedRecently = lastRequest == 0L ||
            now - lastRequest >= TimeUnit.DAYS.toMillis(MIN_DAYS_BETWEEN_REQUESTS)
        if (scans < MIN_SCANS || !oldEnough || !notAskedRecently) return
        if (!activity.canHostReviewFlow()) return

        // Fail CLOSED: only touch Play Core if the Play Store is definitely present and enabled.
        // Otherwise launchReviewFlow crashes later inside Play's own HsdpShimActivity
        // ("targetPackageName is null"), where no try/catch of ours can reach it. A network tool
        // like GreyRecon sees plenty of devices with no/disabled Play Store.
        if (!PlayStoreAvailability.isPresentAndEnabled(activity)) return

        prefs.edit().putLong(KEY_LAST_REQUEST, now).apply()
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            // Re-check: requestReviewFlow is a network round trip and the user can background the
            // app while it is in flight. Launching against a non-resumed activity is what Play
            // Core chokes on, so this second check is the one that actually matters.
            if (request.isSuccessful && activity.canHostReviewFlow()) {
                manager.launchReviewFlow(activity, request.result)
            }
        }
    }

    /** Safe to hand this Activity to Play's review flow: not finishing/destroyed, and resumed. */
    private fun Activity.canHostReviewFlow(): Boolean {
        if (isFinishing || isDestroyed) return false
        val lifecycle = (this as? LifecycleOwner)?.lifecycle ?: return true
        return lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
}
