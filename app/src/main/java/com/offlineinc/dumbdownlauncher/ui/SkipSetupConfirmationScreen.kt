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
 * Brief informational screen shown after the user picks "skip setup"
 * on [LinkingChoiceScreen]. Tells them how to come back to device
 * setup later (via "device setup" in all apps) before dropping them
 * on the home screen.
 *
 * The skip flag has already been persisted by the caller, so OK here
 * just continues to the home screen.
 */
@Composable
fun SkipSetupConfirmationScreen(onOk: () -> Unit) {
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
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        onOk()
                        true
                    }
                    // Back is swallowed — there's nothing meaningful to go
                    // back to (the linking screen already routed us here
                    // and persisted the skip flag).
                    Key.Back -> true
                    else -> false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            BasicText(
                text = "to do device setup l8r (needed 4 smart txt " +
                       "& smartphone sync), select \"device setup\" in all apps",
                style = DumbTheme.Text.Body,
                modifier = Modifier.padding(bottom = 20.dp)
            )
            BasicText(
                text = "press ok to continue",
                style = DumbTheme.Text.Hint
            )
        }
    }
}
