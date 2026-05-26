package com.offlineinc.dumbdownlauncher.launcher.qrenlarge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Fullscreen overlay that displays a re-rendered, max-resolution QR code on top
 * of WhatsApp's companion-mode "Link as companion device" screen.
 *
 * The overlay is pure QR — no hint text, no chrome. The QR is rendered with
 * margin=0 in [QrCodec.encode] so the modules go right to the edge of the
 * bitmap, and the white window background then provides the visual quiet zone
 * needed for another phone's camera to lock onto it.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY (no SYSTEM_ALERT_WINDOW needed) — the
 * accessibility service's grant covers it, matching ResetWarningOverlay.
 *
 * Lifecycle:
 *   • show(context, qr) — renders/replaces the overlay. Safe to call from
 *                         any thread; posts to the main thread internally.
 *   • update(qr)        — swap the QR bitmap (used when WhatsApp rotates the
 *                         linking code every ~30s and we re-screencap).
 *   • hide()            — tear the overlay down. Called when WhatsApp is no
 *                         longer the foreground app or the user toggles off.
 */
internal object QrEnlargeOverlay {

    private const val TAG = "QR_ENLARGE_OVERLAY"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var qrImage: ImageView? = null
    private var windowManager: WindowManager? = null

    val isShowing: Boolean get() = overlayView != null

    fun show(context: Context, qr: Bitmap) {
        mainHandler.post {
            if (overlayView != null) {
                Log.d(TAG, "show(): overlay already up — calling update() instead")
                qrImage?.let { setQrOn(context, it, qr) }
                return@post
            }
            Log.d(TAG, "show(): adding overlay window with ${qr.width}x${qr.height} QR")

            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            // Single ImageView centered on a white background — no hint
            // text, no chrome. White background acts as the visual quiet
            // zone since QrCodec emits a padding-free QR. A few dp of
            // padding on the container keeps the QR modules off the
            // physical screen edges, which gives the scanning camera a
            // small but real quiet zone to lock onto (QR specs ask for
            // 4 modules; we can't afford that on a 240×320 screen, but
            // even a thin band of white at the edges helps).
            val quietZonePx = (12 * context.resources.displayMetrics.density + 0.5f).toInt()
            val container = FrameLayout(context).apply {
                setBackgroundColor(Color.WHITE)
                setPadding(quietZonePx, quietZonePx, quietZonePx, quietZonePx)
            }

            val image = ImageView(context).apply {
                // FIT_CENTER keeps the QR square and unrotated. The QR fills
                // the shorter screen dimension (width on a portrait phone);
                // any extra space along the long dimension is plain white,
                // which is fine — it just extends the quiet zone.
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = false
            }
            setQrOn(context, image, qr)
            qrImage = image
            container.addView(
                image,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                ),
            )

            // FLAG_NOT_FOCUSABLE — let key events flow through to the
            //   accessibility service (which handles # and back).
            // FLAG_LAYOUT_IN_SCREEN + FLAG_LAYOUT_NO_LIMITS — cover the full
            //   screen including under the status bar so the QR can use
            //   every available pixel on this 240x320 device.
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE,
            ).apply {
                gravity = Gravity.CENTER
            }

            try {
                wm.addView(container, params)
                overlayView = container
                Log.d(TAG, "show(): overlay window added")
            } catch (e: Exception) {
                Log.e(TAG, "show(): failed to add overlay window — ${e.message}", e)
                qrImage = null
                windowManager = null
            }
        }
    }

    /**
     * Replace the displayed QR without tearing down the overlay. No-op if the
     * overlay isn't showing — caller should fall back to [show].
     */
    fun update(context: Context, qr: Bitmap) {
        mainHandler.post {
            val img = qrImage
            if (img == null) {
                Log.d(TAG, "update(): not showing — ignoring")
                return@post
            }
            setQrOn(context, img, qr)
        }
    }

    /**
     * Wraps [qr] in a BitmapDrawable with isFilterBitmap = false before
     * setting it on [target]. QrCodec hands us a tiny bitmap with one
     * pixel per QR module — when the ImageView scales it up to fill the
     * screen, the default bilinear filter would soften the module edges
     * into a blurry gradient that's much harder for another phone's
     * camera to lock onto. Disabling the filter gives nearest-neighbor
     * scaling: perfectly sharp black-and-white edges at every zoom level.
     */
    private fun setQrOn(context: Context, target: ImageView, qr: Bitmap) {
        val drawable = BitmapDrawable(context.resources, qr).apply {
            isFilterBitmap = false
            setAntiAlias(false)
        }
        target.setImageDrawable(drawable)
    }

    fun hide() {
        mainHandler.post {
            val view = overlayView ?: return@post
            Log.d(TAG, "hide(): removing overlay window")
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "hide(): removeView failed — ${e.message}")
            }
            overlayView = null
            qrImage = null
            windowManager = null
        }
    }
}
