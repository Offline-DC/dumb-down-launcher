package com.offlineinc.dumbdownlauncher.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Single source of truth for the unified event schema described in
 * battery-diagnostics-plan.md §6.2. Every event in events.jsonl and
 * samples.jsonl is produced via [encode] so the on-device shape and
 * the post-processor's expectations stay locked together.
 *
 * Common shape:
 *   {
 *     "schema": 1,
 *     "ts_ms": ...,
 *     "ts_iso": "...",
 *     "monotonic_ms": ...,
 *     "capture_session_id": "uuid",
 *     "type": "battery_sample | screen_on | screen_off | ...",
 *     "source": "system | launcher | <package>",
 *     "payload": { ... },
 *     "screen_state": "on | off | unknown",
 *     "lid_state": "open | closed | unknown",
 *     "charging": true|false,
 *     "battery_level_pct": <int|null>,
 *     "in_doze": true|false|null
 *   }
 */
internal object DiagEvents {

    private val isoFormat: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            return fmt
        }
    }

    fun encode(
        type: String,
        source: String,
        tsMs: Long,
        monotonicMs: Long,
        sessionId: String,
        screenState: String?,
        lidState: String?,
        charging: Boolean?,
        batteryLevelPct: Int?,
        inDoze: Boolean?,
        payload: Map<String, Any?>,
    ): String {
        return DiagJson.obj(
            "schema" to DiagnosticsConfig.SCHEMA_VERSION,
            "ts_ms" to tsMs,
            "ts_iso" to isoFormat.get()!!.format(Date(tsMs)),
            "monotonic_ms" to monotonicMs,
            "capture_session_id" to sessionId,
            "type" to type,
            "source" to source,
            "payload" to payload,
            "screen_state" to (screenState ?: "unknown"),
            "lid_state" to (lidState ?: "unknown"),
            "charging" to (charging ?: false),
            "battery_level_pct" to batteryLevelPct,
            "in_doze" to inDoze,
        )
    }
}
