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
 * First onboarding screen — "r u linking smartphone?"
 *
 * Users who link get the full pairing + contact sync flow.
 * Users who don't link still pick their messaging app and go
 * straight to the mouse tutorial (smart txt works, just no sync).
 *
 * [onChoose] is called with true (yes, linking) or false (no, not linking).
 */
@Composable
fun LinkingChoiceScreen(
    onChoose: (Boolean) -> Unit
) {
    val options = listOf("yes" to true, "no" to false)

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
                    Key.Back -> true // swallow — first screen, nowhere to go back to
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
                text = "r u linking smartphone?",
                style = DumbTheme.Text.PageTitle,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            options.forEachIndexed { index, (label, _) ->
                val isSelected = index == selectedIndex
                val textColor = if (isSelected) DumbTheme.Colors.Yellow else DumbTheme.Colors.Gray

                BasicText(
                    text = if (isSelected) "> $label" else "  $label",
                    style = DumbTheme.Text.AppLabel.copy(
                        color = textColor,
                        fontSize = 18.sp
                    ),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}
