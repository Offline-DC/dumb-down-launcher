package com.offlineinc.dumbdownlauncher.messenger

/**
 * Callbacks the Google Messages cookie sign-in screen
 * ([GoogleCookieReceiveActivity]) registers with the live Type Sync relay
 * ([com.offlineinc.dumbdownlauncher.launcher.MouseAccessibilityService]) so it
 * can drive the UI as the login arrives and pairing runs.
 *
 *  - [onCookies] login received + Google sign-in started ("waiting" → "signing in")
 *  - [onEmoji]   the UKey2 verification emoji to tap in Google Messages
 *  - [onResult]  terminal (success, message)
 *
 * All are invoked on the main thread.
 */
class GmessagesCookieCallbacks(
    val onCookies: () -> Unit,
    val onEmoji: (String) -> Unit,
    val onResult: (Boolean, String?) -> Unit,
)
