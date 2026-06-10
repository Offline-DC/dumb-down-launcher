package com.offlineinc.dumbdownlauncher.diagnostics

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Reads the flip-phone hall sensor on the TCL 4058W (and likely siblings)
 * by tailing the raw kernel input device with `su -c 'getevent -lt
 * <path>'`. The lid is NOT exposed via SensorManager and NOT exposed via
 * Android's SW_LID input switch on this device — we confirmed both are
 * empty in dumpsys — so this is the only path that actually fires when
 * the user opens or closes the flip.
 *
 * Expected stdout lines from `getevent -lt /dev/input/event3` look like:
 *
 *   [   54976.998068] /dev/input/event3: EV_KEY       00fc                 DOWN
 *   [   54976.998068] /dev/input/event3: EV_SYN       SYN_REPORT           00000000
 *   [   54979.165036] /dev/input/event3: EV_KEY       00fc                 UP
 *
 * We only care about EV_KEY lines whose keycode matches
 * [DiagnosticsConfig.LID_KEYCODE_HEX]. DOWN / UP map to closed / open
 * per [DiagnosticsConfig.LID_KEY_DOWN_MEANS_CLOSED] (flip-phone
 * convention is hall sensor latches when the magnet is in range, i.e.
 * lid closed → DOWN).
 *
 * Threading model: a single dedicated worker thread owns the subprocess
 * and the stdout reader. The thread loops forever — when the subprocess
 * dies (EOF on stdout, exec failure, etc.) it sleeps for a backoff
 * interval and spawns a new one. [stop] flips a flag and interrupts the
 * thread to break it out of any blocking read or sleep.
 *
 * State + event delivery happens through two callbacks supplied by
 * [DiagnosticsService]:
 *
 *   - [onLidStateChanged] updates the service's `lastLidState` field so
 *     the next per-minute `battery_sample` row carries the right value.
 *   - [onLidEvent] writes a `lid_open` / `lid_close` / `lid_bounce`
 *     event to `events.jsonl` via the existing appendEvent helper.
 *
 * Both callbacks may be invoked from the reader thread. They must not
 * block. Writing to JsonlWriter is fine — it's @Synchronized internally
 * and the contention is negligible.
 */
internal class LidSensorReader(
    private val devicePath: String = DiagnosticsConfig.LID_INPUT_DEVICE_PATH,
    private val keycodeHex: String = DiagnosticsConfig.LID_KEYCODE_HEX,
    private val downMeansClosed: Boolean = DiagnosticsConfig.LID_KEY_DOWN_MEANS_CLOSED,
    private val bounceWindowMs: Long = DiagnosticsConfig.LID_BOUNCE_WINDOW_MS,
    private val onLidStateChanged: (newState: String) -> Unit,
    private val onLidEvent: (type: String, payload: Map<String, Any?>) -> Unit,
) {

    private val tag = "DiagLid"

    @Volatile private var running: Boolean = false
    @Volatile private var process: Process? = null
    private var workerThread: Thread? = null

    private var lastEventMonotonicMs: Long = 0L
    private var lastEmittedState: String? = null

    fun start() {
        if (running) return
        running = true
        workerThread = Thread({ runLoop() }, "DiagLidReader").apply {
            isDaemon = true
            start()
        }
        Log.i(tag, "started; device=$devicePath keycode=$keycodeHex downMeansClosed=$downMeansClosed")
    }

    fun stop() {
        running = false
        try { process?.destroyForcibly() } catch (_: Throwable) {}
        try { workerThread?.interrupt() } catch (_: Throwable) {}
        process = null
        workerThread = null
    }

    // ── Reader loop ──────────────────────────────────────────────────────

    private fun runLoop() {
        var backoffMs = DiagnosticsConfig.LID_READER_RESPAWN_INITIAL_MS
        while (running) {
            try {
                runOnce()
                // Clean exit from runOnce means stdout closed. Treat as a
                // failure and respawn with backoff.
                Log.w(tag, "subprocess exited; respawn in ${backoffMs}ms")
            } catch (ie: InterruptedException) {
                // Most likely stop() — break out of the outer loop.
                Log.i(tag, "interrupted; exiting reader loop")
                return
            } catch (t: Throwable) {
                Log.w(tag, "reader threw; respawn in ${backoffMs}ms", t)
            }
            if (!running) return
            try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { return }
            backoffMs = (backoffMs * 2).coerceAtMost(DiagnosticsConfig.LID_READER_RESPAWN_MAX_MS)
        }
    }

    /**
     * One spawn of the `su -c getevent` subprocess. Returns when stdout
     * closes (typical) or throws on exec failure. The outer loop handles
     * the respawn.
     */
    private fun runOnce() {
        val cmd = "getevent -lt $devicePath"
        val p = ProcessBuilder("su", "-c", cmd)
            .redirectErrorStream(true) // mingled stream is fine — we filter line-wise
            .start()
        process = p
        try {
            BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                while (running) {
                    val line = reader.readLine() ?: break // EOF
                    if (line.isEmpty()) continue
                    handleLine(line)
                }
            }
        } finally {
            try { p.destroyForcibly() } catch (_: Throwable) {}
            if (process === p) process = null
        }
    }

    // ── Parsing ──────────────────────────────────────────────────────────

    /**
     * Parse one line of `getevent -lt` output. Lines we care about look
     * like (multiple spaces between fields, tokenize on whitespace):
     *
     *   [   54976.998068] /dev/input/event3: EV_KEY       00fc                 DOWN
     *
     * We accept any line that contains an `EV_KEY` token followed by our
     * configured keycode hex, followed by DOWN / UP / 00000000 / 00000001.
     * Other devices' EV_KEY events (volume buttons, dpad, etc.) are
     * filtered out by the device path passed to getevent, so we rarely
     * see them — but the keycode check protects against accidental
     * matches if the user reuses this reader on a different node.
     */
    private fun handleLine(line: String) {
        val tokens = line.trim().split(Regex("\\s+"))
        // Find EV_KEY anywhere in the line; the leading [timestamp] and
        // device path prefixes are not at fixed indices across kernels.
        val evIdx = tokens.indexOf("EV_KEY")
        if (evIdx < 0 || evIdx + 2 >= tokens.size) return

        val code = tokens[evIdx + 1]
        if (!code.equals(keycodeHex, ignoreCase = true)) return

        val valueTok = tokens[evIdx + 2]
        val isDown = when {
            valueTok.equals("DOWN", ignoreCase = true) -> true
            valueTok.equals("UP", ignoreCase = true) -> false
            valueTok == "00000001" -> true   // raw value form, when -l can't symbolize
            valueTok == "00000000" -> false
            else -> return
        }

        val newState = if (isDown == downMeansClosed) "closed" else "open"

        // Parse the kernel monotonic timestamp out of the leading
        // bracketed seconds.microseconds, if present. Falls back to the
        // service's elapsedRealtime via the callback site if absent.
        val kernelMonotonicMs: Long? = parseKernelMonotonicMs(line)

        emit(newState, kernelMonotonicMs, valueTok)
    }

    /**
     * Best-effort parse of the leading `[   54976.998068]` timestamp.
     * Returns milliseconds since kernel boot, or null if the line has no
     * bracketed prefix.
     */
    private fun parseKernelMonotonicMs(line: String): Long? {
        val open = line.indexOf('[')
        val close = line.indexOf(']')
        if (open != 0 || close <= open + 1) return null
        val inner = line.substring(open + 1, close).trim()
        val seconds = inner.toDoubleOrNull() ?: return null
        return (seconds * 1000.0).toLong()
    }

    private fun emit(newState: String, kernelMonotonicMs: Long?, rawValueToken: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val deltaMs = if (lastEventMonotonicMs == 0L) -1L else (now - lastEventMonotonicMs)
        lastEventMonotonicMs = now

        // Bounce detection — any event arriving inside the bounce window
        // gets tagged. We still emit the normal lid_open/lid_close so the
        // state machine stays consistent; the bounce is an additional
        // event the analyst can count toward hypothesis #2.
        val bounced = deltaMs in 0..bounceWindowMs

        val payload = mutableMapOf<String, Any?>(
            "new_state" to newState,
            "raw_value" to rawValueToken,
            "device_path" to devicePath,
            "keycode_hex" to keycodeHex,
            "delta_ms_since_prev" to if (deltaMs >= 0) deltaMs else null,
            "kernel_monotonic_ms" to kernelMonotonicMs,
        )

        val type = if (newState == "closed") "lid_close" else "lid_open"

        // Only fire the state-change callback when the state actually
        // changes — duplicate DOWN/DOWN from chatter shouldn't repeatedly
        // overwrite lastLidState (it would be a no-op anyway, but this
        // avoids needless callback churn).
        if (lastEmittedState != newState) {
            try { onLidStateChanged(newState) } catch (t: Throwable) {
                Log.w(tag, "onLidStateChanged threw", t)
            }
            lastEmittedState = newState
        }

        try { onLidEvent(type, payload) } catch (t: Throwable) {
            Log.w(tag, "onLidEvent($type) threw", t)
        }

        if (bounced) {
            val bouncePayload = mapOf<String, Any?>(
                "new_state" to newState,
                "delta_ms_since_prev" to deltaMs,
                "bounce_window_ms" to bounceWindowMs,
            )
            try { onLidEvent("lid_bounce", bouncePayload) } catch (t: Throwable) {
                Log.w(tag, "onLidEvent(lid_bounce) threw", t)
            }
        }
    }
}
