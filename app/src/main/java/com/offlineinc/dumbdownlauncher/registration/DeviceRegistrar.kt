package com.offlineinc.dumbdownlauncher.registration

import android.content.Context
import android.util.Log
import com.offlineinc.dumbdownlauncher.launcher.NetworkUtils
import com.offlineinc.dumbdownlauncher.launcher.PhoneNumberReader
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background port of `device_registration.sh` from dumb-phone-configuration.
 *
 * Responsibilities:
 *   1. Wait (indefinitely, in the background) for a SIM to be inserted AND
 *      for the device to have network connectivity.
 *   2. The first time the {IMEI, ICCID, phone number} triple is seen,
 *      PUT it to the Offline API exactly like the shell script does:
 *           PUT /api/v1/phones/{imei}       body: { sim_number, printBarcode, isHacked, software_version }
 *           PUT /api/v1/sims/{iccid}        body: { status: "Active" }
 *           PUT /api/v1/phonelines/{phone}  body: { sim_number }
 *   3. On every subsequent boot, verify the phone number hasn't changed.
 *      If it has (e.g. the user swapped the SIM) the phoneline mapping is
 *      re-sent so the server stays in sync.
 *
 * Safe to call from any thread — the heavy lifting is dispatched to a
 * worker thread internally. Multiple invocations are coalesced into one.
 */
object DeviceRegistrar {

    private const val TAG = "DeviceRegistrar"
    private const val PREFS = "device_registration"
    private const val KEY_IMEI = "last_imei"
    private const val KEY_ICCID = "last_iccid"
    private const val KEY_PHONE = "last_phone_number"
    private const val KEY_REGISTERED_AT = "registered_at_ms"

    // Mirrors OFFLINE_API in dumb-phone-configuration/.env
    private const val API_BASE =
        "https://offline-dc-backend-ba4815b2bcc8.herokuapp.com/api/v1"

    // Hardcoded software version string the backend expects for this device.
    // Not tied to SOFTWARE_VERSION on purpose — the backend matches on
    // this specific value.
    private const val SOFTWARE_VERSION = "0.4.0"

    // How long to sleep between SIM polls while waiting. The launcher is the
    // HOME process and stays alive, so a 10-second tick is cheap.
    private val POLL_INTERVAL_MS = TimeUnit.SECONDS.toMillis(10)

    // Cold-boot quiet period. Delays the first SIM/root probe so the modem
    // can finish its initialization without us hammering telephony APIs and
    // root shell calls through the Magisk daemon. On an already-warm process
    // (not-a-boot restart) this is wasted time, but registration is a
    // once-per-SIM-change background task so there's no visible cost.
    private val COLD_BOOT_QUIET_PERIOD_MS = TimeUnit.SECONDS.toMillis(30)

    private val JSON_TYPE = "application/json".toMediaType()
    private val inFlight = AtomicBoolean(false)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Kicks off registration on a background thread if not already running. */
    fun scheduleOnBoot(context: Context) {
        if (!inFlight.compareAndSet(false, true)) {
            Log.d(TAG, "scheduleOnBoot: already in progress — skipping")
            return
        }
        val appCtx = context.applicationContext
        Thread({
            try {
                runBlocking(appCtx)
            } catch (e: Exception) {
                Log.e(TAG, "Registration thread crashed", e)
            } finally {
                inFlight.set(false)
            }
        }, "DeviceRegistrar").start()
    }

    // ---------------------------------------------------------------------
    // Main loop
    // ---------------------------------------------------------------------

    private fun runBlocking(ctx: Context) {
        // 0. Cold-boot quiet period. The launcher's onCreate fans out several
        //    threads that all shell to root (swap setup, location grants,
        //    OpenBubbles file, migrations). Kicking off SIM reads + HTTP
        //    registration at the same time saturates the Magisk daemon and
        //    interferes with modem init — users were seeing SIM registration
        //    fail until they toggled airplane mode. Give the modem and su
        //    daemon ~30s of breathing room before we touch either.
        try {
            Thread.sleep(COLD_BOOT_QUIET_PERIOD_MS)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }

        // 1. Wait for a SIM + phone number to appear. A cold boot on the TCL
        //    Flip Go can take ~30s to finish SIM initialization.
        val (imei, iccid, phone) = waitForSimAndPhone(ctx)
        Log.i(TAG, "SIM ready — imei=$imei iccid=$iccid phone=$phone")

        // 2. Wait for network before any HTTP work. NetworkUtils fires the
        //    callback as soon as an internet-capable network appears.
        awaitNetwork(ctx)
        Log.i(TAG, "Network available — evaluating registration state")

        // 3. Compare to the last state we successfully registered.
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastImei = prefs.getString(KEY_IMEI, null)
        val lastIccid = prefs.getString(KEY_ICCID, null)
        val lastPhone = prefs.getString(KEY_PHONE, null)
        val registeredAt = prefs.getLong(KEY_REGISTERED_AT, 0L)

        val neverRegistered = registeredAt == 0L ||
            lastImei != imei ||
            lastIccid != iccid
        val phoneChanged = !neverRegistered && lastPhone != phone

        when {
            neverRegistered -> {
                Log.i(TAG, "First-time registration for this device/SIM")
                val ok = registerAll(imei, iccid, phone)
                if (ok) persist(prefs, imei, iccid, phone)
            }
            phoneChanged -> {
                Log.i(TAG, "Phone number changed ($lastPhone → $phone) — updating phoneline")
                val ok = updatePhoneline(iccid, phone)
                if (ok) persist(prefs, imei, iccid, phone)
            }
            else -> {
                Log.i(TAG, "No change since last registration — nothing to do")
            }
        }
    }

    /**
     * Polls until the SIM is ready AND we can read IMEI + ICCID + phone
     * number. Returns the three values.
     *
     * Blocks indefinitely on purpose — this runs on a dedicated worker
     * thread and the launcher is persistent, so if the user never inserts
     * a SIM we just sit here cheaply until they do.
     */
    private fun waitForSimAndPhone(ctx: Context): Triple<String, String, String> {
        while (true) {
            val simReady = SimInfoReader.isSimReady(ctx)
            val imei = SimInfoReader.readImei(ctx)
            val iccid = SimInfoReader.readIccid(ctx)
            val (phone, _) = PhoneNumberReader.read(ctx)

            if (simReady && !imei.isNullOrBlank() && !iccid.isNullOrBlank() && !phone.isNullOrBlank()) {
                return Triple(imei, iccid, normalizePhone(phone))
            }

            Log.d(
                TAG,
                "Waiting for SIM (simReady=$simReady imei=${imei ?: "∅"} " +
                    "iccid=${iccid ?: "∅"} phone=${phone ?: "∅"})"
            )
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ie
            }
        }
    }

    /** Blocks the caller thread until NetworkUtils reports network up. */
    private fun awaitNetwork(ctx: Context) {
        if (NetworkUtils.isNetworkAvailable(ctx)) return
        val lock = Object()
        val ready = AtomicBoolean(false)
        NetworkUtils.whenNetworkAvailable(ctx) {
            synchronized(lock) {
                ready.set(true)
                lock.notifyAll()
            }
        }
        synchronized(lock) {
            while (!ready.get()) {
                try {
                    lock.wait()
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // API calls — mirrors device_registration.sh
    // ---------------------------------------------------------------------

    private fun registerAll(imei: String, iccid: String, phone: String): Boolean {
        val phonesOk = putPhones(imei, iccid)
        val simsOk = putSims(iccid)
        val phonelineOk = putPhoneline(iccid, phone)
        val verifyOk = verifyPhoneRecord(imei, iccid)

        val ok = phonesOk && simsOk && phonelineOk && verifyOk
        Log.i(
            TAG,
            "registerAll: phones=$phonesOk sims=$simsOk phoneline=$phonelineOk " +
                "verify=$verifyOk → ${if (ok) "✅" else "⚠️"}"
        )
        return ok
    }

    private fun updatePhoneline(iccid: String, phone: String): Boolean {
        val ok = putPhoneline(iccid, phone)
        Log.i(TAG, "updatePhoneline: ${if (ok) "✅" else "⚠️"}")
        return ok
    }

    private fun putPhones(imei: String, iccid: String): Boolean {
        val body = JSONObject()
            .put("sim_number", iccid)
            .put("printBarcode", 1)
            .put("isHacked", 1)
            .put("software_version", SOFTWARE_VERSION)
        return putJson("$API_BASE/phones/$imei", body, "phones")
    }

    private fun putSims(iccid: String): Boolean {
        val body = JSONObject().put("status", "Active")
        return putJson("$API_BASE/sims/$iccid", body, "sims")
    }

    private fun putPhoneline(iccid: String, phone: String): Boolean {
        val body = JSONObject().put("sim_number", iccid)
        // Phone number is E.164-normalized (+1…); the shell script strips the
        // leading 1 and uses bare digits in the URL path.
        val pathPhone = phone.removePrefix("+").removePrefix("1")
        return putJson("$API_BASE/phonelines/$pathPhone", body, "phonelines")
    }

    /**
     * GET /phones/{imei} and confirm sim_number + software_version match.
     *
     * Robust against two response shapes because some older deployments of
     * the backend return a JSONArray of all phones from this endpoint
     * instead of the single object the current route is supposed to return:
     *   - JSONObject: use it directly
     *   - JSONArray:  find the entry whose imei matches
     * Any parse or network error is swallowed and logged — this method must
     * never crash the registration thread.
     */
    private fun verifyPhoneRecord(imei: String, iccid: String): Boolean {
        return try {
            val req = Request.Builder().url("$API_BASE/phones/$imei").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "verify: HTTP ${resp.code}")
                    return false
                }
                val bodyStr = resp.body?.string() ?: "{}"
                val record = extractPhoneRecord(bodyStr, imei)
                if (record == null) {
                    Log.w(TAG, "verify: no record found for imei=$imei in response")
                    return false
                }
                val verifiedSim = record.optString("sim_number")
                val verifiedVer = record.optString("software_version")
                val simOk = verifiedSim == iccid
                val verOk = verifiedVer == SOFTWARE_VERSION
                if (!simOk) Log.w(TAG, "verify: sim mismatch expected=$iccid got=$verifiedSim")
                if (!verOk) Log.w(TAG, "verify: version mismatch expected=${SOFTWARE_VERSION} got=$verifiedVer")
                simOk && verOk
            }
        } catch (e: Exception) {
            Log.w(TAG, "verify: failed (${e.javaClass.simpleName}): ${e.message}")
            false
        }
    }

    /**
     * Parse the phones-endpoint response in a shape-tolerant way. Returns the
     * single phone record for `imei`, or null if it can't be found / parsed.
     */
    private fun extractPhoneRecord(body: String, imei: String): JSONObject? {
        return try {
            when (val parsed = JSONTokener(body).nextValue()) {
                is JSONObject -> parsed
                is JSONArray -> {
                    for (i in 0 until parsed.length()) {
                        val item = parsed.optJSONObject(i) ?: continue
                        if (item.optString("imei") == imei) return item
                    }
                    null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractPhoneRecord: parse failed: ${e.message}")
            null
        }
    }

    private fun putJson(url: String, body: JSONObject, label: String): Boolean {
        return try {
            val req = Request.Builder()
                .url(url)
                .put(body.toString().toRequestBody(JSON_TYPE))
                .build()
            http.newCall(req).execute().use { resp ->
                val ok = resp.isSuccessful
                if (ok) {
                    Log.i(TAG, "$label ✔ HTTP ${resp.code} ($url)")
                } else {
                    Log.w(TAG, "$label FAIL HTTP ${resp.code} ($url) body=${resp.body?.string()}")
                }
                ok
            }
        } catch (e: IOException) {
            Log.w(TAG, "$label IO error: ${e.message}")
            false
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun persist(
        prefs: android.content.SharedPreferences,
        imei: String,
        iccid: String,
        phone: String,
    ) {
        prefs.edit()
            .putString(KEY_IMEI, imei)
            .putString(KEY_ICCID, iccid)
            .putString(KEY_PHONE, phone)
            .putLong(KEY_REGISTERED_AT, System.currentTimeMillis())
            .apply()
    }

    /** Normalize to E.164 and strip dashes/whitespace for stable comparison. */
    private fun normalizePhone(raw: String): String =
        PhoneNumberReader.formatE164(raw.trim().replace("-", "").replace(" ", ""))
}
