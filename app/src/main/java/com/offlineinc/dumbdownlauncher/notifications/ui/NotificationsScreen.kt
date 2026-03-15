package com.offlineinc.dumbdownlauncher.notifications.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/**
 * Focus zones (top → bottom):
 *   1. Clear All button (only when notifications exist)
 *   2. Notification list (only when notifications exist)
 *   3. DND toggle (always present at the bottom)
 *
 * When empty:
 *   1. Empty state text
 *   2. DND toggle
 */
@Composable
fun NotificationsScreen(
    items: List<NotificationItem>,
    onOpen: (NotificationItem) -> Unit,
    onDismiss: (NotificationItem) -> Unit,
    onClearAll: () -> Unit,
    scrollToKey: String? = null,
    onScrollConsumed: () -> Unit = {},
    messagesMuted: Boolean = false,
    onToggleMessagesMuted: ((Boolean) -> Unit)? = null,
) {
    val fontFamily = DumbTheme.BioRhyme
    val hasNotifications = items.isNotEmpty()
    val hasDndToggle = onToggleMessagesMuted != null

    // Selection state
    var selectedIndex by remember { mutableIntStateOf(0) }
    var selectionActive by remember { mutableStateOf(false) }

    // Focus requesters
    val clearAllFR = remember { FocusRequester() }
    val emptyFR = remember { FocusRequester() }
    val listFR = remember { FocusRequester() }
    val dndToggleFR = remember { FocusRequester() }

    // Focus tracking
    var clearAllFocused by remember { mutableStateOf(false) }
    var listFocused by remember { mutableStateOf(false) }
    var dndToggleFocused by remember { mutableStateOf(false) }

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

    // Scroll to a specific notification key
    LaunchedEffect(scrollToKey, items) {
        if (scrollToKey != null) {
            val idx = items.indexOfFirst { it.key == scrollToKey }
            if (idx >= 0) {
                selectedIndex = idx
                selectionActive = true
                listFR.requestFocus()
                listState.animateScrollToItem(idx)
                onScrollConsumed()
            }
        }
    }

    // Default focus behavior
    LaunchedEffect(hasNotifications) {
        if (hasNotifications) {
            selectionActive = false
            clearAllFR.requestFocus()
        } else {
            selectionActive = false
            if (hasDndToggle) {
                dndToggleFR.requestFocus()
            } else {
                emptyFR.requestFocus()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .padding(12.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                // ── Empty state (no notifications) ───────────────────────
                if (!hasNotifications && !dndToggleFocused) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.DirectionDown -> {
                            if (hasDndToggle) {
                                dndToggleFR.requestFocus()
                                true
                            } else true
                        }
                        Key.DirectionUp,
                        Key.Enter,
                        Key.NumPadEnter,
                        Key.DirectionCenter -> true
                        else -> false
                    }
                }

                // ── DND toggle focused ───────────────────────────────────
                if (dndToggleFocused) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.DirectionUp -> {
                            if (hasNotifications) {
                                // Go back to last notification
                                selectedIndex = items.lastIndex
                                selectionActive = true
                                listFR.requestFocus()
                            } else {
                                emptyFR.requestFocus()
                            }
                            true
                        }
                        Key.DirectionDown -> true // Already at bottom
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            onToggleMessagesMuted?.invoke(!messagesMuted)
                            true
                        }
                        else -> false
                    }
                }

                // ── Clear All focused ────────────────────────────────────
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

                // ── List focused ─────────────────────────────────────────
                if (listFocused) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.DirectionDown -> {
                            if (items.isNotEmpty()) {
                                if (selectedIndex < items.lastIndex) {
                                    selectedIndex += 1
                                } else if (hasDndToggle) {
                                    // Past last item → DND toggle
                                    selectionActive = false
                                    dndToggleFR.requestFocus()
                                }
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

        // ── DND toggle (always at bottom) ────────────────────────────
        if (hasDndToggle) {
            Spacer(Modifier.height(8.dp))
            DndToggleButton(
                enabled = messagesMuted,
                focused = dndToggleFocused,
                fontFamily = fontFamily,
                modifier = Modifier
                    .focusRequester(dndToggleFR)
                    .onFocusChanged { dndToggleFocused = it.isFocused }
                    .focusable(),
                onClick = { onToggleMessagesMuted?.invoke(!messagesMuted) }
            )
        }
    }
}

// ── Private composables ──────────────────────────────────────────────────────

@Composable
private fun DndToggleButton(
    enabled: Boolean,
    focused: Boolean,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (focused) Yellow else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Toggle pill
        val trackColor = when {
            enabled && focused -> Black
            enabled -> Yellow
            else -> Gray
        }
        val thumbColor = if (focused) Yellow else White

        Box(
            modifier = Modifier
                .size(width = 38.dp, height = 22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(trackColor),
            contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(2.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(thumbColor)
            )
        }

        Spacer(Modifier.width(14.dp))

        BasicText(
            text = "mute all texts",
            style = TextStyle(
                fontFamily = fontFamily,
                fontSize = 16.sp,
                color = if (focused) Black else White,
            ),
        )
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
