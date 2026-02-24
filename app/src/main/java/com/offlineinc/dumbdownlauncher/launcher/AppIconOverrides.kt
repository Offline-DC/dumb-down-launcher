// launcher/AppIconOverrides.kt
package com.offlineinc.dumbdownlauncher.launcher

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.offlineinc.dumbdownlauncher.R

object AppIconOverrides {
    fun getIcon(
        context: Context,
        packageName: String,
        defaultIcon: Drawable
    ): Drawable {
        val overrideRes = when (packageName) {
            "com.android.settings" -> R.drawable.mo_gear

            "com.ubercab",
            "com.offline.uberlauncher" -> R.drawable.ic_uber_car

            else -> null
        }

        return overrideRes?.let { ContextCompat.getDrawable(context, it) } ?: defaultIcon
    }
}