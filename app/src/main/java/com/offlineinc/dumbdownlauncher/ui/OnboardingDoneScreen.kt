package com.offlineinc.dumbdownlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

/**
 * Final onboarding screen for users who don't need smart txt setup.
 * Just a simple confirmation before they start using the phone.
 */
@Composable
fun OnboardingDoneScreen(onOk: () -> Unit) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Back -> {
                        onOk()
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            BasicText(
                text = "click ok to start using ur phone",
                style = DumbTheme.Text.Body,
                modifier = Modifier.padding(bottom = 32.dp)
            )
            BasicText(
                text = "ok",
                style = DumbTheme.Text.AppLabel.copy(color = DumbTheme.Colors.Yellow)
            )
        }
    }
}
