package com.offlineinc.dumbdownlauncher.launcher

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

private const val TAG = "PhoneNumberReader"

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

    fun read(ctx: Context): Pair<String?, String?> {
        return try {
            // 1. Setup-script-written values (most reliable on MediaTek flip phones):
            //    automated_configuration.sh writes the number to Settings.Secure and
            //    content://telephony/siminfo during provisioning, so check these first.
            val scriptNumber = readViaSu()
            if (scriptNumber != null) {
                Log.i(TAG, "Got phone number via setup-script store")
                return formatE164(scriptNumber) to null
            }

            // 2. SubscriptionManager (Android 13+) — SIM API, unreliable on MediaTek
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val subManager = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                val subs = subManager?.activeSubscriptionInfoList
                val number = subs?.firstOrNull()?.number
                if (!number.isNullOrBlank()) {
                    Log.i(TAG, "Got phone number via SubscriptionManager")
                    return formatE164(number) to null
                }
            }

            // 3. TelephonyManager.getLine1Number — deprecated SIM API, last resort
            @Suppress("DEPRECATION")
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val line = tm?.line1Number
            if (!line.isNullOrBlank()) {
                Log.i(TAG, "Got phone number via TelephonyManager")
                return formatE164(line) to null
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

    private fun readViaSu(): String? {
        // Method 1: Settings.Secure (written by setup script via #686# USSD query)
        try {
            val setting = runSuCommand("settings get secure device_phone_number")
            val num = setting?.trim()
            if (!num.isNullOrBlank() && num != "null" && num.any { it.isDigit() }) {
                Log.d(TAG, "Root fallback (Settings.Secure) got number")
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
                    return num.replace("-", "")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Root fallback (siminfo) failed", e)
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
