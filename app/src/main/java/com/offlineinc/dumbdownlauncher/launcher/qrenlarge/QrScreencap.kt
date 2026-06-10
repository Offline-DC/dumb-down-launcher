package com.offlineinc.dumbdownlauncher.launcher.qrenlarge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Captures the device's framebuffer via root `screencap -p` and returns it as a
 * Bitmap.
 *
 * `screencap -p` writes a PNG to stdout, so we just buffer that into memory and
 * decode it with BitmapFactory. We pipe through `su` because the launcher is
 * not signed with the platform key — without root, screencap fails on stock
 * Android with "Permission Denial: can't access screen contents".
 *
 * Threading: caller is expected to invoke from a background executor — the
 * shell-out blocks until screencap finishes (~50-150ms on a 240x320 device).
 *
 * Returns null on any failure (su denied, screencap not found, decode failed)
 * and logs the cause. Callers should be prepared to retry or fall back.
 */
internal object QrScreencap {

    private const val TAG = "QR_ENLARGE_CAP"

    fun captureFullScreen(): Bitmap? {
        return try {
            val proc = ProcessBuilder("su", "-c", "screencap -p")
                .redirectErrorStream(false)
                .start()

            // Drain stdout fully before waitFor() — Android's process stdout
            // buffer is small and screencap will block writing if we don't
            // consume eagerly, which would in turn block waitFor() forever.
            val pngBytes = proc.inputStream.use { it.readBytesCompat() }
            val errBytes = proc.errorStream.use { it.readBytesCompat() }
            val exit = proc.waitFor()
            if (exit != 0) {
                Log.w(TAG, "screencap exited $exit — stderr: ${String(errBytes).trim()}")
                return null
            }
            if (pngBytes.isEmpty()) {
                Log.w(TAG, "screencap produced 0 bytes")
                return null
            }
            val bmp = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
            if (bmp == null) {
                Log.w(TAG, "decodeByteArray returned null for ${pngBytes.size}-byte buffer")
            } else {
                Log.d(TAG, "captured ${bmp.width}x${bmp.height} frame")
            }
            bmp
        } catch (t: Throwable) {
            Log.e(TAG, "captureFullScreen failed: ${t.message}", t)
            null
        }
    }

    /**
     * Convenience: crops [full] to the on-screen [bounds] of an accessibility
     * node (in raw screen pixels — the same coordinate system screencap uses).
     * Returns null if the crop would be empty or out of range.
     */
    fun crop(full: Bitmap, bounds: Rect): Bitmap? {
        val clipped = Rect(bounds)
        if (!clipped.intersect(0, 0, full.width, full.height)) return null
        if (clipped.width() <= 0 || clipped.height() <= 0) return null
        return try {
            Bitmap.createBitmap(full, clipped.left, clipped.top, clipped.width(), clipped.height())
        } catch (t: Throwable) {
            Log.w(TAG, "crop failed for $clipped in ${full.width}x${full.height}: ${t.message}")
            null
        }
    }

    // Minimal stream.readBytes() that works on minSdk 24 without
    // pulling in kotlin.io extensions that vary across Kotlin versions.
    private fun java.io.InputStream.readBytesCompat(): ByteArray {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val n = read(chunk)
            if (n <= 0) break
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }
}
