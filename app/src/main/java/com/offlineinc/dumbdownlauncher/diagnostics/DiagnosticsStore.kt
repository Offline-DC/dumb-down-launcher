package com.offlineinc.dumbdownlauncher.diagnostics

import android.content.Context
import java.util.UUID

/**
 * SharedPreferences-backed state for the diagnostics module: whether the
 * user has opted in and the current capture session id (used to join
 * multiple files and reject partial pulls).
 *
 * Kept independent from PairingStore.betaTesterMode so a single user can be
 * on the beta channel without also collecting diagnostics — and vice versa.
 */
internal class DiagnosticsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(DiagnosticsConfig.PREFS_FILE, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Set once at first opt-in; surfaces in manifest.json as `diagnostics_enabled_since`. */
    var enabledSinceMs: Long
        get() = prefs.getLong(KEY_ENABLED_SINCE_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_ENABLED_SINCE_MS, value).apply()

    /**
     * Current capture session id. Rotated on every "reset session" tap so the
     * support engineer can mark a clean before/after window without rebooting.
     */
    var captureSessionId: String
        get() {
            val existing = prefs.getString(KEY_SESSION_ID, null)
            if (existing != null) return existing
            val fresh = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_SESSION_ID, fresh).apply()
            return fresh
        }
        set(value) = prefs.edit().putString(KEY_SESSION_ID, value).apply()

    /** Bump the session id and return the new value. Called from the "reset" UI button. */
    fun resetSession(): String {
        val fresh = UUID.randomUUID().toString()
        captureSessionId = fresh
        return fresh
    }

    private companion object {
        const val KEY_ENABLED = "diagnostics_enabled"
        const val KEY_ENABLED_SINCE_MS = "diagnostics_enabled_since_ms"
        const val KEY_SESSION_ID = "capture_session_id"
    }
}
