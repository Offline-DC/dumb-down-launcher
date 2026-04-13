package com.offlineinc.dumbdownlauncher.contactsync.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.offlineinc.dumbdownlauncher.ui.components.DumbButton
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

@Composable
fun PermissionsCard(
    hasContactsPerm: Boolean,
    onGrantContactsPermission: () -> Unit
) {
    var buttonFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                DumbTheme.Colors.White.copy(alpha = 0.06f),
                RoundedCornerShape(DumbTheme.Corner.Medium)
            )
            .padding(DumbTheme.Spacing.CardPadding)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(
                text = "permissions",
                style = DumbTheme.Text.Title
            )

            BasicText(
                text = "needs Contacts permission to read/write contacts",
                style = DumbTheme.Text.Subtitle
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { buttonFocused = it.isFocused }
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && !hasContactsPerm) {
                            onGrantContactsPermission()
                            true
                        } else false
                    }
            ) {
                DumbButton(
                    text = "grant contacts permission",
                    focused = buttonFocused
                )
            }
        }
    }
}
