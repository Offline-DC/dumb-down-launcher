package com.offlineinc.dumbdownlauncher.ui.mousetutorial

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.offlineinc.dumbdownlauncher.MouseAccessibilityService
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme
import kotlinx.coroutines.delay

/** Step 5 — terminal "done" screen.
 *
 *  On entry the mouse accessibility service is force-disabled (the
 *  tutorial is over and we don't want the cursor lingering into the next
 *  screen).  We give the daemon ~300 ms to release its keyboard grab
 *  before requesting focus, otherwise [onPreviewKeyEvent] wouldn't
 *  receive key events.  Both OK / center key presses and any mouse click
 *  dismiss the screen via [onDismiss]. */
@Composable
internal fun DoneStep(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    // Disable mouse — tutorial is over.  Then grab focus so hardware
    // keys (now un-grabbed by the daemon) reach onPreviewKeyEvent.
    LaunchedEffect(Unit) {
        MouseAccessibilityService.forceDisable(context)
        // Small delay so the daemon releases the keyboard grab before
        // we try to receive key events.
        delay(300)
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    event.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                ) {
                    onDismiss()
                    true
                } else false
            }
            // Also support mouse click in case the cursor is still visible
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text = "u got it!\ntime 4 smart txt",
                // Device setup titles use the Helvetica body font — see
                // LinkingChoiceScreen for rationale.
                style = DumbTheme.Text.PageTitle.copy(
                    fontFamily = DumbTheme.Body,
                    color = DumbTheme.Colors.Yellow,
                    textAlign = TextAlign.Center
                )
            )
            Spacer(modifier = Modifier.height(16.dp))
            BasicText(
                text = "press OK to start smart txt setup.\nUse Dumb Down app for help!",
                style = DumbTheme.Text.Hint.copy(textAlign = TextAlign.Center)
            )
        }
    }
}

// ── Previews ────────────────────────────────────────────────────────────
// [MouseAccessibilityService.forceDisable] runs in the preview's
// LaunchedEffect but safely no-ops (the `su` call fails silently in the
// preview sandbox), so the preview renders cleanly.

@Preview(
    name = "Step 5 — Done",
    widthDp = 240,
    heightDp = 320,
    showBackground = true,
    backgroundColor = 0xFF000000
)
@Composable
private fun DoneStepPreview() {
    Box(modifier = Modifier.background(DumbTheme.Colors.Black)) {
        DoneStep(onDismiss = {})
    }
}

