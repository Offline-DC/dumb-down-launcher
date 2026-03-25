package com.offlineinc.dumbdownlauncher.contactsync.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign

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

                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "connecting...",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "make sure Dumb Down app is open on Contact Sync",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // Connected — show Sync now button
            ui.isConnected && !ui.isSyncing -> {
                Text(
                    "connected!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))

                // Auto-focus the sync button when connected
                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                }

                Button(
                    onClick = onSyncNow,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        contentColor = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary
                    ),
                    border = if (focused) BorderStroke(3.dp, MaterialTheme.colorScheme.onPrimary)
                    else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = if (focused) 10.dp else 2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp)
                        .scale(if (focused) 1.03f else 1.0f)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.isFocused }
                ) {
                    Text("Sync now")
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

                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "syncing...",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (isOnboarding && ui.canClose) "contacts are being added to your phone.\npress any key to go to next step"
                    else if (ui.canClose) "contacts are being added to your phone.\nu can close this page, it may take several minutes"
                    else "keep this app open — downloading contacts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = if (isOnboarding && ui.canClose) {
                        Modifier
                            .focusRequester(syncFocusRequester)
                            .focusable()
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
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

                Text(
                    "transfer complete!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (isOnboarding) "press any key to go to next step"
                    else "u can close this page",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = if (isOnboarding) {
                        Modifier
                            .focusRequester(completeFocusRequester)
                            .focusable()
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
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
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
    }
}
