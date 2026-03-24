package com.offlineinc.dumbdownlauncher.contactsync.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PermissionsCard(
    hasContactsPerm: Boolean,
    onGrantContactsPermission: () -> Unit
) {
    var buttonFocused by remember { mutableStateOf(false) }

    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Permissions", style = MaterialTheme.typography.titleMedium)

            Text(
                "Needs Contacts permission to read/write contacts",
                style = MaterialTheme.typography.bodyMedium
            )

            Button(
                onClick = onGrantContactsPermission,
                enabled = !hasContactsPerm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (buttonFocused) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondary,
                    contentColor = if (buttonFocused) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSecondary
                ),
                border = if (buttonFocused) {
                    BorderStroke(3.dp, MaterialTheme.colorScheme.onPrimary)
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                },
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = if (buttonFocused) 10.dp else 2.dp
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .scale(if (buttonFocused) 1.03f else 1.0f)
                    .onFocusChanged { buttonFocused = it.isFocused }
            ) {
                Text("Grant contacts permission", fontSize = 13.sp)
            }
        }
    }
}
