package com.offlineinc.dumbdownlauncher.contactsync.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.offlineinc.dumbdownlauncher.ui.components.DumbButton
import com.offlineinc.dumbdownlauncher.ui.components.DumbSpinner
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

@Composable
fun SyncNowSection(
    ui: HomeUiState,
    isOnboarding: Boolean = false,
    onSyncNow: () -> Unit,
    onClearMessages: () -> Unit,
    onFinish: () -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val trapFocusRequester = remember { FocusRequester() }

    DisposableEffect(Unit) {
        onDispose { onClearMessages() }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        when {
            // Connecting state — waiting for both devices
            ui.isConnecting -> {
                // Invisible focus trap so D-pad doesn't land on Unpair
                LaunchedEffect(Unit) { trapFocusRequester.requestFocus() }
                Box(Modifier.size(0.dp).focusRequester(trapFocusRequester).focusable())

                DumbSpinner()
                Spacer(Modifier.height(8.dp))
                BasicText(
                    text = "connecting...",
                    style = DumbTheme.Text.Subtitle.copy(color = DumbTheme.Colors.White, textAlign = TextAlign.Center)
                )
                Spacer(Modifier.height(4.dp))
                BasicText(
                    text = "make sure Dumb Down app is open on Contact Sync",
                    style = DumbTheme.Text.Hint.copy(textAlign = TextAlign.Center)
                )
            }

            // Connected — show Sync now button
            ui.isConnected && !ui.isSyncing -> {
                BasicText(
                    text = "connected!",
                    style = DumbTheme.Text.Subtitle.copy(color = DumbTheme.Colors.Yellow, textAlign = TextAlign.Center)
                )
                Spacer(Modifier.height(8.dp))

                // Auto-focus the sync button when connected
                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                }

                Box(
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.isFocused }
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                event.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                            ) {
                                onSyncNow()
                                true
                            } else false
                        }
                ) {
                    DumbButton(text = "sync now", focused = focused)
                }
            }

            // Syncing in progress
            ui.isSyncing -> {
                val syncFocusRequester = remember { FocusRequester() }
                LaunchedEffect(ui.canClose, isOnboarding) {
                    if (isOnboarding && ui.canClose) {
                        syncFocusRequester.requestFocus()
                    } else {
                        trapFocusRequester.requestFocus()
                    }
                }
                Box(Modifier.size(0.dp).focusRequester(trapFocusRequester).focusable())

                DumbSpinner()
                Spacer(Modifier.height(8.dp))
                BasicText(
                    text = "syncing...",
                    style = DumbTheme.Text.Subtitle.copy(color = DumbTheme.Colors.White, textAlign = TextAlign.Center)
                )
                Spacer(Modifier.height(4.dp))
                BasicText(
                    text = if (isOnboarding && ui.canClose) "contacts are being added to your phone.\npress OK to go to next step"
                    else if (ui.canClose) "contacts are being added to your phone.\nu can close this page, it may take several minutes"
                    else "keep this app open — downloading contacts",
                    style = DumbTheme.Text.Hint.copy(textAlign = TextAlign.Center),
                    modifier = if (isOnboarding && ui.canClose) {
                        Modifier
                            .focusRequester(syncFocusRequester)
                            .focusable()
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    event.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                                ) {
                                    onFinish()
                                    true
                                } else false
                            }
                    } else Modifier
                )
            }

            // Sync complete
            ui.syncComplete -> {
                val completeFocusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    if (isOnboarding) completeFocusRequester.requestFocus()
                    else trapFocusRequester.requestFocus()
                }
                Box(Modifier.size(0.dp).focusRequester(trapFocusRequester).focusable())

                BasicText(
                    text = "transfer complete!",
                    style = DumbTheme.Text.Subtitle.copy(color = DumbTheme.Colors.Yellow, textAlign = TextAlign.Center)
                )
                Spacer(Modifier.height(4.dp))
                BasicText(
                    text = if (isOnboarding) "press OK to go to next step"
                    else "u can close this page",
                    style = DumbTheme.Text.Hint.copy(textAlign = TextAlign.Center),
                    modifier = if (isOnboarding) {
                        Modifier
                            .focusRequester(completeFocusRequester)
                            .focusable()
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    event.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                                ) {
                                    onFinish()
                                    true
                                } else false
                            }
                    } else Modifier
                )
            }
        }

        // Error display
        ui.error?.let {
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = it,
                style = DumbTheme.Text.Subtitle.copy(
                    color = DumbTheme.Colors.Yellow,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}
