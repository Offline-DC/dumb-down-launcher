package com.offlineinc.dumbdownlauncher.diagnostics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock

/**
 * Reads a one-shot battery state snapshot. Combines the registers exposed
 * by BatteryManager.getIntProperty with the sticky ACTION_BATTERY_CHANGED
 * extras (temperature, voltage, status), and the doze/screen state from
 * PowerManager so every sample is self-contained.
 *
 * The output is encoded as a JSONL line keyed on `type=battery_sample`,
 * matching the unified event schema in battery-diagnostics-plan.md §6.2.
 */
internal object BatterySampler {

    fun sampleAsJson(
        context: Context,
        sessionId: String,
        screenStateHint: String?,
        lidStateHint: String?,
    ): String {
        val ts = System.currentTimeMillis()
        val monotonic = SystemClock.elapsedRealtime()

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

        val capacityPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it >= 0 }
        val currentNowUa = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.takeIf { it != Int.MIN_VALUE }
        val chargeCounterUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it != Int.MIN_VALUE }
        val energyCounterNwh = try {
            bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
                ?.takeIf { it != Long.MIN_VALUE }
        } catch (_: Throwable) { null }

        // Sticky ACTION_BATTERY_CHANGED — pulled fresh each call so temperature/voltage are current.
        val sticky: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempDeciC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
        val tempC: Double? = tempDeciC?.let { it / 10.0 }
        val voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
        val statusRaw = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val pluggedRaw = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val status = when (statusRaw) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }
        val charging = pluggedRaw != null && pluggedRaw != 0

        val inDoze = try { pm?.isDeviceIdleMode } catch (_: Throwable) { null }
        val screenOn = try { pm?.isInteractive } catch (_: Throwable) { null }
        val screenState = screenStateHint ?: if (screenOn == true) "on" else if (screenOn == false) "off" else "unknown"

        return DiagEvents.encode(
            type = "battery_sample",
            source = "system",
            tsMs = ts,
            monotonicMs = monotonic,
            sessionId = sessionId,
            screenState = screenState,
            lidState = lidStateHint,
            charging = charging,
            batteryLevelPct = capacityPct,
            inDoze = inDoze,
            payload = mapOf(
                "capacity_pct" to capacityPct,
                "current_now_ua" to currentNowUa,
                "charge_counter_uah" to chargeCounterUah,
                "energy_counter_nwh" to energyCounterNwh,
                "temp_c" to tempC,
                "voltage_mv" to voltageMv,
                "status" to status,
            ),
        )
    }
}
