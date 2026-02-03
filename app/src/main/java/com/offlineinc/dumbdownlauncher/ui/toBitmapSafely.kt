package com.offlineinc.dumbdownlauncher.ui

import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap

fun Drawable.toBitmapSafely(w: Int, h: Int) = try {
    this.toBitmap(width = w, height = h)
} catch (_: Exception) {
    null
}
