package com.offlineinc.dumbdownlauncher.pairing

import android.content.Context

/**
 * Stores device-link pairing credentials in SharedPreferences.
 * The launcher owns pairing state — contacts-sync reads it via ContentProvider.
 */
class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("device_pairing", Context.MODE_PRIVATE)

    var isPaired: Boolean
        get() = prefs.getBoolean("is_paired", false)
        set(v) = prefs.edit().putBoolean("is_paired", v).apply()

    var sharedSecret: String?
        get() = prefs.getString("shared_secret", null)
        set(v) = prefs.edit().putString("shared_secret", v).apply()

    var pairingId: Int
        get() = prefs.getInt("pairing_id", 0)
        set(v) = prefs.edit().putInt("pairing_id", v).apply()

    var flipPhoneNumber: String?
        get() = prefs.getString("flip_phone_number", null)
        set(v) = prefs.edit().putString("flip_phone_number", v).apply()

    /**
     * True once the device has been successfully registered with the
     * backend (POST /api/v1/register). Registration now runs for every
     * user during onboarding — whether or not they go on to link a smart
     * phone — so this flag lets us skip the registration step on re-entry
     * after it has already completed.
     *
     * Separate from [isPaired]: a device can be registered without being
     * paired (linking=no path), but any paired device is also registered.
     */
    var deviceRegistered: Boolean
        get() = prefs.getBoolean("device_registered", false)
        set(v) = prefs.edit().putBoolean("device_registered", v).apply()

    /**
     * Persist the phone number + mark the device as registered atomically
     * so downstream readers never see registered=true without a phone
     * number available in the store.
     */
    fun saveRegistration(phoneNumber: String) {
        prefs.edit()
            .putString("flip_phone_number", phoneNumber)
            .putBoolean("device_registered", true)
            .commit()
    }

    var lastReportedVersion: String?
        get() = prefs.getString("last_reported_version", null)
        set(v) = prefs.edit().putString("last_reported_version", v).apply()

    /**
     * Stripe product IDs associated with this phone line's subscription.
     * Stored as a comma-separated string; null if not yet fetched or not found.
     */
    var stripeProductIds: List<String>?
        get() {
            val raw = prefs.getString("stripe_product_ids", null) ?: return null
            return if (raw.isEmpty()) emptyList() else raw.split(",")
        }
        set(v) = prefs.edit().putString("stripe_product_ids", v?.joinToString(",")).apply()

    /**
     * True if the backend indicated the audio bundle is already included in the
     * subscription and should not be shown as an upsell.
     * Returns false until the value has been fetched and stored.
     */
    var hideAudioBundle: Boolean
        get() = prefs.getBoolean("hide_audio_bundle", false)
        set(v) = prefs.edit().putBoolean("hide_audio_bundle", v).apply()

    /**
     * True if the backend indicated smart txt is already included in the
     * subscription and should not be shown as an upsell.
     * Returns false until the value has been fetched and stored.
     */
    var hideSmartTxt: Boolean
        get() = prefs.getBoolean("hide_smart_txt", false)
        set(v) = prefs.edit().putBoolean("hide_smart_txt", v).apply()

    /**
     * Beta tester opt-in. Toggled by long-pressing the "updates" entry in
     * AllAppsActivity. When true:
     *
     *   1. [com.offlineinc.dumbdownlauncher.update.UpdateChecker] also
     *      considers prerelease GitHub releases (e.g. `v4.58.0-beta.1`)
     *      when picking the highest-version-code build to offer. The
     *      production release flow continues to publish stable releases
     *      with `prerelease=false`; the beta workflow tags those with
     *      `prerelease=true`, and beta testers see both channels.
     *
     *   2. [com.offlineinc.dumbdownlauncher.update.BetaUpdateReminderWorker]
     *      is scheduled to run once a day, posting a low-importance
     *      "beta tester check-in" notification so the user remembers to
     *      install new beta builds. The check piggy-backs on the existing
     *      update-check infrastructure, so any actual update found is
     *      surfaced with the same high-priority notification stable users
     *      already see — the daily reminder is *additional* to those.
     *
     * Intentionally **not** cleared by [clear] — the opt-in is a long-
     * lived user preference that should survive unpair/relink. The only
     * ways to turn it off are: (a) the user long-presses "updates" again
     * to toggle it off, or (b) `PlatformPreferences.clearAll(...)` runs
     * during the AllAppsActivity "factory reset" long-press on Device
     * Setup, which wipes every device-level pref including this one.
     */
    var betaTesterMode: Boolean
        get() = prefs.getBoolean("beta_tester_mode", false)
        set(v) = prefs.edit().putBoolean("beta_tester_mode", v).apply()

    /**
     * Write all pairing credentials in a single atomic commit so downstream
     * readers never see a partial state (e.g. isPaired=true but secret empty).
     */
    fun savePairing(phoneNumber: String, secret: String, pairingId: Int) {
        prefs.edit()
            .putString("flip_phone_number", phoneNumber)
            .putString("shared_secret", secret)
            .putInt("pairing_id", pairingId)
            .putBoolean("is_paired", true)
            .commit()   // synchronous — guarantees data is persisted before returning
    }

    /**
     * Clear pairing credentials only. The device's phone number
     * ([flipPhoneNumber]) and backend registration state
     * ([deviceRegistered]) are intentionally preserved across unpair so
     * that an unpaired device doesn't have to re-register with the backend
     * — or re-show the "setting up ur phone" onboarding screen — just
     * because the user broke the link to a flip phone.
     */
    fun clear() {
        prefs.edit()
            .remove("is_paired")
            .remove("shared_secret")
            .remove("pairing_id")
            .remove("last_reported_version")
            .remove("stripe_product_ids")
            .remove("hide_audio_bundle")
            .remove("hide_smart_txt")
            .apply()
    }
}
