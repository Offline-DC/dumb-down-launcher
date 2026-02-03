package com.offlineinc.dumbdownlauncher.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.R

/**
 * Universal Dumb Phone UI theme
 * Used by: App List, Notifications, and future screens
 */
object DumbTheme {

    /**
     * BRAND COLORS
     */
    @Immutable
    object Colors {
        val Yellow = Color(0xFFFFD400)   // Brand highlight
        val Black = Color(0xFF000000)    // Background
        val White = Color(0xFFFFFFFF)    // Primary text
        val Gray  = Color(0xFFAAAAAA)    // Secondary text
    }

    /**
     * TYPOGRAPHY
     * Uses your existing res/font/syne_mono.xml
     */
    val SyneMono = FontFamily(
        Font(R.font.syne_mono) // This references your XML font-family file
    )

    /**
     * COMMON TEXT STYLES
     */
    @Immutable
    object Text {
        val Title = TextStyle(
            fontFamily = SyneMono,
            fontSize = 18.sp,
            color = Colors.White
        )

        val AppLabel = TextStyle(
            fontFamily = SyneMono,
            fontSize = 24.sp
        )

        val Body = TextStyle(
            fontFamily = SyneMono,
            fontSize = 16.sp
        )

        val Small = TextStyle(
            fontFamily = SyneMono,
            fontSize = 14.sp
        )
    }
}
