package com.offlineinc.dumbdownlauncher.launcher

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val subManager = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                val subs = subManager?.activeSubscriptionInfoList
                val number = subs?.firstOrNull()?.number
                if (!number.isNullOrBlank()) {
                    return formatE164(number) to null
                }
            }
            @Suppress("DEPRECATION")
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val line = tm?.line1Number
            if (!line.isNullOrBlank()) {
                return formatE164(line) to null
            }

            val rootNumber = readViaSu()
            if (rootNumber != null) {
                Log.i(TAG, "Got phone number via root fallback")
                return formatE164(rootNumber) to null
            }

            Log.e(TAG, "SIM did not provide phone number (all methods exhausted)")
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

    private fun runSuCommand(cmd: String): String? {
        return try {
            val proc = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            val exitCode = proc.waitFor()
            if (exitCode == 0 && output.isNotBlank()) output else null
        } catch (e: Exception) {
            Log.w(TAG, "runSuCommand($cmd) failed: ${e.message}")
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
