package com.offlineinc.dumbdownlauncher.registration

import android.content.Context
import android.util.Log
import com.offlineinc.dumbdownlauncher.AllAppsActivity
import com.offlineinc.dumbdownlauncher.MainAppsGridActivity
import com.offlineinc.dumbdownlauncher.launcher.NetworkUtils
import com.offlineinc.dumbdownlauncher.launcher.PhoneNumberReader
import com.offlineinc.dumbdownlauncher.pairing.PairingApiClient
import com.offlineinc.dumbdownlauncher.pairing.PairingStore
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

    // Timeouts are deliberately generous because the user is staring at an
    // "activating ur phone..." spinner on the boot screen and we'd rather they
    // wait a little longer than see a spurious failure. The backend sometimes
    // takes 20–40s on a cold Heroku dyno + the first mobile-data round-trip
    // after SIM registration can be slow, so the previous (15s / 30s) budget
    // was tripping the retry loop before the server ever replied.
    //
    //   connectTimeout — time to establish the TCP + TLS handshake. 30s gives
    //                    the radio time to attach to the tower and bring up
    //                    the data bearer on a flaky signal.
    //   readTimeout    — time to receive response bytes after the request is
    //                    sent. 60s covers a cold Heroku dyno boot (10–30s)
    //                    plus the backend's DB round-trip.
    //   writeTimeout   — OkHttp default is 10s; bump to 30s for symmetry
    //                    with read on slow uplinks.
    //   callTimeout    — overall cap per attempt. With a callTimeout the
    //                    full connect+write+read pipeline is bounded, so a
    //                    single hung attempt can't stall registerNow() past
    //                    ~75s regardless of which stage is slow.
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
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

    /**
     * Immediate, blocking registration for use from the pairing screen.
     * Accepts pre-read SIM identifiers to avoid redundant root shell calls.
     * Uses the single POST /api/v1/register endpoint (1 HTTP call instead of 4).
     *
     * Returns `true` if the device was successfully registered (or was
     * already registered for this SIM). Thread-safe — can be called from
     * [Dispatchers.IO] while [scheduleOnBoot] is also in progress.
     *
     * @param imei   IMEI read by caller (avoids re-reading via root shell).
     * @param iccid  ICCID read by caller.
     * @param phone  Phone number in E.164 format read by caller.
     * @param maxRetries  Number of attempts on failure. Default 4 with
     *               exponential backoff (2s / 4s / 8s between attempts). At
     *               the 75s per-attempt cap this gives the boot screen up to
     *               ~5 minutes of patience before surfacing an error — bad
     *               UX for a one-off slow response is still much better than
     *               bailing out while the backend is mid-response.
     * @param force  If true, bypass the "already registered for this SIM"
     *               short-circuit and always call the backend. Used from the
     *               Device Setup flow so re-running onboarding hits the
     *               backend every time, even with the same SIM.
     */
    fun registerNow(
        context: Context,
        imei: String,
        iccid: String,
        phone: String,
        maxRetries: Int = 4,
        force: Boolean = false,
    ): Boolean {
        val ctx = context.applicationContext
        val normalizedPhone = normalizePhone(phone)

        // 0. Bail fast if there's no network — avoids burning through retries
        //    with backoff when the radio isn't up yet.
        if (!NetworkUtils.isNetworkAvailable(ctx)) {
            Log.w(TAG, "registerNow: no network available — skipping")
            return false
        }

        // 1. Check if already registered for this SIM. The onboarding flow
        //    passes force=true so users re-running Device Setup always hit
        //    the backend.
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastImei = prefs.getString(KEY_IMEI, null)
        val lastIccid = prefs.getString(KEY_ICCID, null)
        val registeredAt = prefs.getLong(KEY_REGISTERED_AT, 0L)

        if (!force && registeredAt != 0L && lastImei == imei && lastIccid == iccid) {
            Log.i(TAG, "registerNow: already registered for this SIM — nothing to do")
            return true
        }
        if (force && registeredAt != 0L && lastImei == imei && lastIccid == iccid) {
            Log.i(TAG, "registerNow: force=true — re-registering despite cached SIM match")
        }

        // 2. Register via single combined endpoint, with retries.
        // Strip the phone number to bare digits (no +1) for the phoneline key,
        // matching the format the backend expects.
        val pathPhone = normalizedPhone.removePrefix("+").removePrefix("1")
        for (attempt in 1..maxRetries) {
            Log.i(TAG, "registerNow: attempt $attempt/$maxRetries")
            val ok = postRegister(imei, iccid, pathPhone)
            if (ok) {
                persist(prefs, imei, iccid, normalizedPhone)
                Log.i(TAG, "registerNow: ✅ registered on attempt $attempt")
                return true
            }
            if (attempt < maxRetries) {
                // Exponential backoff: 2s, 4s, 8s, 16s, … capped at 30s so
                // the total retry budget is bounded even at higher maxRetries.
                // Gives a slow backend breathing room between retries rather
                // than hammering it with the old linear 2s/4s/6s schedule.
                val backoffMs = (2000L shl (attempt - 1).coerceAtMost(5)).coerceAtMost(30_000L)
                Log.w(TAG, "registerNow: attempt $attempt failed — retrying in ${backoffMs}ms")
                try {
                    Thread.sleep(backoffMs)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }

        Log.e(TAG, "registerNow: ⚠️ all $maxRetries attempts failed")
        return false
    }

    /**
     * Single POST to /api/v1/register — replaces the four separate PUT calls
     * (phones, sims, phonelines, verify) with one round-trip.
     */
    private fun postRegister(imei: String, iccid: String, phoneNumber: String): Boolean {
        return try {
            val body = JSONObject()
                .put("imei", imei)
                .put("iccid", iccid)
                .put("phone_number", phoneNumber)
                .put("software_version", SOFTWARE_VERSION)
            val req = Request.Builder()
                .url("$API_BASE/register")
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()
            // Mirrors the PairingAPI one-liner ("HTTP GET <url>") so device
            // logs show the same shape for every outbound HTTP call. The
            // body is logged too (distinct Log.d) so a tail of DeviceRegistrar
            // shows exactly what request hit the backend.
            Log.d(TAG, "HTTP ${req.method} ${req.url}")
            Log.d(TAG, "register request body=$body")
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "register FAIL HTTP ${resp.code} body=${resp.body?.string()}")
                    return false
                }
                val respBody = resp.body?.string() ?: "{}"
                val json = JSONObject(respBody)
                val registered = json.optBoolean("registered", false)
                if (registered) {
                    Log.i(TAG, "register ✔ HTTP ${resp.code}")
                } else {
                    Log.w(TAG, "register: server says not verified — $respBody")
                }
                registered
            }
        } catch (e: IOException) {
            Log.w(TAG, "register IO error: ${e.message}")
            false
        }
    }

    // ---------------------------------------------------------------------
    // Main loop
    // ---------------------------------------------------------------------

    private fun runBlocking(ctx: Context) {
        // 0a. Fast-path: if the foreground [BootRegistrationScreen] ran
        //     very recently (fresh phone or Device Setup re-entry) it has
        //     already done the SIM read + /register + bundle-flag fetch
        //     that this path would do. Skip outright — no value in doing
        //     the same round-trips twice.
        if (recentlyRegistered(ctx)) {
            Log.i(TAG, "runBlocking: boot screen registered recently — skipping")
            return
        }

        // 0b. Cold-boot quiet period. The launcher's onCreate fans out several
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

        // 0c. Re-check after the quiet period: BootRegistrationScreen
        //     typically finishes within 10–20s of app launch, so by the
        //     time we wake up from the 30s sleep it may have finished and
        //     made this pass redundant.
        if (recentlyRegistered(ctx)) {
            Log.i(TAG, "runBlocking: boot screen registered during quiet period — skipping")
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

        // 4. Refresh the Gigs-plan-derived bundle flags
        //    (hideAudioBundle / hideSmartTxt) once network + SIM are up and
        //    device registration has run. Uses the same 30s-quiet-period
        //    timing as /register so we don't stack round-trips with the
        //    modem coming online.
        //
        //    Runs on every boot — even when registration itself was a no-op —
        //    because the user's Gigs plan can change between boots (upgrade /
        //    downgrade) and the launcher shouldn't keep stale flags. The
        //    device-setup screen also re-fetches these during its explicit
        //    "checking bundle..." step; this is just the passive refresh.
        refreshBundleFlags(ctx, phone)
    }

    /**
     * Fetch `/contact-sync/bundle-flags` for this phone number and persist
     * the resulting `hideAudioBundle` / `hideSmartTxt` flags into
     * [PairingStore]. Best-effort — all exceptions are swallowed so a
     * transient backend or network error never blocks registration.
     *
     * Visible for unit testing and for the Device Setup screen to call
     * directly if it wants to chain off registration without going through
     * its own PairingApiClient instance.
     */
    fun refreshBundleFlags(ctx: Context, phone: String) {
        try {
            val api = PairingApiClient(http)
            val result = api.getBundleFlags(phone)
            val store = PairingStore(ctx)
            // Snapshot the previous flags BEFORE overwriting so we can
            // detect tier changes. A user's Gigs plan can change between
            // boots (upgrade dumb → dumber, downgrade dumbest → dumb, etc.)
            // and the launcher's cached app visibility needs to follow —
            // otherwise the audio bundle tile stays visible on a user who
            // just upgraded, or vice versa, until they next navigate.
            val prevHideAudio = store.hideAudioBundle
            val prevHideSmart = store.hideSmartTxt
            store.hideAudioBundle = result.hideAudioBundle
            store.hideSmartTxt = result.hideSmartTxt
            Log.i(
                TAG,
                "refreshBundleFlags ✔ planId=${result.planId} tier=${result.tier} " +
                    "hideAudioBundle=${result.hideAudioBundle} hideSmartTxt=${result.hideSmartTxt}"
            )

            val audioChanged = prevHideAudio != result.hideAudioBundle
            val smartChanged = prevHideSmart != result.hideSmartTxt
            if (audioChanged || smartChanged) {
                // Both the all-apps list and the 3×3 home grid filter apps
                // by hideAudioBundle / hideSmartTxt. Their caches are keyed
                // on the package set, not the flag values, so they don't
                // invalidate themselves when flags flip — we have to tell
                // them. Run on the application context so we don't leak a
                // short-lived caller context into the async rebuild.
                val appCtx = ctx.applicationContext
                Log.i(
                    TAG,
                    "refreshBundleFlags: tier change detected " +
                        "(audio $prevHideAudio→${result.hideAudioBundle}, " +
                        "smart $prevHideSmart→${result.hideSmartTxt}) — busting caches"
                )
                AllAppsActivity.invalidateCache()
                MainAppsGridActivity.invalidateAndRebuildAsync(appCtx)
            }
        } catch (e: Exception) {
            // 404 (no gigs row yet / brand-new activation) is expected for
            // devices that haven't had the Gigs webhook fire. The next boot
            // will try again.
            Log.w(TAG, "refreshBundleFlags: skipped (${e.message})")
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
            // Same one-liner format as PairingAPI so device logs line up.
            Log.d(TAG, "HTTP ${req.method} ${req.url}")
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
            // Same one-liner format as PairingAPI so device logs line up.
            Log.d(TAG, "HTTP ${req.method} ${req.url}")
            Log.d(TAG, "$label request body=$body")
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

    /**
     * True if this device was registered within the recent-registration
     * window (5 minutes). Used to short-circuit the background boot pass
     * when [BootRegistrationScreen] has already done the work. 5 min is
     * comfortably longer than any realistic /register round-trip +
     * bundle-flag fetch (≤ 30s) and short enough that a true cold boot
     * after a reboot won't accidentally match.
     */
    private fun recentlyRegistered(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val registeredAt = prefs.getLong(KEY_REGISTERED_AT, 0L)
        if (registeredAt == 0L) return false
        val ageMs = System.currentTimeMillis() - registeredAt
        return ageMs in 0 until TimeUnit.MINUTES.toMillis(5)
    }
}
