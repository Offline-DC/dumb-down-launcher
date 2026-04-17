package com.offlineinc.dumbdownlauncher.weather

import android.content.Context

/**
 * Persists whether the user has consented to the weather app accessing
 * their location. If they decline, the weather app goes back to all apps.
 * If they accept, we remember it so they aren't asked again.
 */
object WeatherLocationConsentStore {

    private const val PREFS = "weather_consent"
    private const val KEY_CONSENTED = "location_consented"

    fun hasConsented(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONSENTED, false)
    }

    fun setConsented(context: Context, consented: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONSENTED, consented)
            .apply()
    }
}
