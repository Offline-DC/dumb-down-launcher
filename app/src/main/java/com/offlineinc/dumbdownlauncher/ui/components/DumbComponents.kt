package com.offlineinc.dumbdownlauncher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

// ── Skip / action chip ────────────────────────────────────────────────

/**
 * Small top-corner button used throughout onboarding for "skip", "unpair", etc.
 *
 * @param text  Label, e.g. "skip" or "unpair"
 * @param focusRequester  Caller-owned [FocusRequester] so the parent can
 *                        programmatically move focus here (e.g. on D-pad Up).
 * @param focusedBg       Background color when focused (yellow for skip, red for unpair).
 * @param isFocused       Externally-tracked focus state.
 * @param onFocusChanged  Reports focus changes back to the caller.
 * @param modifier        Alignment / padding from the parent (e.g. `Alignment.TopEnd`).
 */
@Composable
fun DumbChipButton(
    text: String,
    focusRequester: FocusRequester,
    focusedBg: Color = DumbTheme.Colors.Yellow,
    focusedTextColor: Color = DumbTheme.Colors.Black,
    isFocused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val textColor = if (isFocused) focusedTextColor else DumbTheme.Colors.Gray
    Box(
        modifier = modifier
            .padding(top = 6.dp, end = 8.dp, start = 8.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .focusable()
            .then(
                if (isFocused) Modifier
                    .background(focusedBg, RoundedCornerShape(DumbTheme.Corner.Small))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                else Modifier
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
    ) {
        BasicText(
            text = text,
            style = DumbTheme.Text.Label.copy(color = textColor)
        )
    }
}

// ── Primary button ────────────────────────────────────────────────────

/**
 * Full-width button with yellow background when focused, dim when not.
 * Matches the "next" / "sync now" pattern used across onboarding.
 */
@Composable
fun DumbButton(
    text: String,
    focused: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(
                if (focused) DumbTheme.Colors.Yellow
                else DumbTheme.Colors.White.copy(alpha = 0.08f),
                RoundedCornerShape(DumbTheme.Corner.Medium)
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            style = DumbTheme.Text.Button.copy(
                color = if (focused) DumbTheme.Colors.Black else DumbTheme.Colors.White
            )
        )
    }
}

// ── Spinner ───────────────────────────────────────────────────────────

/**
 * Minimal yellow-on-black circular spinner for loading / connecting states.
 * Uses a simple rotating arc via Canvas since we avoid Material3 in the
 * DumbTheme world.
 */
@Composable
fun DumbSpinner(modifier: Modifier = Modifier) {
    // Reuse the Material3 indicator for now — it's the one dependency
    // we keep until a custom Canvas spinner is warranted.  The color
    // is overridden to match the brand.
    androidx.compose.material3.CircularProgressIndicator(
        strokeWidth = 2.dp,
        color = DumbTheme.Colors.Yellow,
        trackColor = DumbTheme.Colors.White.copy(alpha = 0.1f),
        modifier = modifier.size(24.dp)
    )
}
