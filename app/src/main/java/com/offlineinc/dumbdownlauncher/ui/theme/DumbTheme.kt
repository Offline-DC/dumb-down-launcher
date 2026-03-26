package com.offlineinc.dumbdownlauncher.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.R

/**
 * Universal Dumb Phone UI theme.
 *
 * ALL screens in the launcher should reference these values rather than
 * hard-coding colors, fonts, or sizes.  When we want to swap the visual
 * identity later, this single file is the only thing that changes.
 */
object DumbTheme {

    // ── Colors ───────────────────────────────────────────────────────────

    @Immutable
    object Colors {
        val Yellow = Color(0xFFFFD400)   // Brand highlight / selection
        val Black  = Color(0xFF000000)   // Background
        val White  = Color(0xFFFFFFFF)   // Primary text
        val Gray   = Color(0xFFAAAAAA)   // Secondary / hint text
        val Red    = Color(0xFFE53935)   // Destructive actions (unpair, delete)
        val Green  = Color(0xFF00AA00)   // Positive / success accents
    }

    // ── Font ─────────────────────────────────────────────────────────────

    val BioRhyme: FontFamily = FontFamily(Font(R.font.bio_rhyme))

    // ── Text styles ──────────────────────────────────────────────────────
    //
    // Convention: every style includes the font family so callers never
    // have to think about it.  Colors default to the most common usage;
    // callers can `.copy(color = …)` when they need something different.

    @Immutable
    object Text {
        /** Large page title — 20 sp, white.  "link ur smart phone", etc. */
        val PageTitle = TextStyle(
            fontFamily = BioRhyme,
            fontSize = 20.sp,
            color = Colors.White
        )

        /** Section / card title — 18 sp, white. */
        val Title = TextStyle(
            fontFamily = BioRhyme,
            fontSize = 18.sp,
            color = Colors.White
        )

        /** Primary body copy — 16 sp, white. */
        val Body = TextStyle(
            fontFamily = BioRhyme,
            fontSize = 16.sp,
            color = Colors.White
        )

        /** Secondary body / instructions — 14 sp, white. */
        val BodySmall = TextStyle(
            fontFamily = BioRhyme,
            fontSize = 14.sp,
            color = Colors.White
        )

        /** Subtitle / description — 13 sp, gray. */
        val Subtitle = TextStyle(
            fontFamily = BioRhyme,
            fontSize = 13.sp,
            color = Colors.Gray
        )

        /** Hint / helper text — 12 sp, gray. */
        val Hint = TextStyle(
            fontFamily = BioRhyme,
            fontSize = 12.sp,
            color = Colors.Gray
        )

        /** Small labels — 11 sp, gray. Used for skip buttons, etc. */
        val Label = TextStyle(
            fontFamily = BioRhyme,
            fontSize = 11.sp,
            color = Colors.Gray
        )

        /** Large list item labels — 24 sp, no default color. */
        val AppLabel = TextStyle(
            fontFamily = BioRhyme,
            fontSize = 24.sp
        )

        /** Primary button text — 16 sp, black (on yellow bg). */
        val Button = TextStyle(
            fontFamily = BioRhyme,
            fontSize = 16.sp,
            color = Colors.Black
        )

        /** Small / secondary button text — 13 sp, white. */
        val ButtonSmall = TextStyle(
            fontFamily = BioRhyme,
            fontSize = 13.sp,
            color = Colors.White
        )

        /** 14 sp, no default color. General-purpose reusable size. */
        val Small = TextStyle(
            fontFamily = BioRhyme,
            fontSize = 14.sp
        )
    }

    // ── Spacing & sizing constants ───────────────────────────────────────

    @Immutable
    object Spacing {
        val ScreenPaddingH = 24.dp
        val ScreenPaddingV = 16.dp
        val CardPadding    = 12.dp
        val ItemGap        = 8.dp
        val SectionGap     = 16.dp
    }

    @Immutable
    object Corner {
        val Small  = 4.dp
        val Medium = 8.dp
    }
}
