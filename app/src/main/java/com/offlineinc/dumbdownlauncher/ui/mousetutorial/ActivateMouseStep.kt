package com.offlineinc.dumbdownlauncher.ui.mousetutorial

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.MouseAccessibilityService
import com.offlineinc.dumbdownlauncher.ui.components.DumbChipButton
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Step 1 — teaches the user to press ✱ on the physical keypad to
 *  activate the FlipMouse cursor.  Polls the daemon every 500 ms;
 *  invokes [onActivated] the first time the service reports enabled.
 *  A top-right "skip" chip lets the user bypass the tutorial entirely.
 */
@Composable
internal fun ActivateMouseStep(
    onActivated: () -> Unit,
    onSkip: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val skipFocusRequester = remember { FocusRequester() }
    var skipFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Poll the FlipMouse daemon status every 500 ms
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            val enabled = withContext(Dispatchers.IO) {
                MouseAccessibilityService.queryMouseEnabled()
            }
            if (enabled) {
                onActivated()
                break
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (skipFocused) {
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            onSkip(); true
                        }
                        Key.DirectionDown, Key.Back -> {
                            focusRequester.requestFocus(); true
                        }
                        else -> false
                    }
                } else {
                    when (event.key) {
                        Key.DirectionUp -> {
                            skipFocusRequester.requestFocus(); true
                        }
                        else -> false
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BasicText(
                text = "Getting around ur phone.",
                // Device setup titles use the Helvetica body font — see
                // LinkingChoiceScreen for rationale.
                style = DumbTheme.Text.Title.copy(
                    fontFamily = DumbTheme.Body,
                    fontSize = 28.sp
                ),
                modifier = Modifier.padding(bottom = 16.dp)
            )
            BasicText(
                text = "The dumb mouse helps u navigate advanced apps.",
                style = DumbTheme.Text.Hint.copy(fontSize = 18.sp),
                modifier = Modifier.padding(bottom = 16.dp)
            )
            BasicText(
                text = "Press ★ (above keypad) to activate the mouse.",
                style = DumbTheme.Text.BodySmall.copy(fontSize = 20.sp)
            )
        }

        // Skip — top right (shared component)
        DumbChipButton(
            text = "skip",
            focusRequester = skipFocusRequester,
            isFocused = skipFocused,
            onFocusChanged = { skipFocused = it },
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}

// ── Previews ────────────────────────────────────────────────────────────
// Sized to roughly match the TCL Flip 2 main display (~240×320 dp).
// The mouse-status poll inside this composable safely returns false in
// Android Studio's preview because [MouseAccessibilityService.queryMouseEnabled]
// swallows all throwables (no `su` binary in the preview sandbox).

@Preview(
    name = "Step 1 — Activate mouse",
    widthDp = 240,
    heightDp = 320,
    showBackground = true,
    backgroundColor = 0xFF000000
)
@Composable
private fun ActivateMouseStepPreview() {
    Box(modifier = Modifier.background(DumbTheme.Colors.Black)) {
        ActivateMouseStep(onActivated = {}, onSkip = {})
    }
}

