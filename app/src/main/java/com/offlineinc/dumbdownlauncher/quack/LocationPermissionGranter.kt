package com.offlineinc.dumbdownlauncher.quack

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

private const val TAG = "LocPermGranter"

/**
 * Self-grants location permissions via `su pm grant` if they're missing.
 *
 * The launcher targets rooted TCL/MediaTek flip phones where we ship
 * set_phone_permissions.sh to pre-grant perms during provisioning. But:
 *  - fresh OTA installs that haven't been re-provisioned end up missing them
 *  - dev reinstalls reset grants (UID changes)
 *  - users doing manual APK sideloads never run the script at all
 *
 * This runs on every boot, checks ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION,
 * and if missing shells out `su -c pm grant ...` to grant them itself.
 * No-op if already granted (common case), so essentially free.
 */
object LocationPermissionGranter {

    private const val PKG = "com.offlineinc.dumbdownlauncher"
    private val NEEDED = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /** Idempotent: checks each perm, grants via su only the ones that are missing. */
    @JvmStatic
    fun ensureGranted(ctx: Context) {
        val missing = NEEDED.filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            Log.d(TAG, "all location perms already granted")
            return
        }
        Log.i(TAG, "missing perms: $missing — attempting su grant")
        for (perm in missing) {
            val ok = runSu("pm grant $PKG $perm")
            Log.i(TAG, "grant $perm → ${if (ok) "ok" else "failed"}")
        }
    }

    private fun runSu(cmd: String, timeoutMs: Long = 1500L): Boolean {
        var proc: Process? = null
        return try {
            proc = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                Log.w(TAG, "runSu($cmd) timed out after ${timeoutMs}ms")
                proc.destroyForcibly()
                return false
            }
            proc.exitValue() == 0
        } catch (e: Exception) {
            Log.w(TAG, "runSu($cmd) failed: ${e.message}")
            try { proc?.destroyForcibly() } catch (_: Exception) {}
            false
        }
    }
}
