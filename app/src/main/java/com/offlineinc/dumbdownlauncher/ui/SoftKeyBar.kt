package com.offlineinc.dumbdownlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

/**
 * Reusable soft-key label bar shown at the bottom of screens.
 *
 * Displays up to three labels aligned left / center / right, matching the
 * physical soft-left, center, and soft-right keys on the TCL handset.
 *
 * Pass `null` or an empty string for any slot to leave it blank while
 * keeping the layout stable (each slot still occupies its weight).
 */
@Composable
fun SoftKeyBar(
    leftLabel: String? = null,
    centerLabel: String? = null,
    rightLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = leftLabel.orEmpty(),
            modifier = Modifier.weight(1f),
            style = TextStyle(
                color = DumbTheme.Colors.Yellow.copy(alpha = 0.65f),
                fontSize = 12.sp,
                fontFamily = DumbTheme.BioRhyme,
                textAlign = TextAlign.Start,
            ),
        )
        BasicText(
            text = centerLabel.orEmpty(),
            modifier = Modifier.weight(1f),
            style = TextStyle(
                color = DumbTheme.Colors.Yellow.copy(alpha = 0.65f),
                fontSize = 12.sp,
                fontFamily = DumbTheme.BioRhyme,
                textAlign = TextAlign.Center,
            ),
        )
        BasicText(
            text = rightLabel.orEmpty(),
            modifier = Modifier.weight(1f),
            style = TextStyle(
                color = DumbTheme.Colors.Yellow.copy(alpha = 0.65f),
                fontSize = 12.sp,
                fontFamily = DumbTheme.BioRhyme,
                textAlign = TextAlign.End,
            ),
        )
    }
}
