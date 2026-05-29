package com.offlineinc.dumbdownlauncher.diagnostics

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Runs a privileged shell command via `su -c` and writes the stdout
 * into a destination file. The launcher already uses this pattern in
 * PhoneNumberReader / CallLogCleanupWorker to self-grant runtime perms,
 * so we know the diag beta device has root via Magisk.
 *
 * Returns true on a clean exit and non-empty output, false otherwise.
 * Failures are swallowed deliberately — a single missing snapshot must
 * not bring down the rest of the diagnostics loop (e.g. a brief root
 * outage from a Magisk policy reload).
 */
internal object ShellRunner {

    private const val TAG = "RebootDiagShell"

    /**
     * Executes `su -c <command>` and writes stdout to [dest]. Stderr is
     * captured and appended as a trailing comment block so post-processing
     * can flag truncated snapshots.
     */
    fun runToFile(command: String, dest: File, timeoutMs: Long): Boolean {
        return try {
            dest.parentFile?.mkdirs()
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(false)
                .start()

            FileWriter(dest).use { writer ->
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = reader.read(buf)
                        if (n <= 0) break
                        writer.write(buf, 0, n)
                    }
                }
                val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
                if (stderr.isNotBlank()) {
                    writer.write("\n# --- stderr ---\n")
                    writer.write(stderr)
                }
            }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.w(TAG, "Timed out: $command")
                return false
            }
            process.exitValue() == 0 && dest.length() > 0
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to run: $command", t)
            false
        }
    }

    /** Short stdout-capture for one-liners like `getprop`. Null on error. */
    fun captureString(command: String, timeoutMs: Long): String? {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() == 0) output.trim() else null
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to capture: $command", t)
            null
        }
    }
}
