package com.offlineinc.dumbdownlauncher

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Checks whether the installed FlipMouse (DumbMouse) Magisk module is older
 * than the version bundled in this APK's assets.  If so, copies the new binary
 * in place and restarts the daemon — no reboot required.
 *
 * Called once from [DumbDownApp.onCreate] on a background thread.
 */
object FlipMouseUpdater {

    private const val TAG = "FlipMouseUpdater"
    private const val MODULE_DIR = "/data/adb/modules/DumbMouse"
    private const val MODULE_PROP = "$MODULE_DIR/module.prop"
    private const val MOUSE_BIN = "$MODULE_DIR/mouse"
    private const val TMP_DIR = "/data/local/tmp/flipmouse_update"

    /**
     * Run the update check + push.  Safe to call from any thread; all I/O is
     * blocking and runs on the caller's thread.
     */
    fun checkAndUpdate(context: Context) {
        try {
            // 1. Is the module even installed?
            val installedVersion = readInstalledVersion()
            if (installedVersion < 0) {
                Log.d(TAG, "DumbMouse module not installed — skipping update")
                return
            }

            // 2. What version ships with this APK?
            val bundledVersion = readBundledVersion(context)
            if (bundledVersion <= installedVersion) {
                Log.d(TAG, "DumbMouse up to date (installed=$installedVersion bundled=$bundledVersion)")
                return
            }

            Log.i(TAG, "Updating DumbMouse: installed=$installedVersion -> bundled=$bundledVersion")

            // 3. Copy bundled files to a temp staging directory
            exec("su -c mkdir -p $TMP_DIR")

            copyAssetToTmp(context, "flipmouse/mouse", "$TMP_DIR/mouse")
            copyAssetToTmp(context, "flipmouse/module.prop", "$TMP_DIR/module.prop")
            // 4. Replace the binary using rm + cp rather than cp-over.
            //    Overwriting a running executable yields "Text file busy" on Linux.
            //    rm just removes the directory entry; running processes keep their
            //    open file descriptor to the old inode, so they're unaffected.
            //    cp then creates a brand-new file at the same path — no conflict.
            exec("su -c rm -f $MOUSE_BIN")
            exec("su -c cp $TMP_DIR/mouse $MOUSE_BIN")
            exec("su -c chmod 755 $MOUSE_BIN")
            exec("su -c cp $TMP_DIR/module.prop $MODULE_PROP")
            // 5. Kill all running instances (old binary still running via its inode),
            //    then restart with the new binary.
            //    Use a finally block so the daemon always comes back up even if
            //    something above throws.
            try {
                exec("su -c killall mouse")
            } catch (_: Exception) {}
            try {
                exec("su -c pkill -f mouse")
            } catch (_: Exception) {}
            Thread.sleep(800)

            // 6. Start the new daemon via the module's service script
            val serviceScript = "$MODULE_DIR/service.sh"
            exec("su -c sh $serviceScript")

            // 6. Clean up
            exec("su -c rm -rf $TMP_DIR")

            Log.i(TAG, "DumbMouse updated successfully to v$bundledVersion and daemon restarted")
        } catch (t: Throwable) {
            Log.e(TAG, "DumbMouse update failed: ${t.message}", t)
            // Non-fatal — but make sure the daemon is running regardless,
            // in case we killed it before the error.
            try {
                exec("su -c sh $MODULE_DIR/service.sh")
                Log.i(TAG, "Restarted daemon after failed update")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart daemon: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Parse `versionCode=N` from the installed module.prop. Returns -1 if missing. */
    private fun readInstalledVersion(): Int {
        return try {
            val output = exec("su -c cat $MODULE_PROP")
            parseVersionCode(output)
        } catch (_: Throwable) {
            -1
        }
    }

    /** Parse `versionCode=N` from the bundled assets/flipmouse/module.prop. */
    private fun readBundledVersion(context: Context): Int {
        val text = context.assets.open("flipmouse/module.prop")
            .bufferedReader().readText()
        return parseVersionCode(text)
    }

    private fun parseVersionCode(text: String): Int {
        val match = Regex("""versionCode=(\d+)""").find(text)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    /**
     * Copy an asset file to a temp path on device.
     * We write to the app's cache dir first (no root needed), then `su -c cp`
     * to the actual destination (which may be on a root-only partition).
     */
    private fun copyAssetToTmp(context: Context, assetPath: String, destPath: String) {
        val cacheFile = File(context.cacheDir, assetPath.replace("/", "_"))
        context.assets.open(assetPath).use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        }
        exec("su -c cp ${cacheFile.absolutePath} $destPath")
        cacheFile.delete()
    }

    /** Run a shell command and return stdout. Throws on non-zero exit. */
    private fun exec(command: String): String {
        val parts = command.split(" ", limit = 3)
        val pb = if (parts[0] == "su" && parts.size >= 3) {
            ProcessBuilder("su", parts[1], parts.drop(2).joinToString(" "))
        } else {
            ProcessBuilder(*command.split(" ").toTypedArray())
        }
        val proc = pb.redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        if (exit != 0) {
            throw RuntimeException("Command failed (exit=$exit): $command\n$output")
        }
        return output
    }
}
