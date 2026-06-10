package com.offlineinc.dumbdownlauncher.launcher.qrenlarge

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Reads QR codes out of a Bitmap and re-encodes the payload as a fresh,
 * high-contrast QR at a chosen pixel size.
 *
 * Why we re-encode rather than just upscaling the captured QR:
 *   • The QR on a 240x320 device screen is often only ~120 px wide. Scaling
 *     a 120 px image to 320 px keeps the same coarse modules and produces
 *     a soft, hard-to-scan result.
 *   • Re-encoding from the decoded payload renders the QR at exactly the
 *     module count the data requires, then we scale up by an integer factor
 *     so every module gets clean black/white pixels — much more scannable
 *     by another phone's camera.
 *   • WhatsApp rotates its companion-mode QR every ~30s, so this also lets
 *     the overlay show a fresh QR whenever the underlying screen updates.
 */
internal object QrCodec {

    private const val TAG = "QR_ENLARGE_CODEC"

    /**
     * Attempts to decode any QR code present in [source]. Tries the image as-is
     * first, then with TRY_HARDER and PURE_BARCODE hints if the first pass
     * fails — these hints are slower but recover small/low-contrast QRs much
     * better than the default settings.
     *
     * Returns the decoded payload (the WhatsApp linking URL/blob) or null.
     */
    fun decode(source: Bitmap): String? {
        val luminance = source.toLuminanceSource() ?: return null
        val binary = BinaryBitmap(HybridBinarizer(luminance))
        val reader = QRCodeReader()

        val attempts = listOf(
            emptyMap(),
            mapOf(DecodeHintType.TRY_HARDER to true),
            mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.PURE_BARCODE to true),
        )
        for (hints in attempts) {
            try {
                val result = reader.decode(binary, hints)
                val text = result.text
                if (!text.isNullOrEmpty()) {
                    Log.d(TAG, "decoded QR (${text.length} chars) with hints=$hints")
                    return text
                }
            } catch (t: Throwable) {
                // Expected — most attempts will throw NotFoundException until
                // one of the hint combos succeeds. We only log at debug.
                Log.v(TAG, "decode miss (hints=$hints): ${t.javaClass.simpleName}")
            } finally {
                reader.reset()
            }
        }
        Log.d(TAG, "no QR found in ${source.width}x${source.height} bitmap")
        return null
    }

    /**
     * Renders [payload] as a tight black-on-white QR bitmap with exactly one
     * pixel per QR module — no quiet zone, no padding. The returned bitmap
     * is intended to be scaled up by the overlay's ImageView (with
     * bitmap-filter disabled) so the modules stay perfectly square and edge-
     * aligned regardless of the target display size.
     *
     * Why so small: when ZXing is asked to render at a specific pixel size
     * (say 240×240) for a QR whose module count doesn't divide evenly into
     * that size, it pads the bitmap with white on all sides to keep modules
     * at integer pixel sizes. Even with MARGIN=0 in the encode hints this
     * leaves a visible 10–20 px white border that wastes our scarce screen
     * real estate. Rendering at 1 px per module gives us the smallest
     * possible bitmap that contains exactly the QR's modules and nothing
     * else, then the ImageView handles the upscale with no padding.
     *
     * Uses high error correction (level H, ~30% redundancy) so the receiving
     * phone's camera has more leeway with focus and motion blur on small,
     * cheap displays.
     */
    fun encode(payload: String, sizePx: Int): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
                EncodeHintType.MARGIN to 0,
            )
            // Pass a tiny target size so ZXing's renderer can't produce
            // multi-pixel modules; it will scale to fit our requested size
            // but the matrix dimensions still reflect the module count.
            // Then we discard those dimensions and find the actual content
            // bounding box below — that's what gives us 1 module = 1 pixel.
            val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val content = findContentBounds(matrix)
            if (content == null) {
                Log.w(TAG, "encode: matrix has no dark modules?")
                return null
            }

            // Sample one pixel per module by walking the matrix at the
            // module step computed from the bounding box. The bbox is in
            // matrix pixels; each module occupies (bbox.width / moduleCount)
            // matrix pixels, but we don't know moduleCount directly. We do
            // know that the QR's three position-detection patterns are
            // 7×7 module squares in the top-left, top-right, and bottom-
            // left corners — so the module pixel size equals (width of the
            // top-left position pattern) / 7. Walk along the top edge from
            // bbox.left until we hit a white pixel; the distance is one
            // position-pattern width.
            val moduleStep = detectModuleStep(matrix, content)
            if (moduleStep <= 0) {
                Log.w(TAG, "encode: failed to detect module step in matrix")
                return null
            }
            val moduleCountX = (content.width() + moduleStep / 2) / moduleStep
            val moduleCountY = (content.height() + moduleStep / 2) / moduleStep
            if (moduleCountX <= 0 || moduleCountY <= 0) {
                Log.w(TAG, "encode: bad module count ${moduleCountX}x${moduleCountY}")
                return null
            }

            val bmp = Bitmap.createBitmap(moduleCountX, moduleCountY, Bitmap.Config.ARGB_8888)
            for (my in 0 until moduleCountY) {
                for (mx in 0 until moduleCountX) {
                    // Sample the center of each module to be robust to
                    // off-by-one at the module/module boundary.
                    val sx = content.left + mx * moduleStep + moduleStep / 2
                    val sy = content.top + my * moduleStep + moduleStep / 2
                    val on = matrix.get(sx.coerceAtMost(matrix.width - 1), sy.coerceAtMost(matrix.height - 1))
                    bmp.setPixel(mx, my, if (on) Color.BLACK else Color.WHITE)
                }
            }
            Log.d(
                TAG,
                "encoded ${moduleCountX}x${moduleCountY}-module QR " +
                    "(source matrix ${matrix.width}x${matrix.height}, step=$moduleStep) " +
                    "for ${payload.length}-char payload",
            )
            bmp
        } catch (t: Throwable) {
            Log.e(TAG, "encode failed: ${t.message}", t)
            null
        }
    }

    /** Find the smallest rectangle in the BitMatrix that contains every
     *  dark module. Used to strip ZXing's white padding from the encoded
     *  bitmap. Returns null if the matrix has no dark modules. */
    private fun findContentBounds(matrix: com.google.zxing.common.BitMatrix): android.graphics.Rect? {
        var minX = matrix.width
        var minY = matrix.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        return android.graphics.Rect(minX, minY, maxX + 1, maxY + 1)
    }

    /** Detect the pixel size of one QR module by measuring the width of the
     *  top-left position-detection pattern, which is always 7 modules wide
     *  and solid dark on the outer edge. Walks right from the top-left
     *  corner of the content bbox until the first white pixel — that span
     *  is 7 modules. Returns 0 on failure. */
    private fun detectModuleStep(matrix: com.google.zxing.common.BitMatrix, content: android.graphics.Rect): Int {
        var run = 0
        var x = content.left
        while (x < matrix.width && matrix.get(x, content.top)) {
            run++
            x++
        }
        // Position pattern is 7 modules; divide and round.
        return (run + 3) / 7
    }

    private fun Bitmap.toLuminanceSource(): LuminanceSource? {
        return try {
            val pixels = IntArray(width * height)
            getPixels(pixels, 0, width, 0, 0, width, height)
            RGBLuminanceSource(width, height, pixels)
        } catch (t: Throwable) {
            Log.w(TAG, "toLuminanceSource failed for ${width}x${height}: ${t.message}")
            null
        }
    }
}
