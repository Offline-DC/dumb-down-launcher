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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

@Composable
fun RestartPhoneDialog(
    onRestart: () -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf("restart now", "not now")
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
                        if (selectedIndex == 0) onRestart() else onDismiss()
                        true
                    }
                    Key.Back -> {
                        onDismiss()
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
                text = "restart phone for",
                style = TextStyle(
                    fontFamily = DumbTheme.BioRhyme,
                    fontSize = 20.sp,
                    color = DumbTheme.Colors.White
                )
            )
            BasicText(
                text = "full effect",
                style = TextStyle(
                    fontFamily = DumbTheme.BioRhyme,
                    fontSize = 20.sp,
                    color = DumbTheme.Colors.White
                ),
                modifier = Modifier.padding(bottom = 24.dp)
            )

            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                val isNotNow = option == "not now"
                BasicText(
                    text = if (isSelected) "> $option" else "  $option",
                    style = TextStyle(
                        fontFamily = DumbTheme.BioRhyme,
                        fontSize = if (isNotNow) 16.sp else 24.sp,
                        color = when {
                            isSelected -> DumbTheme.Colors.Yellow
                            isNotNow -> DumbTheme.Colors.Gray
                            else -> DumbTheme.Colors.White
                        }
                    ),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}
