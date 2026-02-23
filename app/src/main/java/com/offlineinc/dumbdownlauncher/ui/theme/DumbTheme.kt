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

    val BioRhyme = FontFamily(
        Font(R.font.bio_rhyme) // This references your XML font-family file
    )

    /**
     * COMMON TEXT STYLES
     */
    @Immutable
    object Text {
        val Title = TextStyle(
            fontFamily = BioRhyme,
            fontSize = 18.sp,
            color = Colors.White
        )

        val AppLabel = TextStyle(
            fontFamily = BioRhyme,
            fontSize = 24.sp
        )

        val Body = TextStyle(
            fontFamily = BioRhyme,
            fontSize = 16.sp
        )

        val Small = TextStyle(
            fontFamily = BioRhyme,
            fontSize = 14.sp
        )
    }
}
