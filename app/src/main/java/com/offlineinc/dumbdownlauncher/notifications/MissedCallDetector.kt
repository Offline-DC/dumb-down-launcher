package com.offlineinc.dumbdownlauncher.notifications

import android.app.Notification
import com.offlineinc.dumbdownlauncher.notifications.model.NotificationItem

/**
 * Detects whether a [NotificationItem] is a missed-call notification.
 *
 * Primary signal is [Notification.CATEGORY_MISSED_CALL] (string
 * "missed_call") — set by the Google dialer on modern devices. Real-
 * device evidence (AOSP `com.android.dialer`, June 2026) shows that
 * dialer leaves category=null on missed calls and puts the literal
 * string "Missed call" in the title (with the caller's name in `text`),
 * so we fall back to a case-insensitive startsWith check, gated on the
 * poster being a known dialer package.
 *
 * We deliberately *don't* check title alone — that would match arbitrary
 * apps shouting about call events (e.g. spam SMS containing the phrase),
 * which would be more confusing than helpful.
 */
internal fun NotificationItem.isMissedCall(): Boolean {
    if (category == Notification.CATEGORY_MISSED_CALL) return true
    if (packageName !in DIALER_PACKAGES) return false
    return title.trim().startsWith("missed call", ignoreCase = true)
}

/**
 * Dialer packages whose notifications we'll fall back to title-matching
 * when [Notification.CATEGORY_MISSED_CALL] isn't set. Keep this list
 * small and conservative — every package here is one we trust to only
 * post call-related notifications.
 */
private val DIALER_PACKAGES = setOf(
    "com.android.server.telecom",
    "com.google.android.dialer",
    "com.samsung.android.incallui",
    "com.samsung.android.dialer",
    "com.android.dialer",
    "com.android.phone",
)

