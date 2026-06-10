package com.offlineinc.dumbdownlauncher.notifications.ui.components

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Formats a notification's event time in the iOS Messages / iOS missed-call
 * style:
 *
 *   • same calendar day  → locale short time   ("9:42 AM")
 *   • yesterday          → "Yesterday"
 *   • within past week   → day of week         ("Monday")
 *   • older              → locale short date   ("5/27/26")
 *
 * minSdk is 24, which predates `java.time` without core-library desugaring,
 * so this is intentionally written against [Calendar] + [SimpleDateFormat]
 * for portability across every API level the launcher supports.
 *
 * The Context is needed so we pick up the user's system 12/24-hour and
 * short-date preferences via [DateFormat.getTimeFormat] /
 * [DateFormat.getDateFormat].
 *
 * @param now Override the reference "now" — used by tests, otherwise leave
 *   as the default of [System.currentTimeMillis].
 */
internal fun formatNotificationTime(
    context: Context,
    time: Long,
    now: Long = System.currentTimeMillis(),
): String {
    val dayDiff = calendarDaysBetween(then = time, now = now)
    return when {
        dayDiff <= 0 -> DateFormat.getTimeFormat(context).format(Date(time))
        dayDiff == 1 -> "Yesterday"
        dayDiff in 2..6 -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(time))
        else -> DateFormat.getDateFormat(context).format(Date(time))
    }
}

/**
 * Returns the number of *calendar* days between [then] and [now], using
 * the device's default time zone. 0 = same calendar day, 1 = yesterday.
 *
 * Calendar-day math (rather than naive `(now - then) / 86_400_000`) is
 * required so a notification posted at 11:55 PM yesterday correctly reads
 * as "Yesterday" at 12:05 AM today — even though only 10 minutes have
 * elapsed in wall-clock terms.
 *
 * Future timestamps (clock skew, OEM dialer posting `when` slightly
 * ahead) clamp to 0 so they render as "today".
 */
private fun calendarDaysBetween(then: Long, now: Long): Int {
    if (then >= now) return 0
    val startOfThen = startOfDay(then)
    val startOfNow = startOfDay(now)
    val diff = TimeUnit.MILLISECONDS.toDays(startOfNow - startOfThen).toInt()
    return if (diff < 0) 0 else diff
}

private fun startOfDay(epochMs: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = epochMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}
