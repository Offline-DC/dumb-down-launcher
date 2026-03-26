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
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

@Composable
fun PlatformChoiceDialog(
    onChoose: (String) -> Unit
) {
    val options = listOf("ios", "android", "skip")
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
                        onChoose(options[selectedIndex])
                        true
                    }
                    Key.Back -> {
                        onChoose("skipped")
                        true
                    }
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
                text = "what is ur smart phone?",
                style = DumbTheme.Text.PageTitle,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                val isSkip = option == "skip"
                val textColor = when {
                    isSelected -> DumbTheme.Colors.Yellow
                    isSkip -> DumbTheme.Colors.Gray
                    else -> DumbTheme.Colors.White
                }
                val style = if (isSkip) DumbTheme.Text.Subtitle else DumbTheme.Text.AppLabel

                BasicText(
                    text = if (isSelected) "> $option" else "  $option",
                    style = style.copy(color = textColor),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}
