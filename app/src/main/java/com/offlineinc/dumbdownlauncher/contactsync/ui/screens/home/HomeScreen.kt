package com.offlineinc.dumbdownlauncher.contactsync.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

private fun formatPhoneNumber(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    // Handle US numbers: strip leading 1 if 11 digits
    val local = if (digits.length == 11 && digits.startsWith("1")) digits.drop(1) else digits
    return if (local.length == 10) {
        "${local.substring(0, 3)}-${local.substring(3, 6)}-${local.substring(6)}"
    } else {
        raw
    }
}

@Composable
fun HomeScreen(
    ui: HomeUiState,
    isOnboarding: Boolean = false,
    onGrantContactsPermission: () -> Unit,
    onSyncNow: () -> Unit,
    onClearMessages: () -> Unit,
    onFinish: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
            .padding(horizontal = DumbTheme.Spacing.ScreenPaddingH, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BasicText(
            text = "contact sync",
            style = DumbTheme.Text.PageTitle.copy(textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth()
        )

        BasicText(
            text = if (ui.flipPhoneNumber != null) "paired with ${formatPhoneNumber(ui.flipPhoneNumber)}"
            else "paired with smart phone",
            style = DumbTheme.Text.Subtitle.copy(color = DumbTheme.Colors.Yellow)
        )

        when (ui.hasContactsPerm) {
            null -> {
                Spacer(Modifier.height(1.dp))
                return@Column
            }
            false -> {
                PermissionsCard(
                    hasContactsPerm = false,
                    onGrantContactsPermission = onGrantContactsPermission
                )
                return@Column
            }
            true -> {}
        }

        Spacer(Modifier.height(4.dp))

        // Contact counts
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    DumbTheme.Colors.White.copy(alpha = 0.06f),
                    RoundedCornerShape(DumbTheme.Corner.Medium)
                )
                .padding(DumbTheme.Spacing.CardPadding)
        ) {
            Column {
                if (ui.contactCountLoaded) {
                    BasicText(
                        text = "${ui.totalContactCount} contacts on ur dumb phone",
                        style = DumbTheme.Text.Subtitle.copy(color = DumbTheme.Colors.White)
                    )
                }
                val lastText = remember(ui.lastSyncMillis) {
                    if (ui.lastSyncMillis <= 0L) "last sync: never"
                    else {
                        val diff = System.currentTimeMillis() - ui.lastSyncMillis
                        val min = diff / 60000
                        val hr = min / 60
                        val d = hr / 24
                        when {
                            min < 1 -> "last sync: just now"
                            min < 60 -> "last sync: ${min}m ago"
                            hr < 24 -> "last sync: ${hr}h ago"
                            else -> "last sync: ${d}d ago"
                        }
                    }
                }
                BasicText(
                    text = lastText,
                    style = DumbTheme.Text.Hint
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Connection + sync state
        SyncNowSection(
            ui = ui,
            isOnboarding = isOnboarding,
            onSyncNow = onSyncNow,
            onClearMessages = onClearMessages,
            onFinish = onFinish
        )
    }
}
