package com.offlineinc.dumbdownlauncher.contactsync.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

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
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "Contact Sync",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            if (ui.flipPhoneNumber != null) "Paired with ${formatPhoneNumber(ui.flipPhoneNumber)}"
            else "Paired with smart phone",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
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

        Spacer(Modifier.height(2.dp))

        // Contact counts
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (ui.contactCountLoaded) {
                    Text("${ui.totalContactCount} contacts on ur dumb phone", style = MaterialTheme.typography.bodyMedium)
                }
                val lastText = remember(ui.lastSyncMillis) {
                    if (ui.lastSyncMillis <= 0L) "Last sync: never"
                    else {
                        val diff = System.currentTimeMillis() - ui.lastSyncMillis
                        val min = diff / 60000
                        val hr = min / 60
                        val d = hr / 24
                        when {
                            min < 1 -> "Last sync: just now"
                            min < 60 -> "Last sync: ${min}m ago"
                            hr < 24 -> "Last sync: ${hr}h ago"
                            else -> "Last sync: ${d}d ago"
                        }
                    }
                }
                Text(lastText, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(2.dp))

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
