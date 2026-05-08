package com.offlineinc.dumbdownlauncher.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "PhoneNumberReader"

/**
 * Default max time to wait for the SIM to become ready on cold boot.
 * Bumped from 30s → 60s because on fresh/unactivated SIMs the modem can take
 * well over 30s to finish first-time registration with the carrier, and
 * dropping into SIM_ERROR at 30s makes users think the launcher is broken
 * when the SIM is simply still coming up.
 */
private const val DEFAULT_SIM_WAIT_MS = 60_000L

/**
 * Shared utility for reading the device's own phone number.
 *
 * Tries, in order:
 * 1. USSD query to the carrier (#686# on T-Mobile / Tello) via
 *    [TelephonyManager.sendUssdRequest]. Authoritative — bypasses the SIM's
 *    MSISDN file and asks the carrier's HLR directly. This is the only path
 *    that works on units where MSISDN was never written to the SIM (Settings
 *    shows "unknown" under About Phone → My Phone Number) and the setup
 *    script's #686# write step never landed Settings.Secure either. Result is
 *    persisted back to Settings.Secure (and siminfo.number) so the next read
 *    hits the fast path. The cached value is **ICCID-pinned**: every read
 *    compares the SIM that produced the cache against the SIM currently
 *    inserted, so a SIM swap auto-invalidates the cache and re-queries USSD.
 * 2. Root fallback: Settings.Secure (written by setup script via #686# USSD query)
 * 3. Root fallback: content://telephony/siminfo (works on TCL/MediaTek)
 * 4. SubscriptionManager (Android 13+)
 * 5. TelephonyManager.getLine1Number (deprecated but still works on older builds)
 *
 * Returns (phoneNumber, errorMessage) — one will be null. The user-facing
 * error message ("unable to read phone number from SIM") is preserved as the
 * final fallback for the no-SIM / SIM-not-ready cases; USSD silently skips
 * itself when the gates fail so the cascade still ends in that friendly error.
 */
object PhoneNumberReader {

    /**
     * Process-lifetime cache. The phone number can't change without a reboot
     * (SIM swap requires a reboot on these devices), so once we've resolved
     * it we hold onto it for the life of the process. This avoids repeated
     * `su` fallbacks on every read — each miss shells out to root and parses
     * content-provider output which is both slow and noisy in logs.
     *
     * `@Volatile` so writes from background threads are visible to callers
     * on any thread without a lock on the read path.
     */
    @Volatile
    private var cachedNumber: String? = null

    // --- Exponential backoff for the su fallback path ---
    //
    // On cold boot multiple subsystems (AllApps, DeviceRegistrar, SimInfoReader,
    // QuackViewModel, ...) all hit readWithWait concurrently, and each retries
    // after its 60s SIM-ready wait. Without any throttling we shell out to `su`
    // roughly once per second per caller — each attempt takes up to 1500ms to
    // time out, which is spammy in logs and wasteful on CPU for a flip phone
    // that has very little of either.
    //
    // Scheme: after each miss we refuse to re-enter readViaSu() for a window
    // that doubles each time, capped at SU_BACKOFF_CAP_MS. After SU_MAX_ATTEMPTS
    // total failures the su path is disabled for the rest of the process; the
    // Telephony/SubscriptionManager paths keep being tried since they're cheap.
    //
    //   attempt 1 miss → skip for  2s
    //   attempt 2 miss → skip for  4s
    //   attempt 3 miss → skip for  8s
    //   attempt 4 miss → skip for 16s
    //   attempt 5 miss → skip for 32s
    //   attempt 6+ miss → skip for 60s (cap)
    //
    // Note: increments are racy under concurrent callers (two threads can both
    // read count=N and both write count=N+1, losing one increment). That's
    // tolerable — the backoff is a heuristic, not a correctness guarantee, and
    // the worst case is a handful of extra retries before the cap kicks in.
    @Volatile private var suLastFailureMs: Long = 0L
    @Volatile private var suFailureCount: Int = 0
    private const val SU_BACKOFF_BASE_MS = 2_000L
    private const val SU_BACKOFF_CAP_MS = 60_000L
    private const val SU_MAX_ATTEMPTS = 10

    // --- USSD fallback state ---
    //
    // USSD is expensive (~1-3s round-trip to the carrier per attempt, real
    // radio traffic) so we don't retry within a process unless explicitly
    // told to via [invalidateCache]. A failure starts a backoff window; a
    // success disables further attempts because the result has been persisted
    // to Settings.Secure and subsequent reads hit the fast path.
    @Volatile private var ussdAttempted: Boolean = false
    @Volatile private var ussdLastFailureMs: Long = 0L
    @Volatile private var ussdLastFailureWasTransient: Boolean = false
    /**
     * Default backoff after a USSD failure. Used for SecurityException, hard
     * carrier rejections, and parse failures — anything we expect to remain
     * broken for a while.
     */
    private const val USSD_BACKOFF_MS = 30_000L
    /**
     * Shorter backoff for [TelephonyManager.USSD_RETURN_FAILURE] specifically.
     * That code is almost always a transient "modem busy / just-attached /
     * USSD stack still warming up" state on these MediaTek units, especially
     * during the first 60-90s after boot. A 30s window between retries means
     * we can sit dark for 2-3 minutes on a cold boot before the modem is
     * actually ready to answer; 5s lets us catch the first available retry.
     */
    private const val USSD_BACKOFF_TRANSIENT_MS = 5_000L
    /** Hard cap on time a single sendUssdRequest cycle may block. */
    private const val USSD_TIMEOUT_MS = 15_000L
    /**
     * USSD code that asks the carrier to return the subscriber's MSISDN.
     * `#686#` is the T-Mobile / Tello / Mint Mobile self-number query and is
     * what `automated_configuration.sh` already uses during provisioning.
     * Other carriers will need their own code added here later (`*#62#` /
     * `*#100#` / etc.) — keep this a constant so it's easy to find.
     */
    private const val USSD_SELF_NUMBER_CODE = "#686#"

    /**
     * `TelephonyManager.SIM_STATE_LOADED` is `@hide` in the public SDK on
     * API 30, so we can't reference it by name. Value `10` matches
     * `TelephonyProtoEnums.SIM_STATE_LOADED`. Some MediaTek builds settle on
     * this state instead of `SIM_STATE_READY` even with a fully-attached
     * SIM, so we accept it explicitly alongside READY in our SIM-ready gates.
     */
    private const val SIM_STATE_LOADED_INTERNAL = 10

    /**
     * Settings.Secure key that stores the ICCID of the SIM that produced the
     * cached `device_phone_number`. We compare it on every read; on mismatch
     * (SIM swap), the stored number is invalidated and USSD re-queries the
     * carrier. Without this pin we'd serve the previous SIM's number after
     * a swap until something explicitly cleared Settings.Secure.
     */
    private const val KEY_DEVICE_PHONE_NUMBER_ICCID = "device_phone_number_iccid"

    /** Drop the cached number — for tests, or if the caller needs a forced re-read. */
    @JvmStatic
    fun invalidateCache() {
        cachedNumber = null
        // An explicit invalidation usually means the caller has reason to
        // believe the SIM is now ready (e.g. user tapped retry, or boot
        // registration just completed), so give the su path a fresh shot.
        suFailureCount = 0
        suLastFailureMs = 0L
        // Same logic for USSD — explicit retry should re-arm the network query.
        ussdAttempted = false
        ussdLastFailureMs = 0L
        ussdLastFailureWasTransient = false
    }

    /**
     * Wipe every persisted phone-number store the read path consults plus the
     * in-process cache. Used at the start of Device Setup so a stale value
     * left over from a previous SIM (e.g. factory-imaged number from
     * sim_setup.sh, then a subsequent SIM swap) can't masquerade as the
     * current SIM's number.
     *
     * What gets cleared, and why:
     *   1. [cachedNumber] — process-lifetime cache; re-read on next call.
     *   2. `Settings.Secure.device_phone_number` — written by sim_setup.sh
     *      after a successful USSD #686# query. Survives reboots and SIM
     *      swaps, which is exactly what makes it stale.
     *   3. `content://telephony/siminfo` `number` column for currently-
     *      inserted SIMs (`sim_id>=0`). Also written by sim_setup.sh and
     *      can carry a previous SIM's value if the row was re-used after a
     *      swap. The active-SIM filter mirrors [SimInfoReader] so we don't
     *      touch historical (`sim_id=-1`) rows; those are harmless because
     *      every reader filters them out.
     *
     * Best-effort: each step is wrapped so a failing root shell on a non-
     * rooted build doesn't take the others down. Logged at INFO so the
     * launcher logcat clearly shows when stale data was wiped.
     *
     * MUST be called from a background thread — shells out to `su` per step.
     */
    @JvmStatic
    fun clearStoredNumber() {
        cachedNumber = null
        // Reset the su-backoff so the next read after this clear isn't
        // silently skipped because of failures from the previous SIM.
        suFailureCount = 0
        suLastFailureMs = 0L

        try {
            runSuCommand("settings delete secure device_phone_number")
            Log.i(TAG, "Cleared Settings.Secure.device_phone_number")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear Settings.Secure.device_phone_number", e)
        }

        // Use sim_id>=0 to skip historical (removed-SIM) rows; same filter
        // as SimInfoReader.SIMINFO_ACTIVE_WHERE. `>= 0` not `!= -1` because
        // shell-quoting `!` through su -c '…' is fragile across zsh / adb /
        // sh / Android's `content` tool.
        try {
            runSuCommand(
                "content update --uri content://telephony/siminfo " +
                    "--bind number:s: --where \"sim_id>=0\""
            )
            Log.i(TAG, "Cleared siminfo.number for active SIMs")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear siminfo.number", e)
        }
    }

    /**
     * True if we're inside the exponential-backoff window since the last
     * su-fallback failure, or if we've exceeded [SU_MAX_ATTEMPTS] and should
     * stop trying altogether for this process.
     */
    private fun suBackoffActive(): Boolean {
        val count = suFailureCount
        if (count >= SU_MAX_ATTEMPTS) return true
        if (count == 0) return false
        // shl by up to 5 gives us the 2→4→8→16→32→64 progression; cap applies above.
        val window = (SU_BACKOFF_BASE_MS shl (count - 1).coerceAtMost(5))
            .coerceAtMost(SU_BACKOFF_CAP_MS)
        val age = System.currentTimeMillis() - suLastFailureMs
        return age in 0 until window
    }

    fun read(ctx: Context): Pair<String?, String?> {
        // Fast path — return the cached number without touching root or Telephony APIs.
        cachedNumber?.let { return it to null }

        return try {
            // 0. USSD self-number query to the carrier. Authoritative — works on
            //    units where the SIM has no MSISDN file (Settings shows "unknown")
            //    and automated_configuration.sh either never ran or its #686# step
            //    failed to write Settings.Secure. The method internally pre-checks
            //    Settings.Secure and skips USSD when it's already populated, so this
            //    is a no-op on already-provisioned phones — the fast path then falls
            //    straight through to readViaSu below.
            val ussdNumber = readViaUssd(ctx)
            if (ussdNumber != null) {
                Log.i(TAG, "Got phone number via USSD $USSD_SELF_NUMBER_CODE")
                val formatted = formatE164(ussdNumber)
                cachedNumber = formatted
                return formatted to null
            }

            // 1. Setup-script-written values (most reliable on MediaTek flip phones):
            //    automated_configuration.sh writes the number to Settings.Secure and
            //    content://telephony/siminfo during provisioning, so check these first.
            //    If the USSD path above just ran and succeeded, this never executes
            //    (we cached and returned). If USSD ran and persisted to Settings.Secure
            //    but the cache write somehow lost it, this picks it up on the rebound.
            val scriptNumber = readViaSu()
            if (scriptNumber != null) {
                Log.i(TAG, "Got phone number via setup-script store")
                val formatted = formatE164(scriptNumber)
                cachedNumber = formatted
                return formatted to null
            }

            // 2. SubscriptionManager (Android 13+) — SIM API, unreliable on MediaTek
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val subManager = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                val subs = subManager?.activeSubscriptionInfoList
                val number = subs?.firstOrNull()?.number
                if (!number.isNullOrBlank()) {
                    Log.i(TAG, "Got phone number via SubscriptionManager")
                    val formatted = formatE164(number)
                    cachedNumber = formatted
                    return formatted to null
                }
            }

            // 3. TelephonyManager.getLine1Number — deprecated SIM API, last resort
            @Suppress("DEPRECATION")
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val line = tm?.line1Number
            if (!line.isNullOrBlank()) {
                Log.i(TAG, "Got phone number via TelephonyManager")
                val formatted = formatE164(line)
                cachedNumber = formatted
                return formatted to null
            }

            Log.e(TAG, "Phone number not available (all methods exhausted)")
            null to "unable to read phone number from SIM"
        } catch (e: SecurityException) {
            Log.w(TAG, "Need phone permission", e)
            null to null // triggers permission request
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error reading phone number", e)
            null to "unable to read phone number from SIM"
        }
    }

    /** Returns true if a phone number can be read (any method), false otherwise. */
    fun isAvailable(ctx: Context): Boolean {
        val (number, _) = read(ctx)
        return !number.isNullOrBlank()
    }

    /**
     * Like [read], but if the number can't be read immediately, waits up to
     * [timeoutMs] for the SIM to become ready and then retries.
     *
     * This is intended for cold-boot paths (onCreate of the launcher / app list)
     * where the modem takes several seconds to load the SIM, so a synchronous
     * [read] call fires before any of the backing APIs have data.
     *
     * MUST be called from a background thread — it blocks while waiting for the
     * SIM and also invokes the (blocking) root fallbacks inside [read].
     *
     * Uses [SubscriptionManager.OnSubscriptionsChangedListener] to wake up as
     * soon as a subscription shows up, and polls [TelephonyManager.getSimState]
     * as a belt-and-suspenders fallback (some MediaTek builds never fire the
     * subscription callback even with a working SIM).
     */
    @JvmStatic
    @JvmOverloads
    fun readWithWait(ctx: Context, timeoutMs: Long = DEFAULT_SIM_WAIT_MS): Pair<String?, String?> {
        // Fast path — already ready.
        val first = read(ctx)
        if (!first.first.isNullOrBlank()) return first

        Log.i(TAG, "SIM not ready yet — waiting up to ${timeoutMs}ms")
        waitForSimReady(ctx, timeoutMs)
        val second = read(ctx)
        if (!second.first.isNullOrBlank()) {
            Log.i(TAG, "Read phone number after SIM-ready wait")
        } else {
            Log.w(TAG, "Phone number still unavailable after ${timeoutMs}ms wait")
        }
        return second
    }

    /** Blocking variant of [isAvailable] that waits for the SIM to become ready. */
    @JvmStatic
    @JvmOverloads
    fun isAvailableWithWait(ctx: Context, timeoutMs: Long = DEFAULT_SIM_WAIT_MS): Boolean {
        val (number, _) = readWithWait(ctx, timeoutMs)
        return !number.isNullOrBlank()
    }

    /**
     * Blocks up to [timeoutMs] waiting for either:
     *  - a non-empty active subscription list from SubscriptionManager, OR
     *  - TelephonyManager.simState == SIM_STATE_READY.
     *
     * Wakes early via OnSubscriptionsChangedListener when a sub appears; also
     * polls every second so MediaTek builds that never fire the callback still
     * make forward progress.
     */
    private fun waitForSimReady(ctx: Context, timeoutMs: Long) {
        if (isSimReadyNow(ctx)) return

        val latch = CountDownLatch(1)
        val handlerThread = HandlerThread("PhoneNumberReader-SimWait").apply { start() }
        val handler = Handler(handlerThread.looper)
        val subManager = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager

        // Polling fallback — some MediaTek builds never deliver the callback.
        val pollInterval = 1_000L
        val poller = object : Runnable {
            override fun run() {
                if (isSimReadyNow(ctx)) {
                    latch.countDown()
                } else {
                    handler.postDelayed(this, pollInterval)
                }
            }
        }

        try {
            // Construct AND register the listener on the HandlerThread so its
            // internal Handler binds to that thread's Looper — not the caller's.
            // The OnSubscriptionsChangedListener constructor creates a Handler
            // tied to the current thread's Looper, which crashes if the caller
            // is a plain pool thread with no Looper.
            handler.post {
                try {
                    val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
                        override fun onSubscriptionsChanged() {
                            if (isSimReadyNow(ctx)) latch.countDown()
                        }
                    }
                    subManager?.addOnSubscriptionsChangedListener(listener)
                } catch (e: Exception) {
                    Log.w(TAG, "addOnSubscriptionsChangedListener failed: ${e.message}")
                }
            }
            handler.post(poller)

            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            handler.removeCallbacks(poller)
            // Listener cleanup is handled by handlerThread.quitSafely() which
            // tears down the looper and all associated callbacks/listeners.
            handlerThread.quitSafely()
        }
    }

    /** True if the SIM looks ready via any of the cheap APIs. */
    private fun isSimReadyNow(ctx: Context): Boolean {
        try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm?.simState == TelephonyManager.SIM_STATE_READY) return true
        } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                if (!sm?.activeSubscriptionInfoList.isNullOrEmpty()) return true
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * Asks the carrier for our own MSISDN via [TelephonyManager.sendUssdRequest]
     * (the programmatic equivalent of dialing `#686#`). Independent of every
     * SIM-side cache, so it returns a real number on units where `tm.line1Number`,
     * `Settings.Secure.device_phone_number`, and the siminfo content provider
     * are all empty.
     *
     * Gated behind a series of cheap pre-checks; **silently** returns null on
     * any "no SIM / SIM not yet ready / no service / wrong API level / no
     * permission and root grant failed" condition, so the caller's failure
     * cascade still ends with the friendly "unable to read phone number from
     * SIM" message rather than introducing a new error path.
     *
     * On success the result is persisted to `Settings.Secure.device_phone_number`
     * via `su -c "settings put …"` so subsequent reads (in this process or any
     * other) hit the fast path through [readViaSu].
     *
     * MUST be called from a background thread — blocks up to [USSD_TIMEOUT_MS]
     * waiting for the carrier callback. Auto-skips if invoked from the main
     * looper to avoid an ANR.
     *
     * Synchronized at the singleton level so only one thread can be running
     * USSD resolution at a time. Without this, every concurrent cold-boot
     * caller (DeviceRegistrar background pass, BootRegistrationScreen retry
     * tick, QuackViewModel, etc.) would each fire its own sendUssdRequest;
     * the modem would briefly enter a confused "USSD-pending" state that
     * produces `USSD_RETURN_FAILURE` on subsequent attempts and was directly
     * observed adding ~30s to broken-phone first-boot in the field. With the
     * lock, queued callers wait for the in-flight attempt and re-check the
     * cache on entry — they get the result for free if it just landed.
     */
    @Synchronized
    private fun readViaUssd(ctx: Context): String? {
        // Wrong-thread guard — sendUssdRequest is async, but we await its
        // CountDownLatch synchronously, which would ANR if we're on main.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.d(TAG, "readViaUssd: skipping on main thread (would block)")
            return null
        }

        // Double-checked cache lookup — a concurrent caller that beat us
        // through the @Synchronized gate may have just populated the cache.
        // Returning here saves a redundant USSD round-trip and, more
        // importantly, prevents the modem-confusion state described above.
        cachedNumber?.let { return it }

        // API gate — sendUssdRequest landed in API 26.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d(TAG, "readViaUssd: requires API 26+, have ${Build.VERSION.SDK_INT}")
            return null
        }

        // One-shot guard — don't spam the carrier with USSD round-trips. After
        // a miss we wait before retrying within the same process. The window
        // is shorter for transient failures (USSD_RETURN_FAILURE — almost
        // always a still-warming modem on cold boot) than for hard ones
        // (SecurityException, carrier service unavailable, parse failures).
        // [invalidateCache] resets this so user-driven retries (REG_ERROR /
        // SIM_ERROR OK button in BootRegistrationScreen) get a fresh shot.
        if (ussdAttempted) {
            val window = if (ussdLastFailureWasTransient) USSD_BACKOFF_TRANSIENT_MS
                         else USSD_BACKOFF_MS
            val age = System.currentTimeMillis() - ussdLastFailureMs
            if (age in 0 until window) {
                Log.d(TAG, "readViaUssd: in backoff window (${age}ms < ${window}ms, " +
                    "transient=$ussdLastFailureWasTransient)")
                return null
            }
        }

        // Pre-check: if Settings.Secure already has the number AND it's pinned
        // to the currently-inserted SIM, skip the USSD round-trip. Otherwise
        // we'd serve a stale number after a SIM swap (the persisted value
        // outlives the SIM that produced it).
        //
        // ICCID match logic:
        //   - no current ICCID readable      → trust the cache (don't second-guess)
        //   - no stored ICCID                → legacy install, pin lazily and trust this read
        //   - stored == current              → same SIM as last write, fast path
        //   - stored != current              → SIM was swapped, force fresh USSD
        try {
            val existing = Settings.Secure.getString(ctx.contentResolver, "device_phone_number")
            val hasNumber = !existing.isNullOrBlank() &&
                existing != "null" &&
                existing.any(Char::isDigit)
            if (hasNumber) {
                val storedIccid = Settings.Secure.getString(
                    ctx.contentResolver, KEY_DEVICE_PHONE_NUMBER_ICCID
                )
                val currentIccid = readCurrentIccid()
                when {
                    currentIccid == null -> {
                        // Can't read current SIM's ICCID. Don't override the
                        // cache on a hunch — fall back to historical behavior.
                        Log.d(TAG, "readViaUssd: current ICCID unreadable — trusting cache")
                        return null
                    }
                    storedIccid.isNullOrBlank() -> {
                        // Legacy install: Settings.Secure has the number from
                        // automated_configuration.sh or a pre-ICCID-tracking
                        // build. Pin the current ICCID alongside so the next
                        // SIM swap is detectable, and trust the cache for now.
                        Log.i(TAG, "readViaUssd: legacy cache — pinning ICCID=$currentIccid")
                        runSuWrite(
                            "settings put secure $KEY_DEVICE_PHONE_NUMBER_ICCID $currentIccid",
                            label = "$KEY_DEVICE_PHONE_NUMBER_ICCID (legacy pin)"
                        )
                        return null
                    }
                    storedIccid == currentIccid -> {
                        Log.d(TAG, "readViaUssd: Settings.Secure populated for current SIM — skipping USSD")
                        return null
                    }
                    else -> {
                        Log.i(TAG, "readViaUssd: SIM swap detected " +
                            "(stored=$storedIccid current=$currentIccid) — re-querying via USSD")
                        // Fall through to fire USSD; persistPhoneNumber below
                        // will overwrite Settings.Secure with the new pair.
                    }
                }
            }
        } catch (_: Exception) {
            // Read failure is non-fatal — fall through to USSD.
        }

        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm == null) {
            Log.d(TAG, "readViaUssd: no TelephonyManager")
            return null
        }

        // SIM gate — friendly skip on no-SIM / SIM-not-ready. Accept both READY
        // (5) and LOADED (10); some MediaTek builds settle on LOADED. See
        // [SIM_STATE_LOADED_INTERNAL] for why we don't use the framework constant.
        val simState = tm.simState
        if (simState != TelephonyManager.SIM_STATE_READY &&
            simState != SIM_STATE_LOADED_INTERNAL) {
            Log.i(TAG, "readViaUssd: SIM not ready (simState=$simState) — skipping")
            return null
        }

        // Service-state gate — USSD goes over the air to the carrier, so we
        // need an actual radio attachment. If voice isn't IN_SERVICE the
        // request will return USSD_RETURN_FAILURE anyway; cheaper to check
        // up front than burn the full USSD timeout.
        try {
            val ss = tm.serviceState
            val voiceReg = ss?.state ?: ServiceState.STATE_OUT_OF_SERVICE
            if (voiceReg != ServiceState.STATE_IN_SERVICE) {
                Log.i(TAG, "readViaUssd: not in service (voiceReg=$voiceReg) — skipping")
                return null
            }
        } catch (e: SecurityException) {
            // tm.serviceState requires READ_PHONE_STATE; if it's denied,
            // proceed anyway — sendUssdRequest will fail-fast if the modem
            // isn't ready and our timeout will catch it.
            Log.d(TAG, "readViaUssd: serviceState read denied — proceeding anyway")
        } catch (_: Exception) {
            // Some MediaTek builds throw on getServiceState(); same recovery.
        }

        // Permission gate. CALL_PHONE is a runtime permission on Android 6+;
        // if it's not granted, try a root self-grant (we have su on these
        // devices already — same path SimInfoReader uses). If that fails,
        // skip silently.
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "readViaUssd: CALL_PHONE not granted — attempting root grant")
            if (!grantCallPhoneViaRoot(ctx)) {
                Log.w(TAG, "readViaUssd: CALL_PHONE not granted and root grant failed — skipping")
                return null
            }
        }

        // Pin to the active data subscription so we hit the right SIM on
        // dual-SIM-capable builds (single-SIM devices return the same tm).
        val activeSub = SubscriptionManager.getDefaultDataSubscriptionId()
        val scoped = if (activeSub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            tm.createForSubscriptionId(activeSub)
        } else tm

        // Mark the attempt before firing — even if the dispatcher throws, we
        // want the backoff to engage so we don't loop on a permanent failure.
        ussdAttempted = true

        val latch = CountDownLatch(1)
        var parsed: String? = null
        // Track which callback fired so the post-await branching can tell
        // "failure callback fired (parsed is null because the carrier said
        // no)" apart from "success callback fired but parsing produced
        // nothing (real hard failure)". Without this, both paths land in
        // the same `parsed.isNullOrBlank()` branch and overwrite each
        // other's classification of the failure as transient vs hard.
        var failureCallbackFired = false
        var failureCallbackCode = -1
        // Main looper handler is fine — the callback just decrements the
        // latch; the caller's background thread is what blocks on await().
        val handler = Handler(Looper.getMainLooper())

        val cb = object : TelephonyManager.UssdResponseCallback() {
            override fun onReceiveUssdResponse(
                tm: TelephonyManager, request: String, response: CharSequence
            ) {
                Log.i(TAG, "readViaUssd: response received (len=${response.length})")
                val match = Regex("""\+?\d[\d\s\-()]{8,}""").find(response.toString())?.value
                if (match != null) {
                    val digits = match.filter { it.isDigit() || it == '+' }
                    parsed = formatE164(digits)
                } else {
                    Log.w(TAG, "readViaUssd: response had no parseable phone number")
                }
                latch.countDown()
            }

            override fun onReceiveUssdResponseFailed(
                tm: TelephonyManager, request: String, failureCode: Int
            ) {
                // Just record what happened — let the post-await code do the
                // logging and flag-setting in one place so we don't race
                // against ourselves with the parse-failed branch below.
                failureCallbackFired = true
                failureCallbackCode = failureCode
                latch.countDown()
            }
        }

        try {
            Log.i(TAG, "readViaUssd: firing sendUssdRequest('$USSD_SELF_NUMBER_CODE')")
            scoped.sendUssdRequest(USSD_SELF_NUMBER_CODE, cb, handler)
        } catch (e: SecurityException) {
            // Some OEM builds gate sendUssdRequest behind MODIFY_PHONE_STATE
            // (signature-only). If we hit this, USSD is unavailable on this
            // hardware — hard failure, long backoff.
            Log.w(TAG, "readViaUssd: SecurityException — ${e.message}")
            ussdLastFailureWasTransient = false
            ussdLastFailureMs = System.currentTimeMillis()
            return null
        } catch (e: Exception) {
            Log.w(TAG, "readViaUssd: ${e.javaClass.simpleName} — ${e.message}")
            ussdLastFailureWasTransient = false
            ussdLastFailureMs = System.currentTimeMillis()
            return null
        }

        val completedInTime = latch.await(USSD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!completedInTime) {
            // No callback within the timeout — typically means the modem
            // accepted the request but never delivered a response. Treat as
            // transient so the next BootRegistration retry tick can try again.
            Log.w(TAG, "readViaUssd: timed out after ${USSD_TIMEOUT_MS}ms with no callback")
            ussdLastFailureWasTransient = true
            ussdLastFailureMs = System.currentTimeMillis()
            return null
        }

        // Failure callback path. USSD_RETURN_FAILURE is "modem busy / not
        // yet ready" — almost always transient on cold-boot, especially in
        // the first 60-90s after boot. Other codes are hard failures.
        // Process this BEFORE the parsed-empty check below; otherwise that
        // branch would clobber our transient classification because parsed
        // is also null in the failure path.
        if (failureCallbackFired) {
            val codeName = when (failureCallbackCode) {
                TelephonyManager.USSD_RETURN_FAILURE -> "USSD_RETURN_FAILURE"
                TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL -> "USSD_ERROR_SERVICE_UNAVAIL"
                else -> "code=$failureCallbackCode"
            }
            Log.w(TAG, "readViaUssd: failed ($codeName) — carrier rejected or modem busy")
            ussdLastFailureWasTransient =
                failureCallbackCode == TelephonyManager.USSD_RETURN_FAILURE
            ussdLastFailureMs = System.currentTimeMillis()
            return null
        }

        val result = parsed
        if (result.isNullOrBlank()) {
            // Success callback fired but the response didn't contain a
            // parseable number. Could be a "wrong code" carrier message or
            // a malformed reply. Treat as hard — retrying with the same
            // code is unlikely to produce a different shape of response.
            Log.w(TAG, "readViaUssd: callback fired but no usable number")
            ussdLastFailureWasTransient = false
            ussdLastFailureMs = System.currentTimeMillis()
            return null
        }

        // Set the in-memory cache BEFORE releasing the @Synchronized lock so
        // any thread already queued at the lock sees the result via their
        // double-checked cache lookup on entry, rather than firing a
        // redundant USSD round-trip. (read() also sets cachedNumber on its
        // happy path; this is idempotent — same value, same volatile write.)
        cachedNumber = result

        // Persist to BOTH Settings.Secure AND content://telephony/siminfo so
        // the next read (this process or any other) hits the fast path without
        // firing USSD again. Writing only Settings.Secure leaves
        // SimInfoReader.readAll's primary path empty — its "fast" siminfo query
        // would still come back partial (number=) and the BootRegistrationScreen
        // retry loop would keep spinning until the carrier eventually OTA-pushes
        // MSISDN to the SIM (which can take 60+ seconds). Writing to siminfo as
        // well lights up readAll's fast path on the very next attempt.
        // Mirrors what automated_configuration.sh would have done.
        persistPhoneNumber(result)

        // NOTE: USSD's CSFB-to-GSM transition can leave the modem stuck on
        // 2G with a torn-down data PDP context. The recovery (toggling
        // airplane mode via [cycleAirplaneToReattachLte]) is invoked
        // PROACTIVELY by BootRegistrationScreen on every setup pass —
        // every phone pays the ~5-8s toggle cost so calls/SMS are
        // guaranteed to work after registration completes. We don't fire
        // it from here so the proactive caller controls UI state ("finishing
        // sim registration...") around the bounce. DeviceRegistrar still
        // calls it reactively as a safety net if its first HTTP attempt
        // fails — covers any path that bypasses BootRegistrationScreen
        // (e.g. background re-registration via scheduleOnBoot).

        // Successful USSD invalidates the existing su backoff — Settings.Secure
        // is now populated, so the next readViaSu call is going to hit, and we
        // want it to hit immediately rather than wait through any prior backoff.
        suFailureCount = 0
        suLastFailureMs = 0L

        return result
    }

    /**
     * Toggles airplane mode on for ~3s, then off, to force the modem to drop
     * all radio state and re-attach. Required as a post-USSD recovery on
     * TCL/MediaTek + T-Mobile units where USSD's CSFB to GSM leaves the modem
     * stuck on 2G with no usable data PDP context — the same stuck state also
     * breaks IMS registration, so calls/SMS would silently fail until the
     * next reboot or manual airplane-mode toggle without this recovery.
     *
     * Public — invoked from two places:
     *
     *   1. [com.offlineinc.dumbdownlauncher.ui.BootRegistrationScreen]
     *      PROACTIVELY on every setup pass, between the successful SIM read
     *      and the /register HTTP call. This is the primary caller. Every
     *      phone pays the ~7-10s toggle cost so calls/SMS are guaranteed to
     *      work after registration completes; the alternative ("only toggle
     *      when registration fails") leaves a window where registration
     *      completes successfully but the modem is still stuck on 2G with
     *      IMS down, which the user only discovers later when they try to
     *      make a call.
     *
     *   2. [com.offlineinc.dumbdownlauncher.registration.DeviceRegistrar]
     *      REACTIVELY when its first HTTP attempt fails with a network-level
     *      error. Safety net for code paths that bypass BootRegistrationScreen
     *      (e.g. the passive scheduleOnBoot re-registration), and for the
     *      rare case where the proactive bounce didn't fully restore service.
     *
     * Blocks for the full toggle cycle plus a bounded wait for the network to
     * come back as VALIDATED (i.e. Android has confirmed end-to-end IP +
     * DNS works, not just radio attachment). Worst-case ~20s; typical ~7-10s.
     *
     * Fails open: if any step times out or errors, we return and the caller
     * proceeds anyway. The downstream HTTP retries in DeviceRegistrar will
     * pick up the slack if the toggle didn't complete cleanly.
     */
    @JvmStatic
    fun cycleAirplaneToReattachLte(ctx: Context) {
        Log.i(TAG, "cycleAirplaneToReattachLte: dropping radio to clear post-USSD CSFB stuck-on-2G state")

        // Step 1: airplane on. Both writes are needed — the global setting
        // changes the system state, the broadcast wakes up the radio HAL.
        // Some MediaTek builds only respect one or the other.
        runSuWrite("settings put global airplane_mode_on 1", "airplane_mode_on=1")
        runSuWrite(
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true",
            "airplane broadcast (on)"
        )

        // Wait for the modem to fully drop. Bumped from 3s → 5s to give
        // the modem extra headroom — on slower MediaTek units 3s
        // occasionally caught the radio mid-detach, which left IMS in a
        // half-deregistered state and defeated the whole point of the
        // bounce. The extra 2s is paid on every setup but is well within
        // the FINISHING_SIM screen budget and worth it for guaranteed
        // post-bounce IMS re-registration.
        try { Thread.sleep(5_000L) } catch (_: InterruptedException) {}

        // Step 2: airplane off — same dual write.
        runSuWrite("settings put global airplane_mode_on 0", "airplane_mode_on=0")
        runSuWrite(
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false",
            "airplane broadcast (off)"
        )

        // Step 3: wait for end-to-end network connectivity to come back.
        // Using NET_CAPABILITY_VALIDATED (not just INTERNET) means we only
        // unblock once Android has actually confirmed DNS and IP work via
        // its captive-portal probe — exactly the signal we wished
        // NetworkUtils was using during normal boot.
        waitForValidatedNetwork(ctx, timeoutMs = 15_000L)

        Log.i(TAG, "cycleAirplaneToReattachLte: complete — LTE re-attach should be active")
    }

    /**
     * Blocks up to [timeoutMs] waiting for any network with both `INTERNET`
     * and `VALIDATED` capabilities. Returns silently in either case
     * (success or timeout); the caller's HTTP retries handle the timeout
     * case if the radio re-attach didn't finish in time.
     */
    private fun waitForValidatedNetwork(ctx: Context, timeoutMs: Long) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Log.w(TAG, "waitForValidatedNetwork: no ConnectivityManager")
            return
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        val latch = CountDownLatch(1)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "waitForValidatedNetwork: network available + validated")
                latch.countDown()
            }
        }

        try {
            cm.registerNetworkCallback(request, callback)
            val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!ok) {
                Log.w(TAG, "waitForValidatedNetwork: timed out after ${timeoutMs}ms — proceeding anyway")
            }
        } catch (e: Exception) {
            Log.w(TAG, "waitForValidatedNetwork: ${e.javaClass.simpleName} — ${e.message}")
        } finally {
            try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        }
    }

    /**
     * Grants `CALL_PHONE` to ourselves via `su -c "pm grant …"`. Mirrors the
     * pattern used in `UssdProbeActivity` and the rest of the codebase's `su`
     * use. Returns true iff the permission is reported granted after the call.
     */
    private fun grantCallPhoneViaRoot(ctx: Context): Boolean {
        val cmd = "pm grant ${ctx.packageName} android.permission.CALL_PHONE"
        return try {
            val proc = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            // 5s rather than 3s because the *first* su call after process
            // start on a cold-booted device can take 3-5s as Magisk warms up.
            // The previous 3s default tripped during real broken-state tests
            // and caused readViaUssd to skip the USSD path entirely for the
            // next ~17s while waiting for the next BootRegistration retry.
            if (!proc.waitFor(5_000, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "grantCallPhoneViaRoot: timed out after 5s")
                proc.destroyForcibly()
                return false
            }
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "grantCallPhoneViaRoot: ${e.javaClass.simpleName} — ${e.message}")
            false
        }
    }

    /**
     * Writes an E.164 phone number to BOTH:
     *   1. `Settings.Secure.device_phone_number` — read by [readViaSu] method 1
     *   2. `content://telephony/siminfo` column `number` — read by both
     *      [readViaSu] method 2 AND, more importantly, the fast path inside
     *      [com.offlineinc.dumbdownlauncher.registration.SimInfoReader.readAll].
     *      Without this write, readAll's "got all three from siminfo in one
     *      call" path keeps coming back partial (number=) and the
     *      BootRegistrationScreen retry loop spins until the carrier
     *      OTA-provisions MSISDN, which can take 60+ seconds.
     *
     * Mirrors the dual-write pattern in `automated_configuration.sh`. Each
     * write has its own short timeout; failures are logged but non-fatal —
     * the in-memory cache still serves this read, and the next cold boot
     * will fall through to USSD again.
     *
     * The `--where "sim_id>=0"` filter scopes the siminfo update to the
     * currently-inserted SIM only. This matches [SimInfoReader.SIMINFO_ACTIVE_WHERE]
     * and avoids overwriting historical rows for previously-removed SIMs (the
     * content provider keeps a row per SIM the phone has ever seen, with
     * `sim_id=-1` for retired ones).
     */
    private fun persistPhoneNumber(e164: String) {
        // Write 1: Settings.Secure
        runSuWrite(
            "settings put secure device_phone_number $e164",
            label = "Settings.Secure.device_phone_number"
        )
        // Write 2: telephony/siminfo number column, scoped to active SIM
        runSuWrite(
            "content update --uri content://telephony/siminfo " +
                "--bind number:s:$e164 --where \"sim_id>=0\"",
            label = "siminfo.number"
        )
        // Write 3: pin the cached number to the SIM that produced it. The
        // pre-check inside readViaUssd compares this against the active SIM's
        // ICCID on every cold-boot read; mismatch → SIM swap → force USSD
        // re-query. Skipped (with a warning) if we can't read the ICCID right
        // now — better to lose the swap-detection capability for one cycle
        // than to write a bogus pin value.
        val currentIccid = readCurrentIccid()
        if (currentIccid != null) {
            runSuWrite(
                "settings put secure $KEY_DEVICE_PHONE_NUMBER_ICCID $currentIccid",
                label = KEY_DEVICE_PHONE_NUMBER_ICCID
            )
        } else {
            Log.w(TAG, "persistPhoneNumber: ICCID unreadable — skipping ICCID pin (SIM swap " +
                "won't be detected on next read until a successful pin happens)")
        }
    }

    /**
     * Reads the ICCID of the currently-inserted SIM by querying the active
     * row in `content://telephony/siminfo`. Single `su` call — cheaper than
     * [SimInfoReader.readIccid] which falls through service calls and
     * getprop chains, and we don't need that exhaustive search here because
     * Settings.Secure already vouches that *some* SIM exists.
     *
     * Mirrors the active-SIM filter used elsewhere in this class so the
     * post-SIM-swap row (sim_id=-1) is excluded.
     *
     * Uses a generous 5s timeout (vs. the [runSuCommand] default of 1500ms)
     * because content-provider queries on the TCL/MediaTek targets routinely
     * take 1-2s, especially on cold boot before the binder cache is warm.
     * Falling under the default timeout gave a false "current ICCID
     * unreadable — trusting cache" path that defeated SIM-swap detection.
     * 5s is well within the BootRegistration retry budget and only fires
     * on the first read after process start.
     */
    private fun readCurrentIccid(): String? {
        val raw = runSuCommand(
            cmd = "content query --uri content://telephony/siminfo " +
                "--projection icc_id --where \"sim_id>=0\"",
            timeoutMs = 5_000L
        ) ?: return null
        val match = Regex("""icc_id=([^,}\s]+)""").find(raw) ?: return null
        val v = match.groupValues[1].trim().trim('\r', '\n')
        return if (v.isBlank() || v.equals("NULL", ignoreCase = true) || v == "null") null else v
    }

    /**
     * Helper for the persist writes — runs `su -c <cmd>` and logs the
     * outcome. Returns nothing because the caller doesn't care: the
     * in-memory cache serves the current read, and worst case is another
     * USSD round-trip on the next cold boot.
     *
     * Default timeout is 5s rather than [runSuCommand]'s 1.5s default
     * because the persist writes hit `content update` against the telephony
     * provider, which routinely takes 1-3s on the TCL/MediaTek targets and
     * occasionally pushes past 2s on cold boot. 2s timeouts produced false
     * "persist[…]: timed out" warnings even when the write was about to
     * complete; 5s comfortably covers the observed range without making the
     * happy path feel slow.
     */
    private fun runSuWrite(cmd: String, label: String, timeoutMs: Long = 5_000L) {
        try {
            val proc = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "persist[$label]: timed out after ${timeoutMs}ms")
                proc.destroyForcibly()
                return
            }
            if (proc.exitValue() == 0) {
                Log.i(TAG, "persist[$label]: ok")
            } else {
                Log.w(TAG, "persist[$label]: exit=${proc.exitValue()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "persist[$label]: ${e.javaClass.simpleName} — ${e.message}")
        }
    }

    private fun readViaSu(): String? {
        // Skip the su path entirely if we're inside the backoff window or have
        // blown past the max-attempts cap. Callers fall through to the (fast)
        // SubscriptionManager / TelephonyManager paths in read().
        if (suBackoffActive()) {
            return null
        }

        // Method 1: Settings.Secure (written by setup script via #686# USSD query)
        try {
            val setting = runSuCommand("settings get secure device_phone_number")
            val num = setting?.trim()
            if (!num.isNullOrBlank() && num != "null" && num.any { it.isDigit() }) {
                Log.d(TAG, "Root fallback (Settings.Secure) got number")
                suFailureCount = 0
                return num.replace("-", "")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Root fallback (Settings.Secure) failed", e)
        }

        // Method 2: telephony siminfo (also written by setup script)
        //
        // The `sim_id>=0` filter pins the query to the currently-inserted SIM.
        // Without it, the content provider returns one row per SIM the phone
        // has ever seen — including sim_id=-1 rows for SIMs that have been
        // removed — and the first-match regex below would happily return a
        // previous SIM's number on a SIM-swapped phone. This mirrors the same
        // filter SimInfoReader.SIMINFO_ACTIVE_WHERE applies for the same reason.
        // `>= 0` (not `!= -1`) is used because shell-quoting `!` through
        // `su -c '…'` is fragile across zsh / adb / sh / the Android `content`
        // tool; `>= 0` is equivalent and survives every layer unescaped.
        try {
            val cp = runSuCommand(
                "content query --uri content://telephony/siminfo " +
                    "--projection number --where \"sim_id>=0\""
            )
            if (cp != null) {
                val match = Regex("""number=([^,}\s]+)""").find(cp)
                val num = match?.groupValues?.get(1)?.trim()
                if (!num.isNullOrBlank() && num != "NULL" && num.any { it.isDigit() }) {
                    Log.d(TAG, "Root fallback (siminfo) got number")
                    suFailureCount = 0
                    return num.replace("-", "")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Root fallback (siminfo) failed", e)
        }

        // Both methods failed — record for the exponential-backoff window.
        suLastFailureMs = System.currentTimeMillis()
        val next = (suFailureCount + 1).coerceAtMost(SU_MAX_ATTEMPTS)
        suFailureCount = next
        if (next >= SU_MAX_ATTEMPTS) {
            Log.w(TAG, "su fallbacks exhausted after $SU_MAX_ATTEMPTS attempts — disabling for this process")
        }
        return null
    }

    /**
     * Runs an `su -c` command with a hard timeout so a hung `su` daemon
     * (e.g. SIM not ready, Magisk slow to respond) can't block the caller
     * indefinitely. Without this, repeated calls on the main thread caused
     * the launcher ANR ("launcher is not responding").
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
            val output = BufferedReader(InputStreamReader(proc.inputStream))
                .use { it.readText() }
            if (proc.exitValue() == 0 && output.isNotBlank()) output else null
        } catch (e: Exception) {
            Log.w(TAG, "runSuCommand($cmd) failed: ${e.message}")
            try { proc?.destroyForcibly() } catch (_: Exception) {}
            null
        }
    }

    fun formatE164(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            raw.startsWith("+") -> "+$digits"
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            else -> "+$digits"
        }
    }
}
