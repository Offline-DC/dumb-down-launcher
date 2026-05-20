package com.offlineinc.dumbdownlauncher.diagnostics

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Runs a privileged shell command via `su -c` and writes the stdout into
 * a destination file. The launcher already uses this pattern in
 * PhoneNumberReader / CallLogCleanupWorker to self-grant runtime perms,
 * so we know the diag beta devices have root via Magisk.
 *
 * Returns true on a clean exit + non-empty output, false otherwise. Failures
 * are swallowed deliberately — the service must keep running even if a single
 * snapshot fails (e.g. on a device that briefly lost root).
 */
internal object ShellRunner {

    private const val TAG = "DiagShell"

    /**
     * Executes `su -c <command>` and writes stdout to `dest`. Stderr is
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
                // Capture any stderr at the bottom so the post-processor can see it.
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

    /**
     * One-shot capture of a command's stdout as a String. Used for short
     * outputs like `getprop ro.build.fingerprint`. Returns null on any error.
     */
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
