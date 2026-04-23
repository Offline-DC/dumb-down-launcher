package com.offlineinc.dumbdownlauncher.quack

import android.content.Context

/**
 * Persists whether the user has consented to the quack app accessing
 * their location. If they decline, the quack app goes back to all apps.
 * If they accept, we remember it so they aren't asked again — and the
 * periodic location cache refresh is allowed to run.
 *
 * Mirrors [com.offlineinc.dumbdownlauncher.weather.WeatherLocationConsentStore]:
 * the launcher's periodic location prewarm/refresh is gated on either
 * consent being granted.
 */
object QuackLocationConsentStore {

    private const val PREFS = "quack_consent"
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
