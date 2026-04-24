package com.offlineinc.dumbdownlauncher.launcher

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
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
 * 1. SubscriptionManager (Android 13+)
 * 2. TelephonyManager.getLine1Number (deprecated but still works on older builds)
 * 3. Root fallback: Settings.Secure (written by setup script via #686# USSD query)
 * 4. Root fallback: content://telephony/siminfo (works on TCL/MediaTek)
 *
 * Returns (phoneNumber, errorMessage) — one will be null.
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

    /** Drop the cached number — for tests, or if the caller needs a forced re-read. */
    @JvmStatic
    fun invalidateCache() {
        cachedNumber = null
        // An explicit invalidation usually means the caller has reason to
        // believe the SIM is now ready (e.g. user tapped retry, or boot
        // registration just completed), so give the su path a fresh shot.
        suFailureCount = 0
        suLastFailureMs = 0L
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
            // 1. Setup-script-written values (most reliable on MediaTek flip phones):
            //    automated_configuration.sh writes the number to Settings.Secure and
            //    content://telephony/siminfo during provisioning, so check these first.
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
        try {
            val cp = runSuCommand("content query --uri content://telephony/siminfo --projection number")
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
