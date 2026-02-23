package com.offlineinc.dumbdownlauncher.ui

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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.model.AppItem
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

@Composable
fun AppListScreen(
    title: String? = null,
    items: List<AppItem>,
    onActivate: (AppItem) -> Unit,
    onBack: (() -> Unit)? = null,
    showSoftKeys: Boolean = true,
    softKeyLeftLabel: String = "Notifications",
    softKeyRightLabel: String = "All Apps",
    onSoftKeyLeft: (() -> Unit)? = null,
    onSoftKeyRight: (() -> Unit)? = null,
) {
    val fontFamily = DumbTheme.BioRhyme
    val listState = rememberLazyListState()

    var selectedIndex by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(items.size) {
        selectedIndex = selectedIndex.coerceIn(0, (items.lastIndex).coerceAtLeast(0))
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        if (items.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                when (event.key) {
                    Key.DirectionDown -> {
                        if (items.isNotEmpty()) {
                            selectedIndex = (selectedIndex + 1).coerceAtMost(items.lastIndex)
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        if (items.isNotEmpty()) {
                            selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                        }
                        true
                    }
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        if (items.isNotEmpty()) onActivate(items[selectedIndex])
                        true
                    }
                    Key.Back -> {
                        onBack?.invoke()
                        onBack != null
                    }

                    // ✅ Only handle MENU/B if softkeys are enabled AND callbacks are provided
                    Key.Menu -> {
                        if (showSoftKeys && onSoftKeyLeft != null) {
                            onSoftKeyLeft.invoke()
                            true
                        } else false
                    }
                    Key.B -> {
                        if (showSoftKeys && onSoftKeyRight != null) {
                            onSoftKeyRight.invoke()
                            true
                        } else false
                    }

                    else -> false
                }
            }
    ) {
        if (!title.isNullOrBlank()) {
            BasicText(
                text = title,
                style = TextStyle(
                    color = DumbTheme.Colors.White,
                    fontSize = 18.sp,
                    fontFamily = fontFamily
                ),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(
                    items = items,
                    key = { _, item -> item.packageName }
                ) { index, item ->
                    AppRow(
                        item = item,
                        selected = (index == selectedIndex),
                        fontFamily = fontFamily
                    )
                }
            }
        }

        // ✅ Only show footer when enabled
        if (showSoftKeys) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BasicText(
                    text = softKeyLeftLabel,
                    style = TextStyle(
                        color = DumbTheme.Colors.Yellow,
                        fontSize = 14.sp,
                        fontFamily = fontFamily
                    )
                )

                BasicText(
                    text = softKeyRightLabel,
                    style = TextStyle(
                        color = DumbTheme.Colors.Yellow,
                        fontSize = 14.sp,
                        fontFamily = fontFamily
                    )
                )
            }
        }
    }

    LaunchedEffect(selectedIndex) {
        if (items.isNotEmpty()) {
            listState.animateScrollToItem(selectedIndex)
        }
    }
}
