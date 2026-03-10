package com.offlineinc.dumbdownlauncher.coverdisplay

import android.app.Notification
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.offlineinc.dumbdownlauncher.notifications.model.NotificationItem
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Shared colors ─────────────────────────────────────────────────────────────

internal val Black  = DumbTheme.Colors.Black
internal val White  = DumbTheme.Colors.White
internal val Yellow = DumbTheme.Colors.Yellow
internal val Gray   = DumbTheme.Colors.Gray

// ── Call / message detection ──────────────────────────────────────────────────

// Primary: match by standard Android notification category (works on all dialers).
// Fallback package list covers edge cases where category isn't set.
internal fun NotificationItem.isCall(): Boolean =
    category == Notification.CATEGORY_CALL || packageName in CALL_PACKAGES

internal fun NotificationItem.isMessage(): Boolean =
    category == Notification.CATEGORY_MESSAGE ||
    category == Notification.CATEGORY_SOCIAL ||  // DMs from social apps (Instagram, Twitter, etc.)
    packageName in MSG_PACKAGES

internal val CALL_PACKAGES = setOf(
    "com.android.server.telecom",
    "com.google.android.dialer",
    "com.samsung.android.incallui",
    "com.android.phone",
)

internal val MSG_PACKAGES = setOf(
    "com.google.android.apps.messaging",
    "com.android.mms",
    "com.samsung.android.messaging",
    "com.whatsapp",
    "com.whatsapp.w4b",
    "com.facebook.orca",
    "org.telegram.messenger",
    "org.telegram.plus",
    "com.discord",
    "com.snapchat.android",
    "com.instagram.android",
    "com.twitter.android",
    "com.android.chrome",          // Google Messages for Web / web push via Chrome
)

// ── Overlay state ─────────────────────────────────────────────────────────────

internal enum class OverlayKind { CALL, MESSAGE }

internal data class OverlayState(
    val kind:  OverlayKind,
    val line1: String,        // "incoming call" or "new message"
    val line2: String,        // caller name / number
    val line3: String = "",   // location (e.g. "Washington, DC"), calls only
)

// ── Call title parsing ────────────────────────────────────────────────────────

/**
 * Splits a dialer notification title into (number, location).
 * e.g. "+15551234567 Washington, DC" → ("+15551234567", "Washington, DC")
 * Matches a phone-number prefix (digits/+/-/parens) then location starting
 * with an uppercase letter. Returns (title, "") if no location detected.
 */
internal fun parseCallTitle(title: String): Pair<String, String> {
    val match = Regex("""^([\+\d][\d\s\-\(\)\.]*?)\s+([A-Z].+)$""").find(title.trim())
    return if (match != null)
        match.groupValues[1].trim() to match.groupValues[2].trim()
    else
        title.trim() to ""
}

// ── Contact lookup ────────────────────────────────────────────────────────────

/**
 * Looks up a display name for [number] via ContactsContract.
 * Returns null if not found or permission is denied — caller should fall back
 * to whatever the notification already has in its title.
 */
internal suspend fun lookupContactName(context: Context, number: String): String? =
    withContext(Dispatchers.IO) {
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number),
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst())
                    cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                else null
            }
        } catch (_: Exception) {
            null
        }
    }
