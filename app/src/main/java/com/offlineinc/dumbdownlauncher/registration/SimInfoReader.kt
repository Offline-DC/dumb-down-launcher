package com.offlineinc.dumbdownlauncher.registration

import android.content.Context
import android.os.Build
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
 * Mirrors the ordering in device_registration.sh:
 *   IMEI:  `service call iphonesubinfo 1`  →  siminfo.imsi  →  ro.serialno
 *   ICCID: `service call iphonesubinfo 11` →  siminfo.icc_id
 *
 * On modern Android the TelephonyManager APIs require privileged permission
 * and usually return null, so the root fallbacks are the reliable path on
 * TCL/MediaTek flip phones — the same path the shell script uses.
 */
object SimInfoReader {

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
    private val ICCID_CALLS = listOf("11", "12")

    /**
     * `sim_id` is the slot index Android's SubscriptionManager keeps on every
     * row in `content://telephony/siminfo`. It's `>= 0` for the SIM currently
     * in a slot and `-1` for historical rows whose SIM has been removed.
     *
     * We filter on it because the content provider keeps one row per SIM the
     * phone has ever seen, and without a filter the first-match regex in
     * [extractField] / [queryField] will happily return the previous SIM's
     * ICCID on a SIM-swapped phone. That bug caused post-swap registrations
     * to POST the old ICCID and broke the Gigs-tab IMEI join (phones.sim_number
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
     * Reads IMEI (stored as `imsi`), ICCID (`icc_id`), and phone number (`number`)
     * from `content://telephony/siminfo` in a **single** root shell call.
     *
     * On TCL/MediaTek flip phones the `service call iphonesubinfo` commands all
     * fail ("Attempt to get length of null array") and the getprop fallbacks are
     * empty, so the siminfo content provider is the only source that works — and
     * it has all three values. Calling this first avoids ~8 failed `su -c` round
     * trips that take 10-15 seconds.
     *
     * Falls back to the individual [readImei]/[readIccid] methods if the combined
     * query fails.
     */
    fun readAll(ctx: Context): SimInfo {
        var imei: String? = null
        var iccid: String? = null
        var phone: String? = null

        // 0. Check if a SIM is actually present. The system telephony DB
        //    (content://telephony/siminfo) caches values from the last
        //    inserted SIM even after it's removed, so we can't trust it
        //    without this gate.
        val simReady = isSimReady(ctx)
        if (!simReady) {
            Log.d(TAG, "readAll: SIM not ready — skipping siminfo (stale data)")
            return SimInfo(null, null, null)
        }

        // 1. Fast path: single content query for all three fields. The
        //    [siminfoQuery] helper pins the query to currently-inserted SIMs
        //    only — see [SIMINFO_ACTIVE_WHERE] for the rationale.
        //    Wrapped in try/catch in case Magisk isn't ready or su is missing.
        try {
            val raw = runSu(siminfoQuery("imsi,icc_id,number"))
            if (raw != null) {
                imei = extractField(raw, "imsi")
                    ?.takeIf { IMEI_REGEX.matches(it) || (it.isNotBlank() && it.all(Char::isDigit)) }
                iccid = extractField(raw, "icc_id")
                    ?.takeIf { ICCID_REGEX.matches(it) }
                phone = extractField(raw, "number")
                    ?.takeIf { it.isNotBlank() && it.any(Char::isDigit) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "readAll: siminfo query failed: ${e.message}")
        }

        // Normalize phone to E.164 — siminfo may store it without the '+' prefix,
        // but the pairing backend requires E.164 for exact-match lookups.
        if (phone != null) {
            phone = PhoneNumberReader.formatE164(phone)
        }

        if (imei != null && iccid != null && phone != null) {
            Log.i(TAG, "readAll: got all three from siminfo in one call")
            return SimInfo(imei, iccid, phone)
        }
        Log.d(TAG, "readAll: siminfo partial (imei=${imei != null} iccid=${iccid != null} phone=${phone != null}) — filling gaps")

        // 2. Fill in any missing values via the individual fallback chains.
        //    Each of these tries TelephonyManager → service calls → getprops, etc.
        if (imei == null) imei = readImei(ctx)
        if (iccid == null) iccid = readIccid(ctx)
        if (phone == null) {
            // PhoneNumberReader tries Settings.Secure, siminfo, SubscriptionManager,
            // and TelephonyManager.getLine1Number.
            val (p, _) = PhoneNumberReader.read(ctx)
            phone = p
        }

        return SimInfo(imei, iccid, phone)
    }

    /** Extract a named field from `content query` output like "Row: 0 field=value, ..." */
    private fun extractField(raw: String, field: String): String? {
        val match = Regex("""$field=([^,}\s]+)""").find(raw) ?: return null
        val v = match.groupValues[1].trim().trim('\r', '\n')
        return if (v.isBlank() || v.equals("NULL", ignoreCase = true) || v == "null") null else v
    }

    /** Returns the device IMEI (or serial as last resort) or null if none available. */
    fun readImei(ctx: Context): String? {
        // 1. TelephonyManager — works on some builds if our caller is privileged
        try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            @Suppress("DEPRECATION", "HardwareIds")
            val viaTm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tm?.imei else tm?.deviceId
            val trimmed = viaTm?.trim()
            if (!trimmed.isNullOrBlank() && IMEI_REGEX.matches(trimmed)) return trimmed
        } catch (e: SecurityException) {
            Log.d(TAG, "TelephonyManager.imei denied — falling back to root")
        } catch (e: Exception) {
            Log.d(TAG, "TelephonyManager.imei failed: ${e.message}")
        }

        // 2. service call iphonesubinfo <code> [ i32 0 ] — try known codes
        //    both without and with a subscriber-id argument.
        for (code in IMEI_CALLS) {
            parseServiceCall(code)?.let { if (IMEI_REGEX.matches(it)) return it }
            parseServiceCall(code, withSubId = true)?.let { if (IMEI_REGEX.matches(it)) return it }
        }

        // 3. Common IMEI getprops seen on MediaTek/TCL builds
        val imeiProps = listOf(
            "ro.ril.oem.imei",
            "ro.ril.oem.imei1",
            "persist.radio.imei",
            "ril.gsm.imei",
        )
        for (p in imeiProps) {
            runSu("getprop $p")?.trim()?.let { if (IMEI_REGEX.matches(it)) return it }
        }

        // 4. content://telephony/siminfo imsi (NOT an IMEI — IMSI. Kept as
        //    a last-ditch fallback that matches the legacy shell script.)
        queryField("imsi")?.let { if (it.isNotBlank() && it.all(Char::isDigit)) return it }

        // 5. ro.serialno — hardware serial as absolute last resort
        runSu("getprop ro.serialno")?.trim()?.takeIf { it.isNotBlank() && it.length in 6..32 }?.let { return it }

        return null
    }

    /** Returns the SIM ICCID or null if not present. */
    fun readIccid(ctx: Context): String? {
        // 1. TelephonyManager
        try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            @Suppress("DEPRECATION")
            val viaTm = tm?.simSerialNumber
            val trimmed = viaTm?.trim()
            if (!trimmed.isNullOrBlank() && ICCID_REGEX.matches(trimmed)) return trimmed
        } catch (e: SecurityException) {
            Log.d(TAG, "TelephonyManager.simSerialNumber denied — falling back to root")
        } catch (e: Exception) {
            Log.d(TAG, "TelephonyManager.simSerialNumber failed: ${e.message}")
        }

        // 2. service call iphonesubinfo <code> [ i32 0 ]
        for (code in ICCID_CALLS) {
            parseServiceCall(code)?.let { if (ICCID_REGEX.matches(it)) return it }
            parseServiceCall(code, withSubId = true)?.let { if (ICCID_REGEX.matches(it)) return it }
        }

        // 3. content://telephony/siminfo icc_id
        queryField("icc_id")?.let { if (ICCID_REGEX.matches(it)) return it }

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
        val raw = runSu(cmd) ?: return null

        // Fast-fail if the raw output is clearly an error rather than a parcel
        val errMarkers = listOf(
            "Attempt to",              // "Attempt to get length of null array"
            "Exception",
            "java.lang.",
            "Permission Denial",
            "SecurityException",
        )
        if (errMarkers.any { raw.contains(it, ignoreCase = true) }) {
            Log.d(TAG, "parseServiceCall($code withSubId=$withSubId): error output: ${raw.take(120)}")
            return null
        }

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
        return cleaned.takeIf { it.isNotBlank() }
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

    private fun runSu(cmd: String): String? {
        return try {
            val proc = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            val exit = proc.waitFor()
            if (exit == 0 && output.isNotBlank()) output else null
        } catch (e: Exception) {
            Log.d(TAG, "runSu($cmd) failed: ${e.message}")
            null
        }
    }
}
