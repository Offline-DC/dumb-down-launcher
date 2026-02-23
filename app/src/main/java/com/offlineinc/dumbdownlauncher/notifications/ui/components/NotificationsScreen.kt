package com.offlineinc.dumbdownlauncher.notifications.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.notifications.model.NotificationItem
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

@Composable
fun NotificationsScreen(
    items: List<NotificationItem>,
    onOpen: (NotificationItem) -> Unit,
    onDismiss: (NotificationItem) -> Unit,
    onClearAll: () -> Unit
) {
    val fontFamily = DumbTheme.BioRhyme
    val hasNotifications = items.isNotEmpty()

    var selectedIndex by remember { mutableIntStateOf(0) }
    var selectionActive by remember { mutableStateOf(false) }

    val clearAllFR = remember { FocusRequester() }
    val emptyFR = remember { FocusRequester() }
    val listFR = remember { FocusRequester() }

    var clearAllFocused by remember { mutableStateOf(false) }
    var listFocused by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    LaunchedEffect(items.size) {
        if (items.isEmpty()) {
            selectedIndex = 0
            selectionActive = false
        } else {
            selectedIndex = selectedIndex.coerceIn(0, items.lastIndex)
        }
    }

    LaunchedEffect(hasNotifications) {
        if (hasNotifications) {
            selectionActive = false
            clearAllFR.requestFocus()
        } else {
            selectionActive = false
            emptyFR.requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
            .padding(12.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                if (!hasNotifications) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.DirectionUp,
                        Key.DirectionDown,
                        Key.Enter,
                        Key.NumPadEnter,
                        Key.DirectionCenter -> true
                        else -> false
                    }
                }

                if (clearAllFocused) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.DirectionDown -> {
                            selectedIndex = 0
                            selectionActive = true
                            listFR.requestFocus()
                            true
                        }
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            onClearAll()
                            true
                        }
                        else -> false
                    }
                }

                if (listFocused) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.DirectionDown -> {
                            if (items.isNotEmpty()) {
                                val newIndex = (selectedIndex + 1).coerceIn(0, items.lastIndex)
                                if (newIndex != selectedIndex) selectedIndex = newIndex
                                true
                            } else true
                        }
                        Key.DirectionUp -> {
                            if (selectedIndex == 0) {
                                selectionActive = false
                                clearAllFR.requestFocus()
                                true
                            } else {
                                selectedIndex = (selectedIndex - 1).coerceIn(0, items.lastIndex)
                                true
                            }
                        }
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            if (items.isNotEmpty()) onOpen(items[selectedIndex])
                            true
                        }
                        Key.Delete -> {
                            if (items.isNotEmpty()) onDismiss(items[selectedIndex])
                            true
                        }
                        else -> false
                    }
                }

                false
            }
    ) {
        BasicText(
            text = "Notifications",
            style = TextStyle(
                color = DumbTheme.Colors.White,
                fontSize = 18.sp,
                fontFamily = fontFamily
            ),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (hasNotifications) {
            ClearAllButton(
                modifier = Modifier
                    .focusRequester(clearAllFR)
                    .onFocusChanged { state ->
                        clearAllFocused = state.isFocused
                        if (state.isFocused) selectionActive = false
                    },
                fontFamily = fontFamily,
                onClick = onClearAll
            )

            Spacer(Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusRequester(listFR)
                    .onFocusChanged { listFocused = it.isFocused }
                    .focusable()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(items, key = { _, it -> it.key }) { index, item ->
                        NotificationRow(
                            item = item,
                            selected = selectionActive && index == selectedIndex,
                            fontFamily = fontFamily,
                            onClick = { onOpen(item) }
                        )
                    }
                }
            }

            LaunchedEffect(selectedIndex) {
                if (items.isNotEmpty()) listState.animateScrollToItem(selectedIndex)
            }
        } else {
            EmptyState(
                modifier = Modifier
                    .focusRequester(emptyFR)
                    .focusable(),
                fontFamily = fontFamily
            )
            Spacer(Modifier.weight(1f))
        }
    }
}
