package com.offlineinc.dumbdownlauncher.launcher.qrenlarge

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives the WhatsApp companion-mode QR enlarge feature.
 *
 * Flow:
 *   1. [onCompanionScreenEntered] is called when the accessibility service
 *      detects RegisterAsCompanionActivity. We just record that we're on
 *      the screen — nothing visible happens until the user presses *.
 *   2. The accessibility service forwards KEYCODE_STAR while on the
 *      companion screen to [toggleEnlargedQr]:
 *        • If the big overlay isn't up, we run the capture pipeline (root
 *          screencap → crop to QR bounds → ZXing decode → ZXing re-encode
 *          at max screen pixels) and show [QrEnlargeOverlay] fullscreen.
 *        • If it's already up, we hide it.
 *   3. While the overlay is up we keep re-running the capture every
 *      [REFRESH_INTERVAL_MS] because WhatsApp rotates the companion-link QR
 *      every ~30s; this updates the overlay in place.
 *   4. [dismissEnlargedQr] is called when the user hits BACK. Hides the
 *      overlay; leaves the small WhatsApp QR untouched.
 *   5. [onLeftCompanionScreen] is called when a different Activity comes
 *      to the foreground. Hides everything and clears state.
 */
internal object QrEnlargeController {

    private const val TAG = "QR_ENLARGE_CTRL"

    private const val WA_QR_RES_ID = "com.whatsapp:id/registration_qr"
    private const val WA_QR_CONTENT_DESC = "QR code"

    /** Cadence between QR refreshes while the overlay is showing. WhatsApp
     *  rotates the companion-link QR roughly every 30s; sampling at 8s
     *  guarantees the overlay never holds a stale code for more than one
     *  rotation. */
    private const val REFRESH_INTERVAL_MS = 8_000L

    /** Retry cadence while a capture/decode is failing (e.g. QR view not
     *  laid out yet, screencap raced with a redraw). */
    private const val CAPTURE_RETRY_INTERVAL_MS = 1_000L

    /** Cap on consecutive capture failures before we fall back to slow
     *  cadence. Prevents busy-spinning screencap when WhatsApp is showing
     *  an error state or the QR is genuinely absent. */
    private const val MAX_FAST_RETRIES = 6

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "qr-enlarge-worker").apply { isDaemon = true }
    }

    private val onScreen = AtomicBoolean(false)
    private val enlarged = AtomicBoolean(false)

    @Volatile private var lastPayload: String? = null
    @Volatile private var pendingCaptureRun: Runnable? = null
    private var fastRetryCount = 0

    /** True when the companion activity is foreground. Accessibility service
     *  reads this to gate the * key. */
    val isOnCompanionScreen: Boolean get() = onScreen.get()

    /** True when the big QR overlay is up. Accessibility service reads
     *  this to decide whether to swallow KEYCODE_BACK. */
    val isEnlargedShowing: Boolean get() = enlarged.get()

    // ── Activity lifecycle hooks ────────────────────────────────────────────

    fun onCompanionScreenEntered() {
        if (!onScreen.compareAndSet(false, true)) return
        Log.i(TAG, "onCompanionScreenEntered: press * to enlarge")
    }

    fun onLeftCompanionScreen() {
        if (!onScreen.compareAndSet(true, false)) return
        Log.i(TAG, "onLeftCompanionScreen: tearing down overlay")
        cancelPendingCapture()
        enlarged.set(false)
        lastPayload = null
        fastRetryCount = 0
        QrEnlargeOverlay.hide()
    }

    // ── Key-driven toggles ──────────────────────────────────────────────────

    /**
     * Called by the accessibility service when the user presses * while on
     * RegisterAsCompanionActivity. If the overlay is already up, hide it;
     * otherwise kick off the capture pipeline to bring it up.
     */
    fun toggleEnlargedQr(service: AccessibilityService) {
        if (!onScreen.get()) {
            Log.d(TAG, "toggleEnlargedQr: not on companion screen — ignoring")
            return
        }
        if (enlarged.get()) {
            Log.i(TAG, "toggleEnlargedQr: * pressed while enlarged — collapsing")
            dismissEnlargedQr()
        } else {
            Log.i(TAG, "toggleEnlargedQr: * pressed — running capture pipeline")
            enlarged.set(true)
            fastRetryCount = 0
            scheduleCapture(service, 0L)
        }
    }

    /**
     * Called by the accessibility service when BACK is pressed while the
     * overlay is up. The capture refresh loop is cancelled and the overlay
     * is hidden; the user is back to WhatsApp's small QR.
     */
    fun dismissEnlargedQr() {
        if (!enlarged.compareAndSet(true, false)) return
        Log.i(TAG, "dismissEnlargedQr: hiding overlay")
        cancelPendingCapture()
        lastPayload = null
        fastRetryCount = 0
        QrEnlargeOverlay.hide()
    }

    // ── Capture pipeline ────────────────────────────────────────────────────

    private fun scheduleCapture(service: AccessibilityService, delayMs: Long) {
        pendingCaptureRun?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { runCapture(service) }
        pendingCaptureRun = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun cancelPendingCapture() {
        pendingCaptureRun?.let { mainHandler.removeCallbacks(it) }
        pendingCaptureRun = null
    }

    private fun runCapture(service: AccessibilityService) {
        if (!onScreen.get() || !enlarged.get()) return

        val bounds = findQrNodeBounds(service)
        if (bounds == null) {
            Log.d(TAG, "runCapture: QR node not laid out yet — retry")
            scheduleNextBeat(service, succeeded = false)
            return
        }

        workerExecutor.execute {
            val full = QrScreencap.captureFullScreen()
            if (full == null) {
                mainHandler.post { scheduleNextBeat(service, succeeded = false) }
                return@execute
            }
            // ZXing needs some quiet zone (~4 modules) around the QR to
            // decode reliably; the reported view bounds are tight, so pad
            // 20% on every side.
            val padded = bounds.expandedBy(0.2f, full.width, full.height)
            val crop = QrScreencap.crop(full, padded)
            if (crop == null) {
                full.recycle()
                mainHandler.post { scheduleNextBeat(service, succeeded = false) }
                return@execute
            }
            val payload = QrCodec.decode(crop)
            crop.recycle()
            full.recycle()
            if (payload == null) {
                mainHandler.post { scheduleNextBeat(service, succeeded = false) }
                return@execute
            }
            // Skip re-encoding if the QR hasn't rotated.
            if (payload == lastPayload && QrEnlargeOverlay.isShowing) {
                mainHandler.post { scheduleNextBeat(service, succeeded = true) }
                return@execute
            }
            val sizePx = pickQrSizePx(service)
            val rendered = QrCodec.encode(payload, sizePx)
            if (rendered == null) {
                mainHandler.post { scheduleNextBeat(service, succeeded = false) }
                return@execute
            }
            lastPayload = payload
            mainHandler.post {
                if (!onScreen.get() || !enlarged.get()) {
                    rendered.recycle()
                    return@post
                }
                if (QrEnlargeOverlay.isShowing) {
                    QrEnlargeOverlay.update(service, rendered)
                } else {
                    QrEnlargeOverlay.show(service, rendered)
                }
                scheduleNextBeat(service, succeeded = true)
            }
        }
    }

    private fun scheduleNextBeat(service: AccessibilityService, succeeded: Boolean) {
        if (!onScreen.get() || !enlarged.get()) return
        if (succeeded) {
            fastRetryCount = 0
            scheduleCapture(service, REFRESH_INTERVAL_MS)
        } else {
            fastRetryCount++
            val delay = if (fastRetryCount > MAX_FAST_RETRIES) REFRESH_INTERVAL_MS else CAPTURE_RETRY_INTERVAL_MS
            Log.d(TAG, "scheduleNextBeat: failure #$fastRetryCount, next in ${delay}ms")
            scheduleCapture(service, delay)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun findQrNodeBounds(service: AccessibilityService): Rect? {
        val root = try {
            service.rootInActiveWindow
        } catch (t: Throwable) {
            Log.w(TAG, "findQrNodeBounds: rootInActiveWindow threw ${t.message}")
            null
        } ?: return null

        return try {
            val byId = root.findAccessibilityNodeInfosByViewId(WA_QR_RES_ID)
            val node: AccessibilityNodeInfo? = byId.firstOrNull()
                ?: findByContentDesc(root, WA_QR_CONTENT_DESC)
            if (node == null) {
                Log.d(TAG, "findQrNodeBounds: no match for id=$WA_QR_RES_ID or desc=$WA_QR_CONTENT_DESC")
                return null
            }
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.isEmpty) {
                Log.d(TAG, "findQrNodeBounds: empty bounds (not yet laid out)")
                return null
            }
            rect
        } finally {
            root.recycle()
        }
    }

    private fun findByContentDesc(root: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        if (root.contentDescription?.toString() == desc) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val match = findByContentDesc(child, desc)
            if (match != null) return match
        }
        return null
    }

    private fun pickQrSizePx(context: Context): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        // Encode the QR at the full shorter screen dimension so it fills
        // the width edge-to-edge on a portrait device. The overlay no
        // longer reserves space for hint text, so nothing eats into this.
        return minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(160)
    }

    private fun Rect.expandedBy(fraction: Float, maxW: Int, maxH: Int): Rect {
        val dx = (width() * fraction / 2f).toInt()
        val dy = (height() * fraction / 2f).toInt()
        return Rect(
            (left - dx).coerceAtLeast(0),
            (top - dy).coerceAtLeast(0),
            (right + dx).coerceAtMost(maxW),
            (bottom + dy).coerceAtMost(maxH),
        )
    }
}
