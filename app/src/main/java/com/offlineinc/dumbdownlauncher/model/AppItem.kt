package com.offlineinc.dumbdownlauncher.model

import android.content.ComponentName
import android.graphics.drawable.Drawable

data class AppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val launchComponent: ComponentName?,
    val isMuted: Boolean = false,
    val isToggleOn: Boolean? = null   // non-null → renders as a toggle row
)