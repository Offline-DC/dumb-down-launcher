package com.offlineinc.dumbdownlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

/**
 * First onboarding screen — "what do u want?"
 *
 * Lets the user pick their messaging setup before anything else.
 * [onChoose] is called with one of:
 *   "ios"     — iMessage via OpenBubbles
 *   "android" — Google Messages via Chrome
 *   "none"    — super dumb mode, no smart text or type sync
 */
@Composable
fun IntentChoiceScreen(
    onChoose: (String) -> Unit
) {
    // Pairs of (display label, internal value)
    val options = listOf(
        "ios"             to "ios",
        "android"         to "android",
        "none - super dumb" to "none",
    )

    var selectedIndex by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                        true
                    }
                    Key.DirectionDown -> {
                        selectedIndex = (selectedIndex + 1).coerceAtMost(options.lastIndex)
                        true
                    }
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        onChoose(options[selectedIndex].second)
                        true
                    }
                    Key.Back -> true // swallow back — no previous screen to go to
                    else -> false
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            BasicText(
                text = "what texts are u syncing?",
                // Device setup titles use the Helvetica body font — see
                // LinkingChoiceScreen for rationale.
                style = DumbTheme.Text.PageTitle.copy(fontFamily = DumbTheme.Body),
                modifier = Modifier.padding(bottom = 24.dp)
            )

            options.forEachIndexed { index, (label, value) ->
                val isSelected = index == selectedIndex
                val textColor = if (isSelected) DumbTheme.Colors.Yellow else DumbTheme.Colors.Gray
                // Device setup options use the Helvetica body font to match
                // the device setup title — see the title above.
                val style = DumbTheme.Text.AppLabel.copy(
                    fontFamily = DumbTheme.Body,
                    fontSize = 18.sp
                )

                BasicText(
                    text = if (isSelected) "> $label" else "  $label",
                    style = style.copy(color = textColor),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}
