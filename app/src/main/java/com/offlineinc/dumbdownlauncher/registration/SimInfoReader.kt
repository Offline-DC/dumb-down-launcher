package com.offlineinc.dumbdownlauncher.registration

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import com.offlineinc.dumbdownlauncher.launcher.PhoneNumberReader
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "SimInfoReader"

/**
 * Reads the IMEI and SIM ICCID used by the device_registration.sh workflow
 * in the dumb-phone-configuration repo.
 *
 * IMEI resolution order:
 *   `service call iphonesubinfo` (codes 1, 3, 4; ± `i32 0` subscriber-id)
 *     →  getprop ro.ril.oem.imei / .imei1 / persist.radio.imei /
 *        ril.gsm.imei
 *     →  ro.serialno (only if IMEI-shaped — some OEMs put the IMEI here)
 *
 * ICCID resolution order:
 *   `service call iphonesubinfo` 12 →  11 →  11 i32 0   (see [ICCID_ATTEMPTS])
 *     →  content://telephony/siminfo `icc_id` column
 *
 * The service-call route is the load-bearing one — `parseServiceCall`
 * invokes the binder via `su 2000 -c` so it runs in u:r:shell:s0 SELinux
 * context (the only context where iphonesubinfo returns real data on
 * the TCL/MediaTek builds we target).
 *
 * Two routes have been deliberately removed from the cascades:
 *
 *   1. TelephonyManager.imei / .simSerialNumber. They require
 *      READ_PRIVILEGED_PHONE_STATE on Android 10+. We tried to grant
 *      that via `pm grant` from a root shell, but on the target hardware
 *      the package manager exits 0 and silently leaves the runtime state
 *      DENIED — see the "FAILED (cmd error + still denied)" log line
 *      from the PhoneStatePermissionGranter that used to live here.
 *      Net effect: the SDK call always threw SecurityException and the
 *      cascade always fell through. Removed to cut startup cost and
 *      noise.
 *
 *   2. content://telephony/siminfo `imsi` column as a source for the IMEI.
 *      That column is actually the IMSI on TCL/MediaTek builds. The
 *      legacy shell script in dumb-phone-configuration assumed it held
 *      the IMEI and our previous code mirrored that, so the cascade was
 *      returning a value like `310240381195533` (T-Mobile MCC+MNC+MSIN)
 *      as the IMEI. See the cache v1→v2 history in [CURRENT_CACHE_VERSION].
 */
object SimInfoReader {

    // ─── IMEI cache ──────────────────────────────────────────────────────
    //
    // The IMEI is a hardware identifier: it does not change for the lifetime
    // of a device, even across SIM swaps and factory resets of the launcher's
    // own data (it survives until the OS image is wiped). Once we've
    // successfully read it via the cascade below — TelephonyManager →
    // `service call iphonesubinfo` → multiple getprops → `content query
    // siminfo` → `ro.serialno` — there is no value in re-running that cascade
    // on every launcher start. Each pass spawns up to ~12 `su -c` subprocesses
    // through Magisk, which on low-RAM MediaTek/TCL flip phones is enough to
    // trip the low-memory killer and freeze system_server for 10–20 seconds
    // (see docs/bug-imei-fallback-thrashes-system-server.md).
    //
    // So: after the first cascade returns a value matching [IMEI_REGEX] we
    // persist it here and short-circuit every subsequent [readImei] call.
    // The cache is intentionally separate from
    // [com.offlineinc.dumbdownlauncher.registration.DeviceRegistrar]'s
    // "registered_at" prefs row — that one means "the IMEI the backend has
    // confirmed", which is a stricter condition than "the IMEI we managed to
    // read from hardware." Callers that need the latter (e.g. the boot poller
    // in DumbDownApp.populateOpenBubblesActivationCode, which runs before
    // registration succeeds) get it from here.
    private const val PREFS = "sim_info_cache"
    private const val KEY_CACHED_IMEI = "cached_imei"
    private const val KEY_CACHE_VERSION = "cache_version"

    // Bump this constant whenever the *meaning* of [KEY_CACHED_IMEI] changes
    // in a way that makes already-persisted values incorrect. Each install
    // tracks the version under which its cache was written; if it doesn't
    // match [CURRENT_CACHE_VERSION], [readCachedImei] discards the old
    // value on first read.
    //
    // History:
    //   v1 — initial cache. Accepted the siminfo `imsi` column as IMEI
    //        because the surrounding cascade did. On TCL/MediaTek builds
    //        that column actually contains the IMSI, so v1 caches got the
    //        device's IMSI saved under the IMEI key and re-served it on
    //        every subsequent read. See the diagnostic in
    //        scripts/imei_probe.sh — `service call iphonesubinfo 1`
    //        returns the real IMEI on these devices; the siminfo route
    //        was always wrong.
    //   v2 — siminfo `imsi` column is no longer read for IMEI in either
    //        [readAll]'s fast path or [performReadImei]. Only sources
    //        backed by the modem or vendor properties feed this cache
    //        now, so a v2 entry is genuinely an IMEI.
    private const val CURRENT_CACHE_VERSION = 2

    private fun readCachedImei(ctx: Context): String? {
        val prefs = ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getInt(KEY_CACHE_VERSION, 0)
        if (version < CURRENT_CACHE_VERSION) {
            // Old install — the value under KEY_CACHED_IMEI may be an IMSI
            // (v1 bug). Drop it and stamp the new version so we don't keep
            // re-discarding on every read. The next successful cascade
            // will populate a correct value.
            Log.i(TAG, "cache version $version < $CURRENT_CACHE_VERSION — clearing stale IMEI entry")
            prefs.edit()
                .remove(KEY_CACHED_IMEI)
                .putInt(KEY_CACHE_VERSION, CURRENT_CACHE_VERSION)
                .apply()
            return null
        }
        return prefs.getString(KEY_CACHED_IMEI, null)
            ?.takeIf { it.isNotBlank() && IMEI_REGEX.matches(it) }
    }

    private fun writeCachedImei(ctx: Context, value: String) {
        if (!IMEI_REGEX.matches(value)) return
        ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CACHED_IMEI, value)
            .putInt(KEY_CACHE_VERSION, CURRENT_CACHE_VERSION)
            .apply()
    }

    /** IMEI is 14–17 digits (15 is the standard; IMEISV adds 2, occasionally 14 appears). */
    private val IMEI_REGEX = Regex("^\\d{14,17}$")

    /** ICCID is 18–22 digits, sometimes with a trailing 'F' pad nibble. */
    private val ICCID_REGEX = Regex("^\\d{18,22}F?$", RegexOption.IGNORE_CASE)

    /**
     * service call transaction codes we'll try for IMEI. The codes differ
     * across Android versions and vendor builds — we try a few:
     *   - "1"   : legacy getDeviceId (pre-O)
     *   - "3"   : getImei on some AOSP builds
     *   - "4"   : getImei on some MediaTek builds
     * Some builds also require an i32 subscriber-id arg (usually 0).
     */
    private val IMEI_CALLS = listOf("1", "3", "4")

    /**
     * Ordered (code, withSubId) attempts for ICCID. Note that `service call 12`
     * (subId=false) is the load-bearing one on the TCL/MediaTek hardware we
     * target — it has succeeded on every observed device. Codes 11 (both subId
     * variants) are kept as fallbacks for other vendor builds; `12 i32 0`
     * (subId=true) is omitted because no observed device that has 12 working
     * at all needs the subId variant.
     *
     * Order matters: the previous order was 11(false) → 11(true) → 12(false),
     * which wasted ~4s of timeouts on every cold-boot ICCID read before the
     * working call fired. Putting 12 first cuts cold-boot ICCID reads from
     * ~4.5s to ~0.6s on this hardware. Other vendors that need 11 still get
     * it as a fallback at the cost of one extra timeout.
     */
    private val ICCID_ATTEMPTS: List<Pair<String, Boolean>> = listOf(
        "12" to false,
        "11" to false,
        "11" to true,
    )

    /**
     * `sim_id` is the slot index Android's SubscriptionManager keeps on every
     * row in `content://telephony/siminfo`. It's `>= 0` for the SIM currently
     * in a slot and `-1` for historical rows whose SIM has been removed.
     *
     * We filter on it because the content provider keeps one row per SIM the
     * phone has ever seen, and without a filter the first-match regex in
     * [queryField] will happily return the previous SIM's ICCID on a
     * SIM-swapped phone. That bug caused post-swap registrations to POST
     * the old ICCID and broke the Gigs-tab IMEI join (phones.sim_number
     * ≠ gigs_subscriptions.iccid).
     *
     * `>= 0` is used instead of `!= -1` because shell-quoting `!` through
     * `su -c '…'` is fragile across zsh / adb / sh / the Android `content`
     * tool; `>= 0` is equivalent and survives every layer unescaped.
     */
    private const val SIMINFO_ACTIVE_WHERE = "sim_id>=0"

    /**
     * Build a `content query` command against `content://telephony/siminfo`
     * that returns only rows for a currently-inserted SIM. [projection] is a
     * comma-separated list of column names (e.g. `"imsi,icc_id,number"`).
     */
    private fun siminfoQuery(projection: String): String =
        "content query --uri content://telephony/siminfo " +
            "--projection $projection --where \"$SIMINFO_ACTIVE_WHERE\""

    /**
     * Data class for the combined SIM info read — IMEI, ICCID, and phone number
     * from a single `content://telephony/siminfo` query (one root shell call).
     */
    data class SimInfo(val imei: String?, val iccid: String?, val phoneNumber: String?)

    /**
     * Reads IMEI, ICCID, and phone number. Each component goes through
     * its own dedicated cascade:
     *
     *   IMEI  → [readImei]               (cache hit, or service call cascade)
     *   ICCID → [readIccid]              (service call 11/12, then siminfo icc_id)
     *   Phone → [PhoneNumberReader.read] (USSD #686# / Settings.Secure / etc.)
     *
     * Previously this method had a "fast path" that batched ICCID + phone
     * into a single `content query` against `content://telephony/siminfo`
     * for the `icc_id,number` projection. That was useful back when we
     * also wrote `siminfo.number` from [PhoneNumberReader.persistPhoneNumber]
     * — one shell call could populate both fields. We no longer write
     * that column (it's a leftover from the legacy setup-script flow),
     * the carrier OTA doesn't populate it reliably on our target SIMs,
     * and the query was hitting [SU_TIMEOUT_MS] on cold boot ~every
     * time. So now we just delegate to the per-field cascades, each of
     * which handles its own timeout, fallbacks, and caching.
     */
    fun readAll(ctx: Context): SimInfo {
        // Gate: don't try to read SIM-bound values when no SIM is present.
        // The siminfo content provider caches values from the last inserted
        // SIM even after it's removed, so without this check downstream
        // callers (DeviceRegistrar in particular) would re-register with
        // stale identifiers.
        if (!isSimReady(ctx)) {
            Log.d(TAG, "readAll: SIM not ready — returning empty")
            return SimInfo(null, null, null)
        }

        val imei = readImei(ctx)
        val iccid = readIccid(ctx)
        // PhoneNumberReader tries Settings.Secure, USSD #686#, SubscriptionManager,
        // and TelephonyManager.getLine1Number — handles its own caching and
        // SIM-swap detection internally.
        val (phone, _) = PhoneNumberReader.read(ctx)

        Log.i(TAG, "readAll: imei=${imei != null} iccid=${iccid != null} phone=${phone != null}")
        return SimInfo(imei, iccid, phone)
    }

    /**
     * Returns the device IMEI (or serial as last resort) or null if none available.
     *
     * The first successful read is persisted via [writeCachedImei] and every
     * subsequent call short-circuits on the cache. This is the load-bearing
     * fix for the system_server thrash described in
     * docs/bug-imei-fallback-thrashes-system-server.md — without it the
     * cascade below runs once per launcher start, spawning ~12 `su` subprocesses
     * each time.
     */
    fun readImei(ctx: Context): String? {
        readCachedImei(ctx)?.let {
            Log.d(TAG, "readImei: cache hit ($it)")
            return it
        }
        return performReadImei(ctx)?.also { writeCachedImei(ctx, it) }
    }

    private fun performReadImei(ctx: Context): String? {
        Log.d(TAG, "performReadImei: cascade start")

        // 1. service call iphonesubinfo <code> [ i32 0 ] — this is the
        //    confirmed-working path on the target TCL/MediaTek hardware.
        //    `parseServiceCall` runs the command via `su 2000` first
        //    (shell:s0 SELinux context) and falls back to `su` (magisk:s0)
        //    if that's not honored.
        for (code in IMEI_CALLS) {
            for (withSubId in booleanArrayOf(false, true)) {
                val parsed = parseServiceCall(code, withSubId = withSubId)
                if (parsed == null) {
                    Log.d(TAG, "performReadImei: step 1 (service call $code, subId=$withSubId) → null/filtered")
                } else if (IMEI_REGEX.matches(parsed)) {
                    Log.d(TAG, "performReadImei: step 1 (service call $code, subId=$withSubId) → match")
                    return parsed
                } else {
                    // Truncate to avoid spamming the log with junk parcels;
                    // first 40 chars is enough to recognize what came back.
                    Log.d(TAG, "performReadImei: step 1 (service call $code, subId=$withSubId) → shape mismatch: ${parsed.take(40)}")
                }
            }
        }

        // (Previously step 2: TelephonyManager.imei. Removed because on
        // the rooted TCL/MediaTek hardware we target, the SDK call
        // requires READ_PRIVILEGED_PHONE_STATE which `pm grant` can't
        // actually deliver — the package manager exits 0 but the
        // runtime permission state stays DENIED. See the
        // "FAILED (cmd error + still denied)" log from the previous
        // PhoneStatePermissionGranter run. The service-call route
        // above is doing all the work in practice, so the TM step was
        // just adding startup cost and a misleading "denied" log line
        // every boot.)

        // 2. Common IMEI getprops seen on MediaTek/TCL builds. These are
        //    empty on the TCL 4058R per scripts/imei_probe.sh; included
        //    for the OEMs (Samsung, MIUI, older Mediatek) where they work.
        val imeiProps = listOf(
            "ro.ril.oem.imei",
            "ro.ril.oem.imei1",
            "persist.radio.imei",
            "ril.gsm.imei",
        )
        for (p in imeiProps) {
            val v = runSu("getprop $p")?.trim()
            if (v.isNullOrBlank()) {
                Log.d(TAG, "performReadImei: step 2 (getprop $p) → empty")
            } else if (IMEI_REGEX.matches(v)) {
                Log.d(TAG, "performReadImei: step 2 (getprop $p) → match")
                return v
            } else {
                Log.d(TAG, "performReadImei: step 2 (getprop $p) → shape mismatch: ${v.take(40)}")
            }
        }

        // (Previously step 4: content://telephony/siminfo `imsi` column.
        // Removed because that column is the IMSI on TCL/MediaTek builds,
        // not the IMEI — see the cache v1→v2 history in
        // [CURRENT_CACHE_VERSION].)

        // 3. ro.serialno — hardware serial as last resort, but ONLY when
        //    it's IMEI-shaped (15 digits). On many OEM builds the serial
        //    IS the IMEI; on others (the TCL 4058R for instance) it's a
        //    base32-looking hardware serial like "8PUSB6PV59EEIBDE" that
        //    must NOT be returned as the IMEI — the previous unconditional
        //    return here was the source of the bug where DeviceRegistrar
        //    PUT requests went out with `imei=8PUSB6PV59EEIBDE` and the
        //    server kept them with a garbage identifier. Returning null
        //    instead lets BootRegistrationScreen retry and eventually
        //    show SIM_ERROR, which is the correct UX.
        val serial = runSu("getprop ro.serialno")?.trim()
        if (serial != null && IMEI_REGEX.matches(serial)) {
            Log.d(TAG, "performReadImei: step 3 (ro.serialno) → IMEI-shaped match")
            return serial
        }
        Log.d(TAG, "performReadImei: step 3 (ro.serialno) → ${serial ?: "null"} (not IMEI-shaped, skipping)")

        Log.w(TAG, "performReadImei: cascade exhausted — returning null")
        return null
    }

    /**
     * Returns the SIM ICCID or null if not present.
     *
     * Resolution order mirrors [performReadImei] — try the modem-backed
     * service-call path first (the confirmed-working route on TCL/MediaTek
     * via the `su 2000` shell-context wrapper inside [parseServiceCall]),
     * then the siminfo content provider as a last resort.
     */
    fun readIccid(ctx: Context): String? {
        Log.d(TAG, "readIccid: cascade start")

        // 1. service call iphonesubinfo <code> [ i32 0 ] — codes 11 and 12
        //    are the ICCID slots in the iphonesubinfo binder. parseServiceCall
        //    runs via `su 2000` (shell:s0 SELinux context) so it works on
        //    builds where the binder rejects calls from magisk:s0.
        //    Attempt order is defined by [ICCID_ATTEMPTS] — 12(false) first
        //    on TCL/MediaTek, then 11(false)/11(true) as cross-vendor fallback.
        for ((code, withSubId) in ICCID_ATTEMPTS) {
            val parsed = parseServiceCall(code, withSubId = withSubId)
            if (parsed == null) {
                Log.d(TAG, "readIccid: step 1 (service call $code, subId=$withSubId) → null/filtered")
            } else if (ICCID_REGEX.matches(parsed)) {
                Log.d(TAG, "readIccid: step 1 (service call $code, subId=$withSubId) → match")
                return parsed
            } else {
                Log.d(TAG, "readIccid: step 1 (service call $code, subId=$withSubId) → shape mismatch: ${parsed.take(40)}")
            }
        }

        // (Previously step 2: TelephonyManager.simSerialNumber. Removed
        // for the same reason as the analogous step in [performReadImei]
        // — it requires READ_PRIVILEGED_PHONE_STATE which `pm grant`
        // can't deliver on this hardware, and the service-call route is
        // doing the real work.)

        // 2. content://telephony/siminfo icc_id — last-ditch fallback.
        //    The icc_id column is the canonical name for ICCID on Android,
        //    so unlike the imsi-as-IMEI bug we fixed earlier, this one is
        //    safe to trust under the strict ICCID_REGEX filter.
        val viaSiminfo = queryField("icc_id")
        if (viaSiminfo != null && ICCID_REGEX.matches(viaSiminfo)) {
            Log.d(TAG, "readIccid: step 2 (siminfo icc_id) → match")
            return viaSiminfo
        }
        Log.d(TAG, "readIccid: step 2 (siminfo icc_id) → ${viaSiminfo ?: "null"} (not ICCID-shaped, skipping)")

        Log.w(TAG, "readIccid: cascade exhausted — returning null")
        return null
    }

    /** True if a SIM appears to be present and ready. */
    fun isSimReady(ctx: Context): Boolean {
        return try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val state = tm?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN
            state == TelephonyManager.SIM_STATE_READY ||
                // Some MediaTek builds never leave SIM_STATE_UNKNOWN even with a
                // working SIM — fall back to "we can read an ICCID" as proof.
                (state == TelephonyManager.SIM_STATE_UNKNOWN && !readIccid(ctx).isNullOrBlank())
        } catch (e: Exception) {
            !readIccid(ctx).isNullOrBlank()
        }
    }

    /**
     * Mirrors parse_service_call() in device_registration.sh — pulls the
     * printable chars out of each quoted parcel line and strips padding.
     *
     * Some builds (API 29+ / MediaTek) require a subscriber-id int arg;
     * pass `withSubId = true` to append `i32 0`.
     *
     * Rejects output that looks like a binder error (e.g. "Attempt to get
     * length of null array", "java.lang.*", "Exception"), which would
     * otherwise slip past as a bogus "IMEI".
     */
    private fun parseServiceCall(code: String, withSubId: Boolean = false): String? {
        val cmd = if (withSubId) "service call iphonesubinfo $code i32 0"
                  else "service call iphonesubinfo $code"

        // Try shell UID first. On TCL/MediaTek the iphonesubinfo binder
        // only returns real data from u:r:shell:s0 context — calling
        // through `su -c` (magisk:s0) gets back a "null array" error
        // parcel. See [runAsShell]'s docstring for the diagnostic.
        // Fall back to magisk root only if the shell call returned
        // nothing usable, since on some builds Magisk's UID drop may
        // not be available.
        return extractIdFromServiceCallOutput(runAsShell(cmd))
            ?: extractIdFromServiceCallOutput(runSu(cmd))
    }

    /**
     * Pull a digit string out of a `service call` parcel dump like:
     *
     *     Result: Parcel(
     *       0x00000000: 00000000 0000000f 00310030 00310036 '........0.1.6.1.'
     *       0x00000010: 00330034 00300030 00330032 00380033 '4.3.0.0.2.3.3.8.'
     *       0x00000020: 00340036 00000038                   '6.4.8...        ')
     *
     * Algorithm: for each line, take the contents of the first single-
     * quoted region (that's the ASCII rendering of the bytes — UTF-16LE
     * digits with 0x00 high-bytes shown as `.`), concatenate across
     * lines, then strip dots and whitespace. Result for the parcel
     * above: "016143002338648".
     *
     * Returns null for empty input or parcels that look like error
     * messages (length-zero return, exceptions, permission denials).
     * The error parcels are recognizable because the error text is
     * stored as UTF-16 in the parcel's char[] payload, which when
     * dot-stripped concatenates to phrases like
     * "Attempttogetlengthofnullarray".
     */
    private fun extractIdFromServiceCallOutput(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        val sb = StringBuilder()
        for (line in raw.lineSequence()) {
            val parts = line.split("'")
            // odd-indexed tokens are the quoted ASCII payloads
            var i = 1
            while (i < parts.size) {
                sb.append(parts[i])
                i += 2
            }
        }
        val cleaned = sb.toString().replace(".", "").replace(Regex("\\s"), "")
        if (cleaned.isBlank()) return null

        // Reject error parcels. Note: the previous version of this check
        // looked for "Attempt to" in the *raw* output, but the raw output
        // shows the error text as `'A.t.t.e.m.p.t. .t.o. ...'` (UTF-16
        // with dot separators), so the substring never matched. We do
        // the check against the dot-stripped cleaned string instead, which
        // produces "Attempttogetlengthofnullarray" — that we can grep for.
        val errSignatures = listOf(
            "Attempttoget",            // "Attempt to get length of null array"
            "Exception",
            "javalang",
            "PermissionDenial",
            "SecurityException",
        )
        if (errSignatures.any { cleaned.contains(it, ignoreCase = true) }) {
            return null
        }
        return cleaned
    }

    /**
     * Mirrors get_siminfo_field() in device_registration.sh. Uses the same
     * `siminfo` active-SIM filter as [readAll] via [siminfoQuery].
     */
    private fun queryField(field: String): String? {
        val raw = runSu(siminfoQuery(field)) ?: return null
        val match = Regex("""$field=([^,}\s]+)""").find(raw) ?: return null
        val v = match.groupValues[1].trim().trim('\r', '\n')
        return if (v.isBlank() || v.equals("NULL", ignoreCase = true) || v == "null") null else v
    }

    /**
     * Upper bound for a single `su -c <cmd>` invocation. The commands we run
     * here (`getprop`, `service call`, `content query`) all return in well
     * under a second on a healthy device; the only reason a call would
     * exceed this is Magisk thrashing under memory pressure or its root
     * server being mid-respawn (see
     * docs/bug-imei-fallback-thrashes-system-server.md for the full chain).
     *
     * Without this bound the previous implementation called `proc.waitFor()`
     * with no timeout, so a stuck Magisk daemon could block the calling
     * thread indefinitely while the cascade ran to completion. With it, each
     * stuck call is forcibly killed after [SU_TIMEOUT_MS] and the cascade
     * keeps moving — worst-case wall-clock for the full IMEI cascade is now
     * roughly (num su calls) × [SU_TIMEOUT_MS] regardless of Magisk state.
     */
    private const val SU_TIMEOUT_MS: Long = 2500L

    /**
     * Runs [cmd] as Magisk root (u:r:magisk:s0 SELinux context). This is
     * the default su mode and works for getprop, content query, and the
     * filesystem. Avoid it for [parseServiceCall] — see [runAsShell].
     */
    private fun runSu(cmd: String): String? =
        runProcessWithTimeout(arrayOf("su", "-c", cmd), label = "su -c", cmd = cmd)

    /**
     * Runs [cmd] as the shell user (UID 2000) by asking Magisk to elevate
     * and then drop to that UID before exec. SELinux transitions to
     * u:r:shell:s0 because that's the domain attached to UID 2000 — the
     * same context `adb shell <cmd>` runs under.
     *
     * Use this for `service call iphonesubinfo`: on TCL/MediaTek builds
     * (and likely others), the iphonesubinfo binder returns
     *   "Attempt to get length of null array"
     * when the caller is in u:r:magisk:s0, but returns the real IMEI/
     * ICCID parcel when the caller is in u:r:shell:s0. The verifiable
     * test: `adb shell 'service call iphonesubinfo 1'` works, but
     * `adb shell 'su -c "service call iphonesubinfo 1"'` returns the
     * null-array error. See scripts/imei_probe.sh for the diagnostic
     * that nailed this down.
     */
    private fun runAsShell(cmd: String): String? =
        runProcessWithTimeout(arrayOf("su", "2000", "-c", cmd), label = "su 2000", cmd = cmd)

    /**
     * Shared implementation behind [runSu] and [runAsShell].
     *
     * We can't use Process.waitFor(long, TimeUnit) — that overload was
     * added to android.lang.Process in API 26 and our minSdk is 24.
     * Instead, spawn a daemon thread that does the blocking read +
     * waitFor, then [Thread.join] it with [SU_TIMEOUT_MS]. If the join
     * returns before the thread finishes, we hit the deadline and we
     * destroyForcibly() the subprocess.
     *
     * @param argv full process argument list, e.g. `["su", "-c", "getprop foo"]`
     * @param label short string for log lines (so a stuck call shows
     *              which su mode was used)
     * @param cmd   the command itself, for log readability
     */
    private fun runProcessWithTimeout(argv: Array<String>, label: String, cmd: String): String? {
        var proc: Process? = null
        return try {
            proc = ProcessBuilder(*argv)
                .redirectErrorStream(true)
                .start()

            // The output buffer is a single-element array because Kotlin's
            // captured-var rules don't let us mutate a `var` from a lambda
            // passed to Thread {} (the lambda boxes the reference). An
            // array slot is the idiomatic workaround.
            val outputHolder = arrayOfNulls<String>(1)
            val procRef = proc
            val worker = Thread {
                try {
                    outputHolder[0] = BufferedReader(InputStreamReader(procRef.inputStream))
                        .use { it.readText() }
                    procRef.waitFor()
                } catch (_: Exception) {
                    // Reader / waitFor interrupted (likely because we
                    // destroyForcibly'd the process on timeout). Nothing
                    // to surface — the caller will see a null result.
                }
            }.apply {
                isDaemon = true
                name = "SimInfoReader-$label"
                start()
            }

            worker.join(SU_TIMEOUT_MS)
            if (worker.isAlive) {
                Log.w(TAG, "$label($cmd) timed out after ${SU_TIMEOUT_MS}ms — destroying")
                // destroyForcibly() sends SIGKILL; the subprocess (and its
                // su -> root-shell chain) goes away even if the JVM Process
                // object would otherwise leak it. Without this, a timed-out
                // process can sit around indefinitely chewing memory we
                // don't have to spare.
                proc.destroyForcibly()
                worker.interrupt()
                return null
            }

            val exit = try { proc.exitValue() } catch (_: IllegalThreadStateException) { return null }
            val output = outputHolder[0]
            if (exit == 0 && !output.isNullOrBlank()) output else null
        } catch (e: Exception) {
            // Force-kill on any unexpected throw too, so we don't leak the
            // child process if e.g. ProcessBuilder.start() itself fails
            // mid-launch.
            try { proc?.destroyForcibly() } catch (_: Exception) {}
            Log.d(TAG, "$label($cmd) failed: ${e.message}")
            null
        }
    }
}
