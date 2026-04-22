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
        val Yellow = Color(0xFFFAF594)   // Brand highlight / selection (#FAF594)
        val Black  = Color(0xFF000000)   // Background
        val White  = Color(0xFFFFFFFF)   // Primary text
        val Gray   = Color(0xFFAAAAAA)   // Secondary / hint text
        val Red    = Color(0xFFE53935)   // Destructive actions (unpair, delete)
        val Green  = Color(0xFF00AA00)   // Positive / success accents
    }

    // ── Fonts ────────────────────────────────────────────────────────────
    //
    // Two-family system matching the Dumb Dumb apps:
    //  • [Header] — Cheltenham Extra Condensed Bold, used for titles and
    //    other display text.
    //  • [Body]   — Helvetica Now Text Black, used for body copy, buttons,
    //    labels, and everywhere else.

    /** Display / header font — Cheltenham Extra Condensed Bold. */
    val Header: FontFamily = FontFamily(Font(R.font.cheltenham_extra_condensed_bold))

    /** Body copy font — Helvetica Now Text Black. */
    val Body: FontFamily = FontFamily(Font(R.font.helvetica_now_text_black))

    /**
     * Legacy alias. The launcher previously used BioRhyme as its only font;
     * many call sites still reference [DumbTheme.BioRhyme] directly. It now
     * points at [Body] so existing usages automatically pick up the new
     * Helvetica body font without touching every file.
     */
    @Deprecated(
        "Use DumbTheme.Header for titles or DumbTheme.Body for body copy.",
        ReplaceWith("DumbTheme.Body")
    )
    val BioRhyme: FontFamily = Body

    // ── Text styles ──────────────────────────────────────────────────────
    //
    // Convention: every style includes the font family so callers never
    // have to think about it.  Colors default to the most common usage;
    // callers can `.copy(color = …)` when they need something different.

    @Immutable
    object Text {
        /** Large page title — 20 sp, white.  "link ur smart phone", etc. */
        val PageTitle = TextStyle(
            fontFamily = Header,
            fontSize = 20.sp,
            color = Colors.White
        )

        /** Section / card title — 18 sp, white. */
        val Title = TextStyle(
            fontFamily = Header,
            fontSize = 18.sp,
            color = Colors.White
        )

        /** Primary body copy — 16 sp, white. */
        val Body = TextStyle(
            fontFamily = DumbTheme.Body,
            fontSize = 16.sp,
            color = Colors.White
        )

        /** Secondary body / instructions — 14 sp, white. */
        val BodySmall = TextStyle(
            fontFamily = DumbTheme.Body,
            fontSize = 14.sp,
            color = Colors.White
        )

        /** Subtitle / description — 13 sp, gray. */
        val Subtitle = TextStyle(
            fontFamily = DumbTheme.Body,
            fontSize = 13.sp,
            color = Colors.Gray
        )

        /** Hint / helper text — 12 sp, gray. */
        val Hint = TextStyle(
            fontFamily = DumbTheme.Body,
            fontSize = 12.sp,
            color = Colors.Gray
        )

        /** Small labels — 11 sp, gray. Used for skip buttons, etc. */
        val Label = TextStyle(
            fontFamily = DumbTheme.Body,
            fontSize = 11.sp,
            color = Colors.Gray
        )

        /** Large list item labels — 24 sp, no default color. */
        val AppLabel = TextStyle(
            fontFamily = Header,
            fontSize = 24.sp
        )

        /** Primary button text — 16 sp, black (on yellow bg). */
        val Button = TextStyle(
            fontFamily = DumbTheme.Body,
            fontSize = 16.sp,
            color = Colors.Black
        )

        /** Small / secondary button text — 13 sp, white. */
        val ButtonSmall = TextStyle(
            fontFamily = DumbTheme.Body,
            fontSize = 13.sp,
            color = Colors.White
        )

        /** 14 sp, no default color. General-purpose reusable size. */
        val Small = TextStyle(
            fontFamily = DumbTheme.Body,
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
