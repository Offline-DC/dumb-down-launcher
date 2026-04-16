package com.offlineinc.dumbdownlauncher.quack

import android.content.Context
import android.util.Log

/**
 * Persists the last successfully acquired location across app restarts.
 *
 * The system's getLastKnownLocation() is unreliable on MediaTek flip phones —
 * it often returns null when no other app has recently requested location.
 * By saving our own copy we guarantee that after first successful use,
 * location is available instantly on every subsequent open.
 */
object QuackLocationStore {

    private const val TAG = "QuackLocationStore"
    private const val PREFS = "quack_location"
    private const val KEY_LAT  = "lat_bits"
    private const val KEY_LNG  = "lng_bits"
    private const val KEY_TIME = "saved_at"

    /** How old our persisted location can be before we treat it as stale. */
    const val FRESH_MAX_AGE_MS  = 2 * 60 * 60 * 1000L       // 2 hours — deliver instantly
    const val STALE_MAX_AGE_MS  = 7 * 24 * 60 * 60 * 1000L  // 7 days — use as hard-timeout fallback

    data class StoredLocation(val lat: Double, val lng: Double, val savedAt: Long) {
        val ageMs: Long get() = System.currentTimeMillis() - savedAt
        val ageMinutes: Long get() = ageMs / 60_000
    }

    fun save(context: Context, lat: Double, lng: Double) {
        // commit() instead of apply(): save() is always called from a
        // background thread (BeaconDB, GPS listener, prewarm). Using commit()
        // writes synchronously here and avoids queuing work that Android
        // flushes on the main thread during Activity.onStop(), which on slow
        // eMMC contributes to ANRs.
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAT,  java.lang.Double.doubleToRawLongBits(lat))
            .putLong(KEY_LNG,  java.lang.Double.doubleToRawLongBits(lng))
            .putLong(KEY_TIME, System.currentTimeMillis())
            .commit()
        Log.d(TAG, "Saved location lat=$lat lng=$lng")
    }

    /** Clear the persisted location so the next request() forces a fresh fix. */
    fun invalidate(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        Log.d(TAG, "Invalidated persisted location")
    }

    fun load(context: Context): StoredLocation? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LAT)) return null
        val lat = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAT, 0))
        val lng = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LNG, 0))
        val savedAt = prefs.getLong(KEY_TIME, 0)
        return StoredLocation(lat, lng, savedAt)
    }
}
