package com.offlineinc.dumbdownlauncher.notifications.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.notifications.model.NotificationItem
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

private val Yellow = Color(0xFFFFD400)
private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)
private val Gray = Color(0xFFAAAAAA)

@Composable
fun NotificationsScreen(
    items: List<NotificationItem>,
    onOpen: (NotificationItem) -> Unit,
    onDismiss: (NotificationItem) -> Unit,
    onClearAll: () -> Unit
) {
    val fontFamily = DumbTheme.BioRhyme

    val hasNotifications = items.isNotEmpty()

    // Selection state (replaces adapter selection logic)
    var selectedIndex by remember { mutableIntStateOf(0) }
    var selectionActive by remember { mutableStateOf(false) }

    // Focus state
    val clearAllFR = remember { FocusRequester() }
    val emptyFR = remember { FocusRequester() }
    val listFR = remember { FocusRequester() }

    var clearAllFocused by remember { mutableStateOf(false) }
    var listFocused by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Clamp selection when list changes
    LaunchedEffect(items.size) {
        if (items.isEmpty()) {
            selectedIndex = 0
            selectionActive = false
        } else {
            selectedIndex = selectedIndex.coerceIn(0, items.lastIndex)
        }
    }

    // Default focus behavior
    LaunchedEffect(hasNotifications) {
        if (hasNotifications) {
            // Start on Clear All and ensure no row highlight
            selectionActive = false
            clearAllFR.requestFocus()
        } else {
            // Empty state focus
            selectionActive = false
            emptyFR.requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .padding(12.dp)
            // Root key handler so DPAD works regardless of which child is focused
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                // If empty, swallow navigation/select keys like your old code
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

                // CLEAR ALL focused behavior
                if (clearAllFocused) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.DirectionDown -> {
                            // Enter list, highlight row 0
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

                // LIST focused behavior
                if (listFocused) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.DirectionDown -> {
                            if (items.isNotEmpty()) {
                                val newIndex = (selectedIndex + 1).coerceIn(0, items.lastIndex)
                                if (newIndex != selectedIndex) {
                                    selectedIndex = newIndex
                                }
                                true
                            } else true
                        }
                        Key.DirectionUp -> {
                            if (selectedIndex == 0) {
                                // Go back to Clear All, remove highlight
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
            text = "notifications",
            style = TextStyle(
                color = White,
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
                        if (state.isFocused) {
                            // While Clear All is focused, NEVER highlight a row.
                            selectionActive = false
                        }
                    },
                fontFamily = fontFamily,
                onClick = onClearAll
            )

            Spacer(Modifier.height(10.dp))

            // LazyColumn container is focusable so we can move focus into it
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

            // Keep selected item visible when it changes
            LaunchedEffect(selectedIndex) {
                if (items.isNotEmpty()) {
                    listState.animateScrollToItem(selectedIndex)
                }
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

@Composable
private fun ClearAllButton(
    modifier: Modifier,
    fontFamily: FontFamily,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .wrapContentWidth()
            .background(if (focused) Yellow else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
    ) {
        BasicText(
            text = "clear all",
            style = TextStyle(
                color = if (focused) Black else Yellow,
                fontSize = 16.sp,
                fontFamily = fontFamily
            )
        )
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier,
    fontFamily: FontFamily
) {
    BasicText(
        text = "none... ur free!",
        style = TextStyle(
            color = Gray,
            fontSize = 16.sp,
            fontFamily = fontFamily
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    )
}

@Composable
private fun NotificationRow(
    item: NotificationItem,
    selected: Boolean,
    fontFamily: FontFamily,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Yellow else Color.Transparent)
            .padding(14.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
    ) {
        BasicText(
            text = item.title,
            style = TextStyle(
                color = if (selected) Black else White,
                fontSize = 16.sp,
                fontFamily = fontFamily
            ),
            maxLines = 1
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            text = item.text,
            style = TextStyle(
                color = if (selected) Black else Gray,
                fontSize = 14.sp,
                fontFamily = fontFamily
            ),
            maxLines = 2
        )
    }
}
