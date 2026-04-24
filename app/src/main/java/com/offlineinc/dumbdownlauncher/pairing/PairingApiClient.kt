package com.offlineinc.dumbdownlauncher.pairing

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Result of [PairingApiClient.getStripeProductIds].
 *
 * @param productIds     Raw Stripe product IDs for this phone line.
 * @param hideAudioBundle True if the subscription includes a product that hides
 *                        the audio-bundle upsell (e.g. it's already included).
 */
data class StripeProductResult(
    val productIds: List<String>,
    val hideAudioBundle: Boolean,
    val hideSmartTxt: Boolean,
)

/**
 * Result of [PairingApiClient.getBundleFlags].
 *
 * Mirrors the `/contact-sync/bundle-flags` response: feature flags derived
 * from the Gigs plan tier attached to the flip phone's subscription.
 *
 * @param planId          Gigs plan_id the backend resolved (e.g. "pln_…"), or
 *                        null if the gigs_subscriptions row had an empty
 *                        plan_id. Included mostly for logging/diagnostics.
 * @param tier            "dumb" | "dumber" | "dumbest" | null. Null means the
 *                        plan_id was unrecognised — treat as no flags hidden.
 * @param hideAudioBundle True if the audio-bundle upsell should be hidden.
 *                        Dumber and Dumbest tiers both set this.
 * @param hideSmartTxt    True if the smart-txt / contact-sync flow should be
 *                        hidden entirely. Only the Dumbest tier sets this.
 */
data class BundleFlagsResult(
    val planId: String?,
    val tier: String?,
    val hideAudioBundle: Boolean,
    val hideSmartTxt: Boolean,
)

/**
 * Thrown by [PairingApiClient.getStripeProductIds] and
 * [PairingApiClient.getBundleFlags] when the backend returns HTTP 404 —
 * i.e. the phone number isn't in the relevant table (phone_lines for the
 * Stripe call, gigs_subscriptions for bundle flags). Callers should treat
 * this as a "no subscription data" signal (default both bundle flags to
 * false) rather than a transient failure to keep retrying.
 */
class PhoneNumberNotFoundException(message: String) : IOException(message)

/**
 * Minimal API client for the pairing confirm endpoint.
 * Only used during onboarding to pair the flip phone with the smartphone.
 */
class PairingApiClient(private val httpClient: OkHttpClient) {
    companion object {
        private const val TAG = "PairingAPI"
        private const val BASE_URL = "https://offline-dc-backend-ba4815b2bcc8.herokuapp.com/contact-sync"
        private val JSON_TYPE = "application/json".toMediaType()

        /**
         * Per-call timeout for [getBundleFlags]. The shared OkHttpClient from
         * [DeviceRegistrar] has a 75s callTimeout tuned for /register (which
         * can legitimately take 20–40s against a cold Heroku dyno). Bundle
         * flags is a trivial GET — if the backend hasn't answered in 20s it
         * isn't going to, and we'd rather punt back to the cached flags and
         * move the user off the "checking bundle..." spinner than stall boot.
         */
        private const val BUNDLE_FLAGS_TIMEOUT_SECONDS = 20L
    }

    /**
     * Confirms a 4-digit pairing code and returns the server response
     * containing sharedSecret and pairingId.
     */
    fun confirmPairing(pairingCode: String, flipPhoneNumber: String, flipLauncherVersion: String? = null): JSONObject {
        Log.i(TAG, "confirmPairing: code=$pairingCode, phone=$flipPhoneNumber, version=$flipLauncherVersion")
        val body = JSONObject()
            .put("pairingCode", pairingCode)
            .put("flipPhoneNumber", flipPhoneNumber)
        if (flipLauncherVersion != null) {
            body.put("flipLauncherVersion", flipLauncherVersion)
        }
        val request = Request.Builder()
            .url("$BASE_URL/pairing/confirm")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()

        Log.d(TAG, "HTTP ${request.method} ${request.url}")
        val response = httpClient.newCall(request).execute()
        val bodyStr = response.body?.string() ?: "{}"
        if (!response.isSuccessful) {
            val error = try {
                JSONObject(bodyStr).optString("error", "Request failed")
            } catch (_: Exception) {
                "Request failed: ${response.code}"
            }
            Log.e(TAG, "HTTP ${request.method} ${request.url}: ${response.code} — $error")
            throw IOException(error)
        }
        val result = JSONObject(bodyStr)
        Log.i(TAG, "confirmPairing: success — pairingId=${result.optInt("pairingId")}")
        return result
    }

    /**
     * Reports the current launcher version to the server so the smart phone
     * companion app knows which features are available.
     * Called once after each launcher update.
     */
    fun reportVersion(flipPhoneNumber: String, flipLauncherVersion: String, sharedSecret: String) {
        Log.i(TAG, "reportVersion: phone=$flipPhoneNumber, version=$flipLauncherVersion")
        val body = JSONObject()
            .put("flipPhoneNumber", flipPhoneNumber)
            .put("flipLauncherVersion", flipLauncherVersion)
        val hmac = com.offlineinc.dumbdownlauncher.contactsync.sync.CryptoUtil.hmacSha256Hex(
            body.toString().toByteArray(), sharedSecret
        )
        val request = Request.Builder()
            .url("$BASE_URL/report-version")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .header("X-Auth-HMAC", hmac)
            .build()

        Log.d(TAG, "HTTP ${request.method} ${request.url}")
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.e(TAG, "reportVersion: HTTP ${response.code}")
            throw IOException("Report version failed: ${response.code}")
        }
        Log.i(TAG, "reportVersion: success")
    }

    /**
     * Fetches Stripe product IDs for a given flip phone number.
     * Does not require authentication — safe to call before or after pairing.
     *
     * Returns a [StripeProductResult] containing the raw product IDs and
     * derived flags (e.g. [StripeProductResult.hideAudioBundle]).
     */
    fun getStripeProductIds(flipPhoneNumber: String): StripeProductResult {
        val encoded = java.net.URLEncoder.encode(flipPhoneNumber, "UTF-8")
        val request = Request.Builder()
            .url("$BASE_URL/stripe-product-ids?flipPhoneNumber=$encoded")
            .get()
            .build()

        Log.d(TAG, "HTTP ${request.method} ${request.url}")
        val response = httpClient.newCall(request).execute()
        val bodyStr = response.body?.string() ?: "{}"
        if (response.code == 404) {
            Log.w(TAG, "getStripeProductIds: phone number not found (HTTP 404)")
            throw PhoneNumberNotFoundException("Phone number not found: $flipPhoneNumber")
        }
        if (!response.isSuccessful) {
            Log.e(TAG, "getStripeProductIds: HTTP ${response.code}")
            throw IOException("Request failed: ${response.code}")
        }
        val json = JSONObject(bodyStr)
        val arr = json.optJSONArray("stripeProductIds")
        val ids = if (arr != null) (0 until arr.length()).map { arr.getString(it) } else emptyList()
        val hideAudioBundle = json.optBoolean("hideAudioBundle", false)
        val hideSmartTxt = json.optBoolean("hideSmartTxt", false)
        Log.d(TAG, "getStripeProductIds: ids=$ids hideAudioBundle=$hideAudioBundle hideSmartTxt=$hideSmartTxt")
        return StripeProductResult(productIds = ids, hideAudioBundle = hideAudioBundle, hideSmartTxt = hideSmartTxt)
    }

    /**
     * Fetches the bundle feature flags from the Gigs-plan-backed endpoint.
     *
     * Returns a [BundleFlagsResult] derived from `gigs_subscriptions.plan_id`
     * for this phone number. This is the call that the launcher's
     * "checking bundle..." step in device setup makes, and that
     * [MainActivity] re-fires on every launch after the 30s cold-boot
     * quiet period to keep the flags fresh.
     *
     * Does not require authentication — safe to call before or after pairing.
     *
     * Throws [IOException] on transport errors or non-2xx responses so the
     * caller (which runs off the main thread) can decide whether to retry
     * or fall back to cached flags.
     */
    fun getBundleFlags(flipPhoneNumber: String): BundleFlagsResult {
        val encoded = java.net.URLEncoder.encode(flipPhoneNumber, "UTF-8")
        val request = Request.Builder()
            .url("$BASE_URL/bundle-flags?flipPhoneNumber=$encoded")
            .get()
            .build()

        Log.d(TAG, "HTTP ${request.method} ${request.url}")
        // Override the shared client's 75s callTimeout per call — bundle
        // flags is a trivial GET and we don't want it to stall the boot
        // screen's "checking bundle..." spinner on a misbehaving backend.
        // Call.timeout() takes precedence over the client-level callTimeout.
        val call = httpClient.newCall(request).apply {
            timeout().timeout(BUNDLE_FLAGS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        val response = call.execute()
        val bodyStr = response.body?.string() ?: "{}"
        if (response.code == 404) {
            // Phone number isn't in gigs_subscriptions yet (brand-new
            // activation, Gigs webhook not fired, or no subscription at
            // all). Surfaced as a dedicated exception so the launcher can
            // default both flags to false instead of retrying forever.
            Log.w(TAG, "getBundleFlags: phone number not found (HTTP 404)")
            throw PhoneNumberNotFoundException("Phone number not found: $flipPhoneNumber")
        }
        if (!response.isSuccessful) {
            Log.e(TAG, "getBundleFlags: HTTP ${response.code}")
            throw IOException("Request failed: ${response.code}")
        }
        val json = JSONObject(bodyStr)
        // `planId` and `tier` may be JSON null — optString returns "" in that
        // case, and isNull() lets us distinguish "missing/null" from "empty".
        val planId = if (json.isNull("planId")) null else json.optString("planId").ifEmpty { null }
        val tier = if (json.isNull("tier")) null else json.optString("tier").ifEmpty { null }
        val hideAudioBundle = json.optBoolean("hideAudioBundle", false)
        val hideSmartTxt = json.optBoolean("hideSmartTxt", false)
        Log.d(
            TAG,
            "getBundleFlags: planId=$planId tier=$tier hideAudioBundle=$hideAudioBundle hideSmartTxt=$hideSmartTxt"
        )
        return BundleFlagsResult(
            planId = planId,
            tier = tier,
            hideAudioBundle = hideAudioBundle,
            hideSmartTxt = hideSmartTxt,
        )
    }

    /**
     * Fetches the pairing status from the server for a given flip phone number.
     * Returns the JSON response which includes smartPlatform when paired.
     */
    fun getPairingStatus(flipPhoneNumber: String): JSONObject {
        val encoded = java.net.URLEncoder.encode(flipPhoneNumber, "UTF-8")
        val request = Request.Builder()
            .url("$BASE_URL/pairing/status?flipPhoneNumber=$encoded")
            .get()
            .build()

        Log.d(TAG, "HTTP ${request.method} ${request.url}")
        val response = httpClient.newCall(request).execute()
        val bodyStr = response.body?.string() ?: "{}"
        if (!response.isSuccessful) {
            Log.e(TAG, "getPairingStatus: HTTP ${response.code}")
            throw IOException("Request failed: ${response.code}")
        }
        return JSONObject(bodyStr)
    }
}
