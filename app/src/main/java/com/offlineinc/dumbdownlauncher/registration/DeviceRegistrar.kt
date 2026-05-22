package com.offlineinc.dumbdownlauncher.registration

import android.content.Context
import android.util.Log
import com.offlineinc.dumbdownlauncher.AllAppsActivity
import com.offlineinc.dumbdownlauncher.MainAppsGridActivity
import com.offlineinc.dumbdownlauncher.launcher.NetworkUtils
import com.offlineinc.dumbdownlauncher.launcher.PhoneNumberReader
import com.offlineinc.dumbdownlauncher.pairing.PairingApiClient
import com.offlineinc.dumbdownlauncher.pairing.PairingStore
import com.offlineinc.dumbdownlauncher.pairing.PhoneNumberNotFoundException
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background port of `device_registration.sh` from dumb-phone-configuration.
 *
 * Responsibilities:
 *   1. Wait (indefinitely, in the background) for a SIM to be inserted AND
 *      for the device to have network connectivity.
 *   2. The first time the {IMEI, ICCID, phone number} triple is seen — or
 *      any time the IMEI or ICCID has changed since the last successful
 *      registration — POST it to the combined registration endpoint:
 *           POST /api/v1/register  body: { imei, iccid, phone_number, software_version }
 *      One HTTP call replaces the legacy four-PUT sequence (phones, sims,
 *      phonelines, verify). This call does NOT allocate a QR code; QR
 *      assignment is handled separately by
 *      [DumbDownApp.populateOpenBubblesActivationCode] when the OpenBubbles
 *      dumb file is empty (it POSTs with `assign_qr: true`, which triggers
 *      the backend's SIM-keyed dedup so the same physical device never
 *      gets a second QR allocated from the pool).
 *   3. On every subsequent boot where IMEI and ICCID are unchanged but
 *      the phone number isn't (e.g. the carrier reissued the number), the
 *      phoneline mapping is re-sent via PUT /api/v1/phonelines/{phone} so
 *      the server stays in sync.
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

    /**
     * Returns the IMEI we last successfully registered with the backend, or
     * null if the device has never completed registration. Backed by the same
     * SharedPreferences row that [persist] writes; reading is essentially
     * free, so callers that need IMEI on a hot path (e.g. the activation-code
     * fallback in [DumbDownApp.populateOpenBubblesActivationCode]) should
     * prefer this over the multi-second [SimInfoReader.readImei] cascade.
     */
    @JvmStatic
    fun getCachedImei(ctx: Context): String? {
        val prefs = ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_IMEI, null)?.takeIf { it.isNotBlank() }
    }

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

    /**
     * Set by [postRegister] (and only that one) when its most recent failure
     * was a network-level error (UnknownHostException / SocketTimeout /
     * ConnectException) rather than an HTTP non-2xx response. Used by
     * [registerNow]'s retry loop to decide whether to trigger
     * [PhoneNumberReader.cycleAirplaneToReattachLte] — the recovery is only
     * useful for the post-USSD CSFB-stuck-on-2G case, which manifests as
     * DNS / connect failures, not as 4xx/5xx responses.
     */
    @Volatile private var lastFailureWasNetworkError: Boolean = false

    /**
     * Once-per-process guard so we don't keep re-toggling airplane mode if
     * the recovery didn't actually fix things. The CSFB stuck-on-2G state
     * either resolves on the first toggle or it doesn't — repeated toggles
     * just punish the user with more "no signal" intervals. Reset only when
     * the process restarts.
     */
    private val csfbRecoveryAttempted = AtomicBoolean(false)

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
        if (normalizedPhone == null) {
            // Unparseable input — surface as a registration failure rather
            // than send garbage to the backend. The backend's toE164 would
            // 400 on the same value, so this just shifts the error one hop
            // closer with a more actionable log line.
            Log.e(TAG, "registerNow: phone='$phone' could not be normalized to E.164 — aborting")
            return false
        }

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
        // Send the canonical E.164 string ("+1XXXXXXXXXX") to the backend.
        // The backend's /api/v1/register normalizes via libphonenumber and
        // stores E.164 in phone_lines.phone_number. (Pre-Phase-4 the backend
        // used to expect bare 10-digit US numbers in the phoneline key, so
        // this caller stripped the "+1" before sending. That's no longer the
        // case — the backend accepts both formats but stores E.164.)
        for (attempt in 1..maxRetries) {
            Log.i(TAG, "registerNow: attempt $attempt/$maxRetries")
            val ok = postRegister(imei, iccid, normalizedPhone)
            if (ok) {
                persist(prefs, imei, iccid, normalizedPhone)
                Log.i(TAG, "registerNow: ✅ registered on attempt $attempt")
                return true
            }

            // Reactive CSFB-stuck-on-2G recovery. If postRegister failed with
            // a transport-level error (DNS/connect/timeout — typical symptom
            // of the post-USSD stuck-on-2G modem state on TCL/MediaTek
            // T-Mobile units), toggle airplane mode once to force a clean
            // LTE re-attach. Process-lifetime guard ([csfbRecoveryAttempted])
            // ensures we only do it once per process — repeated toggles
            // wouldn't help and just punish the user with more no-signal
            // intervals. We skip the normal exponential backoff after a
            // recovery attempt because the recovery itself blocks ~10-15s
            // (which is plenty of breathing room) and we want to retry as
            // soon as the network is validated.
            if (lastFailureWasNetworkError &&
                attempt < maxRetries &&
                csfbRecoveryAttempted.compareAndSet(false, true)) {
                Log.i(TAG, "registerNow: attempt $attempt hit a network error — " +
                    "triggering one-shot CSFB recovery (airplane-mode toggle) before retry")
                PhoneNumberReader.cycleAirplaneToReattachLte(ctx)
                Log.i(TAG, "registerNow: CSFB recovery complete — retrying immediately")
                continue  // skip the standard backoff sleep
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
        // Reset the network-error flag at the start of each attempt; it'll
        // be set to true by the IOException catch below only if the failure
        // was a transport-level problem (couldn't reach the server). HTTP
        // 4xx/5xx responses leave it false because the server WAS reachable
        // and CSFB recovery wouldn't help.
        lastFailureWasNetworkError = false
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
            // IOException covers UnknownHostException (DNS), SocketTimeoutException,
            // ConnectException, etc. — all "couldn't reach server" failures.
            // These are exactly the symptoms of the post-USSD CSFB stuck-on-2G
            // state, so we flag them for the retry loop to potentially trigger
            // airplane-mode recovery.
            Log.w(TAG, "register IO error: ${e.message}")
            lastFailureWasNetworkError = true
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

        // 0d. Backend Gigs phone-number lookup (fast path BEFORE USSD).
        //     Grab the ICCID as soon as the SIM lets us read it, then ask
        //     the backend whether gigs_subscriptions already knows this
        //     SIM's phone number. If it does (the common case for any SIM
        //     activated through Gigs — webhooks populate phone_number on
        //     activation), persist to Settings.Secure so PhoneNumberReader's
        //     readViaUssd pre-check finds it and SKIPS the carrier #686#
        //     scrape entirely. Net effect: 1 HTTP round-trip (~1s) replaces
        //     the 15-60s USSD path for any provisioning-time launch.
        //
        //     This block is best-effort: any failure (no ICCID yet, no
        //     network, 404 from backend, timeout) silently falls through to
        //     the existing waitForSimAndPhone path below, which still uses
        //     USSD as the authoritative fallback.
        val earlyIccid = waitForIccidOnly(ctx)
        if (earlyIccid != null) {
            awaitNetwork(ctx)
            val resolved = lookupPhoneByIccid(earlyIccid)
            if (resolved != null) {
                persistResolvedPhone(resolved, earlyIccid)
                Log.i(TAG, "Backend Gigs lookup populated Settings.Secure — USSD will be skipped by PhoneNumberReader")
            } else {
                Log.i(TAG, "Backend Gigs lookup empty — PhoneNumberReader will fall through to USSD")
            }
        } else {
            Log.w(TAG, "ICCID not readable within wait budget — skipping backend lookup, will rely on USSD path")
        }

        // 1. Wait for a SIM + phone number to appear. A cold boot on the TCL
        //    Flip Go can take ~30s to finish SIM initialization.
        //
        //    If step 0d succeeded above, PhoneNumberReader's USSD pre-check
        //    finds the value in Settings.Secure and returns immediately
        //    (no USSD round-trip). If 0d failed/skipped, this is the
        //    original slow path through USSD #686#.
        val (imei, iccid, phone) = waitForSimAndPhone(ctx)
        Log.i(TAG, "SIM ready — imei=$imei iccid=$iccid phone=$phone")

        // 2. Wait for network before any HTTP work. NetworkUtils fires the
        //    callback as soon as an internet-capable network appears. Noop
        //    if step 0d already waited for it.
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
                // Single POST /api/v1/register — same call [registerNow] makes
                // from the Device Setup screens. Replaces the legacy four-PUT
                // sequence (phones, sims, phonelines, verify) with one
                // round-trip. No QR allocation here — that's the job of
                // [DumbDownApp.populateOpenBubblesActivationCode] when the
                // OpenBubbles dumb file is empty (it POSTs with
                // assign_qr=true, which triggers the backend's SIM-keyed
                // dedup so the same physical device never gets a second QR
                // burned from the pool).
                //
                // `neverRegistered` fires on lastImei != imei — exactly the
                // case that happens on the first boot after the SimInfoReader
                // cascade fix corrects a previously-wrong cached IMEI. That
                // creates a fresh phones row keyed by the real IMEI; the old
                // ICCID/IMSI/serialno phantom rows stay where they are and
                // are released only via a future cleanup sweep.
                Log.i(TAG, "First-time registration for this device/SIM")
                val ok = postRegister(imei, iccid, phone)
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
     * [PairingStore]. Best-effort — transient exceptions are swallowed so
     * a flaky backend or network error never blocks registration.
     *
     * A 404 from the backend (phone not in `gigs_subscriptions`) is not a
     * failure: it's an authoritative "no subscription data yet" signal, so
     * we explicitly write `hideAudioBundle = false` / `hideSmartTxt = false`
     * rather than leaving whatever stale value was cached. This matters for
     * downgrade cases — a user who went from Dumbest back to Dumb would
     * otherwise keep the hidden upsells cached until a 200 arrived.
     *
     * Visible for unit testing and for the Device Setup screen to call
     * directly if it wants to chain off registration without going through
     * its own PairingApiClient instance.
     */
    fun refreshBundleFlags(ctx: Context, phone: String) {
        val store = PairingStore(ctx)
        // Snapshot the previous flags BEFORE overwriting so we can detect
        // tier changes in both the 200 and 404 paths. A user's Gigs plan
        // can change between boots (upgrade dumb → dumber, downgrade
        // dumbest → dumb, etc.) and the launcher's cached app visibility
        // needs to follow — otherwise the audio bundle tile stays visible
        // on a user who just upgraded, or vice versa, until they next
        // navigate.
        val prevHideAudio = store.hideAudioBundle
        val prevHideSmart = store.hideSmartTxt

        // Must be `var` — Kotlin's definite-assignment analysis doesn't
        // recognise that the try + two catch blocks are mutually exclusive,
        // so a `val` assigned in each branch fails to compile.
        var newHideAudio: Boolean
        var newHideSmart: Boolean
        var logSuffix: String

        try {
            val api = PairingApiClient(http)
            val result = api.getBundleFlags(phone)
            newHideAudio = result.hideAudioBundle
            newHideSmart = result.hideSmartTxt
            logSuffix = "planId=${result.planId} tier=${result.tier}"
            Log.i(
                TAG,
                "refreshBundleFlags ✔ $logSuffix " +
                    "hideAudioBundle=$newHideAudio hideSmartTxt=$newHideSmart"
            )
        } catch (e: PhoneNumberNotFoundException) {
            // 404 → no gigs_subscriptions row for this number (brand-new
            // activation, Gigs webhook hasn't fired, or the user has no
            // subscription). Treat as authoritative "no bundle perks" so
            // any previously cached `true` flags don't linger forever.
            newHideAudio = false
            newHideSmart = false
            logSuffix = "404 (phone not found)"
            Log.i(
                TAG,
                "refreshBundleFlags: $logSuffix — defaulting " +
                    "hideAudioBundle=false hideSmartTxt=false"
            )
        } catch (e: Exception) {
            // Transient failure (network blip, 5xx, timeout). Do NOT
            // overwrite cached flags — the next boot will try again.
            Log.w(TAG, "refreshBundleFlags: skipped (${e.message})")
            return
        }

        store.hideAudioBundle = newHideAudio
        store.hideSmartTxt = newHideSmart

        val audioChanged = prevHideAudio != newHideAudio
        val smartChanged = prevHideSmart != newHideSmart
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
                    "(audio $prevHideAudio→$newHideAudio, " +
                    "smart $prevHideSmart→$newHideSmart) — busting caches"
            )
            AllAppsActivity.invalidateCache()
            MainAppsGridActivity.invalidateAndRebuildAsync(appCtx)
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
            // Phase 1: confirm the cheap, mandatory identifiers are in hand
            // before we touch PhoneNumberReader. ICCID + IMEI are direct SIM /
            // modem reads (~ms) and we need them for /register either way, so
            // gating on them keeps each early-boot iteration cheap. Without
            // this gate we'd fire PhoneNumberReader.read on every tick — and
            // when step 0d's Gigs lookup didn't pre-populate Settings.Secure
            // (404, no network, etc.) that means re-entering the USSD cascade
            // before the SIM has even attached, which is pure waste.
            val simReady = SimInfoReader.isSimReady(ctx)
            val imei = SimInfoReader.readImei(ctx)
            val iccid = SimInfoReader.readIccid(ctx)

            if (!simReady || imei.isNullOrBlank() || iccid.isNullOrBlank()) {
                Log.d(
                    TAG,
                    "Waiting for SIM (simReady=$simReady imei=${imei ?: "∅"} " +
                        "iccid=${iccid ?: "∅"})"
                )
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw ie
                }
                continue
            }

            // Phase 2: SIM is up and ICCID is known — now attempt the phone
            // number read. If step 0d's Gigs lookup landed a value in
            // Settings.Secure (and the ICCID pin matches), PhoneNumberReader's
            // pre-check returns it immediately; otherwise this is the slow
            // USSD path. Either way, paying that cost only after ICCID is in
            // hand means we never USSD-poll a not-yet-attached SIM.
            val (phone, _) = PhoneNumberReader.read(ctx)

            if (!phone.isNullOrBlank()) {
                val normalized = normalizePhone(phone)
                if (normalized != null) {
                    return Triple(imei, iccid, normalized)
                }
                // The SIM read produced a value libphonenumber rejected
                // (typically a still-warming modem returning a partial
                // string, or a fictional/test number). Keep polling — a
                // later read once the SIM is fully attached usually
                // returns a parseable value.
                Log.w(TAG, "waitForSimAndPhone: phone='$phone' didn't normalize to E.164 — continuing to poll")
            } else {
                Log.d(TAG, "Waiting for phone number (iccid=$iccid)")
            }
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
    // API calls
    //
    // The neverRegistered path (first-time / IMEI-change / ICCID-change)
    // posts the full payload to /api/v1/register via [postRegister] up above —
    // one round-trip, no separate verify step needed because the response
    // carries `registered` directly.
    //
    // Only the phone-number-changed-but-IMEI-unchanged corner case still
    // uses a legacy PUT, because /api/v1/register expects all four fields
    // and we deliberately want to push *just* the new phoneline mapping
    // without re-upserting the phone/sim rows.
    // ---------------------------------------------------------------------

    private fun updatePhoneline(iccid: String, phone: String): Boolean {
        val ok = putPhoneline(iccid, phone)
        Log.i(TAG, "updatePhoneline: ${if (ok) "✅" else "⚠️"}")
        return ok
    }

    private fun putPhoneline(iccid: String, phone: String): Boolean {
        val body = JSONObject().put("sim_number", iccid)
        // Send the canonical E.164 in the URL path. URL-encode it — the "+"
        // character is technically reserved in path segments and Express
        // automatically decodes "%2B" → "+" when populating req.params, so
        // %2B-encoded reaches the backend as "+18048336200". Pre-migration
        // this stripped "+1" because the backend keyed on bare 10-digit US
        // numbers; post-Phase-5 the backend stores E.164.
        val pathPhone = URLEncoder.encode(phone, "UTF-8")
        return putJson("$API_BASE/phonelines/$pathPhone", body, "phonelines")
    }

    // ---------------------------------------------------------------------
    // Backend Gigs lookup — fast path BEFORE USSD
    // ---------------------------------------------------------------------

    /**
     * Asks the backend's `GET /api/v1/phone-number-by-iccid/{iccid}` endpoint
     * for the phone number associated with this SIM in `gigs_subscriptions`.
     * Returns the normalized E.164 number on a 200 response, null on 404 /
     * 5xx / IO error / parse failure.
     *
     * Why this exists: Gigs' webhook populates `phone_number` on
     * gigs_subscriptions the instant a SIM activates, so for any SIM that's
     * been provisioned through Gigs we can resolve the number in one HTTP
     * round-trip (~200-2000ms) instead of running the launcher's USSD
     * #686# scrape against the carrier (~15-60s on cold boot, fragile to
     * carrier UI changes, can fail entirely on units where the MSISDN file
     * was never written).
     *
     * USSD remains the authoritative fallback when this returns null —
     * common cases are SIMs that pre-date the Gigs integration, port-ins
     * still settling on Gigs' side, or non-Gigs MVNOs.
     */
    private fun lookupPhoneByIccid(iccid: String): String? {
        return try {
            val url = "$API_BASE/phone-number-by-iccid/${URLEncoder.encode(iccid, "UTF-8")}"
            val req = Request.Builder().url(url).get().build()
            Log.d(TAG, "HTTP ${req.method} ${req.url}")
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 404 -> {
                        Log.i(TAG, "lookupPhoneByIccid: 404 — no gigs_subscriptions row for iccid=$iccid (USSD will take over)")
                        null
                    }
                    !resp.isSuccessful -> {
                        Log.w(TAG, "lookupPhoneByIccid FAIL HTTP ${resp.code} body=${resp.body?.string()}")
                        null
                    }
                    else -> {
                        val bodyStr = resp.body?.string()
                        if (bodyStr.isNullOrBlank()) {
                            Log.w(TAG, "lookupPhoneByIccid: 200 with empty body")
                            null
                        } else {
                            val json = JSONObject(bodyStr)
                            val raw = json.optString("phone_number", "").takeIf { it.isNotBlank() }
                            if (raw == null) {
                                Log.w(TAG, "lookupPhoneByIccid: 200 with no phone_number — body=$bodyStr")
                                null
                            } else {
                                val normalized = normalizePhone(raw) ?: raw
                                Log.i(TAG, "lookupPhoneByIccid ✔ HTTP ${resp.code} phone=$normalized source=${json.optString("source")}")
                                normalized
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "lookupPhoneByIccid IO error: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "lookupPhoneByIccid unexpected error: ${e.message}")
            null
        }
    }

    /**
     * Persists a backend-resolved phone number to the same Settings.Secure
     * row that [PhoneNumberReader] consults. Two writes:
     *
     *   1. `Settings.Secure.device_phone_number` — the actual number. This
     *      is the value [PhoneNumberReader.readViaUssd] pre-checks before
     *      firing its USSD round-trip, so populating it short-circuits
     *      every subsequent USSD attempt across all callers.
     *
     *   2. `Settings.Secure.device_phone_number_iccid` — the SIM-pin. Without
     *      this matching the currently-inserted ICCID, readViaUssd treats
     *      the cached number as belonging to a different SIM and forces a
     *      fresh USSD query. Pin is required for the skip to work.
     *
     * Best-effort: writes go through `su` since /system Settings.Secure rows
     * require root. Both writes are timeout-bounded (1.5s each) so a wedged
     * Magisk daemon can't block the registration thread indefinitely. On
     * failure we log and move on — the launcher still has the number in
     * memory for the /register call; the next cold boot will simply re-fetch.
     */
    private fun persistResolvedPhone(phone: String, iccid: String) {
        runSuCommand("settings put secure device_phone_number $phone")
        runSuCommand("settings put secure device_phone_number_iccid $iccid")
        Log.i(TAG, "persistResolvedPhone: wrote Settings.Secure (phone + iccid pin)")
    }

    /**
     * Polls [SimInfoReader.readIccid] until it returns a non-blank value or
     * we hit [maxWaitMs]. Used to grab the ICCID early enough for the
     * backend Gigs lookup, without waiting through PhoneNumberReader's
     * full read cascade (which would trigger USSD).
     */
    private fun waitForIccidOnly(ctx: Context, maxWaitMs: Long = 60_000L): String? {
        val deadline = System.currentTimeMillis() + maxWaitMs
        var iccid: String? = SimInfoReader.readIccid(ctx)
        while (iccid.isNullOrBlank() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(2_000L) } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt(); return null
            }
            iccid = SimInfoReader.readIccid(ctx)
        }
        return iccid?.takeIf { it.isNotBlank() }
    }

    /**
     * Same shell-out pattern as [PhoneNumberReader]'s private helper — copied
     * here so DeviceRegistrar doesn't introduce a dependency on
     * PhoneNumberReader's internals (PhoneNumberReader stays purely a
     * data-source helper). Timeout is generous-ish for cold-boot Magisk
     * latency but bounded so a wedged daemon can't stall the boot thread.
     */
    private fun runSuCommand(cmd: String, timeoutMs: Long = 1500L): String? {
        var proc: Process? = null
        return try {
            proc = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                Log.w(TAG, "runSuCommand($cmd) timed out after ${timeoutMs}ms")
                proc.destroyForcibly()
                return null
            }
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            if (proc.exitValue() == 0) output else null
        } catch (e: Exception) {
            Log.w(TAG, "runSuCommand($cmd) failed: ${e.message}")
            try { proc?.destroyForcibly() } catch (_: Exception) {}
            null
        }
    }

    // ---------------------------------------------------------------------

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

    /**
     * Normalize an arbitrary phone-shaped string to canonical E.164.
     *
     * Returns null if the input is unparseable, malformed, or not a valid
     * number for any country. Uses libphonenumber (Google's port — same lib
     * the backend uses via `libphonenumber-js`) so this is symmetric with
     * what the server will accept post-Phase-4: any input shape that
     * `toE164()` accepts on the backend will normalize to the same E.164
     * here.
     *
     * Defaults to "US" region for inputs without a country code (so
     * "8048336200" → "+18048336200"). Inputs that already include the "+"
     * country-code prefix are parsed by their own region.
     *
     * Replaces a NANP-only home-rolled normalizer that stripped non-digits
     * and assumed any 10-digit value was a US number. The new behavior is
     * a strict superset for valid inputs, plus correctly rejects garbage
     * like "abc" or fictional area codes (returns null instead of "+abc"
     * which the backend would have errored on anyway).
     */
    private fun normalizePhone(raw: String, defaultRegion: String = "US"): String? {
        val util = PhoneNumberUtil.getInstance()
        return try {
            val parsed = util.parse(raw, defaultRegion)
            if (!util.isValidNumber(parsed)) null
            else util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
        } catch (e: NumberParseException) {
            null
        }
    }

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
