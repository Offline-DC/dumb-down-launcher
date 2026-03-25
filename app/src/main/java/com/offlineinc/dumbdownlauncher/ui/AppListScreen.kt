package com.offlineinc.dumbdownlauncher.ui

import android.content.Context.MODE_PRIVATE
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.model.AppItem
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

@Composable
fun AppListScreen(
    title: String? = null,
    titleEndLabel: String? = null,
    items: List<AppItem>,
    onActivate: (AppItem) -> Unit,
    onBack: (() -> Unit)? = null,
    showSoftKeys: Boolean = true,
    softKeyLeftLabel: String = "Notifications",
    softKeyRightLabel: String = "All Apps",
    onSoftKeyLeft: (() -> Unit)? = null,
    onSoftKeyRight: (() -> Unit)? = null,
    messagesMuted: Boolean = false,
    onToggleMessagesMuted: ((Boolean) -> Unit)? = null,
    wallpaperBitmap: Bitmap? = null,
) {
    val fontFamily = DumbTheme.BioRhyme
    val listState = rememberLazyListState()

    var selectedIndex by remember { mutableIntStateOf(0) }
    var didWrap by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(items.size) {
        selectedIndex = selectedIndex.coerceIn(0, (items.lastIndex).coerceAtLeast(0))
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        if (items.isNotEmpty()) listState.scrollToItem(0)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Wallpaper background (dark overlay) ───────────────────────
        if (wallpaperBitmap != null) {
            Image(
                bitmap = wallpaperBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.75f))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DumbTheme.Colors.Black)
            )
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                when (event.key) {
                    Key.DirectionDown -> {
                        if (items.isNotEmpty()) {
                            val next = (selectedIndex + 1) % items.size
                            didWrap = next == 0
                            selectedIndex = next
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        if (items.isNotEmpty()) {
                            val next = (selectedIndex - 1 + items.size) % items.size
                            didWrap = next == items.lastIndex
                            selectedIndex = next
                        }
                        true
                    }
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        if (items.isNotEmpty()) {
                            val item = items[selectedIndex]
                            if (item.packageName == DND_TOGGLE) {
                                onToggleMessagesMuted?.invoke(!messagesMuted)
                                true
                            } else {
                                onActivate(item)
                                true
                            }
                        } else true
                    }
                    Key.Back -> {
                        onBack?.invoke()
                        onBack != null
                    }

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
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = title,
                    style = TextStyle(
                        color = DumbTheme.Colors.White,
                        fontSize = 18.sp,
                        fontFamily = fontFamily
                    )
                )
                if (!titleEndLabel.isNullOrBlank()) {
                    BasicText(
                        text = titleEndLabel,
                        style = TextStyle(
                            color = DumbTheme.Colors.White.copy(alpha = 0.4f),
                            fontSize = 11.sp,
                            fontFamily = fontFamily
                        )
                    )
                }
            }
        }

        val listAlpha by animateFloatAsState(
            targetValue = if (items.isNotEmpty()) 1f else 0f,
            animationSpec = tween(300),
            label = "listAlpha"
        )
        Box(modifier = Modifier.weight(1f).alpha(listAlpha)) {
            LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    itemsIndexed(
                        items = items,
                        key = { _, item -> item.packageName }
                    ) { index, item ->
                        if (item.packageName == DND_TOGGLE) {
                            DndToggleRow(
                                item = item,
                                selected = (index == selectedIndex),
                                enabled = messagesMuted,
                                fontFamily = fontFamily
                            )
                        } else {
                            AppRow(
                                item = item,
                                selected = (index == selectedIndex),
                                fontFamily = fontFamily,
                            )
                        }
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

    } // end outer Box

    LaunchedEffect(selectedIndex) {
        if (items.isNotEmpty()) {
            if (didWrap) {
                // Instant scroll on wrap-around to prevent key-repeat from
                // stacking up during the long animated scroll through the list
                listState.scrollToItem(selectedIndex)
                didWrap = false
            } else {
                listState.animateScrollToItem(selectedIndex)
            }
        }
    }
}
