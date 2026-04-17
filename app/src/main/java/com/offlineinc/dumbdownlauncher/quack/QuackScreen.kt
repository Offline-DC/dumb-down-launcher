package com.offlineinc.dumbdownlauncher.quack

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.offlineinc.dumbdownlauncher.ui.SoftKeyBar
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme
import kotlinx.coroutines.delay

@Composable
fun QuackScreen(
    viewModel: QuackViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
    ) {
        when (state.mode) {
            QuackMode.LOADING -> {
                if (state.isInitialLoad) LoadingScreen()
                else MiniLoadingScreen()
            }
            QuackMode.FEED    -> FeedScreen(state, viewModel, onBack)
            QuackMode.RULES   -> RulesScreen(state, viewModel)
            QuackMode.COMPOSE -> ComposeScreen(state, viewModel)
            QuackMode.ERROR   -> ErrorScreen(state, viewModel, onBack)
        }
    }
}

// ── Loading ──────────────────────────────────────────────────────────

/**
 * Pixel-art duck with a 3-frame walking animation.
 * Each frame is a 12×12 grid drawn with chunky square pixels.
 */
@Composable
private fun LoadingScreen() {
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            frame = (frame + 1) % 3
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            PixelDuckSpinner(frame)
            Spacer(Modifier.height(12.dp))
            // Pixel-style dot loader: ■ · · → · ■ · → · · ■
            Row {
                for (i in 0 until 3) {
                    BasicText(
                        text = if (i == frame) "■" else "·",
                        style = DumbTheme.Text.Body.copy(
                            color = if (i == frame) DumbTheme.Colors.Yellow
                                    else DumbTheme.Colors.Gray
                        ),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Simplified loading screen — just the dots, no duck. Used for refreshes after submit.
 */
@Composable
private fun MiniLoadingScreen() {
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            frame = (frame + 1) % 3
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            Row {
                for (i in 0 until 3) {
                    BasicText(
                        text = if (i == frame) "■" else "·",
                        style = DumbTheme.Text.Body.copy(
                            color = if (i == frame) DumbTheme.Colors.Yellow
                                    else DumbTheme.Colors.Gray
                        ),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Draws a pixel-art duck on a Canvas. Three frames animate the feet
 * to create a waddling effect. Grid is 12 wide × 12 tall.
 *
 * Colors: Y=yellow(body), O=orange(beak/feet), K=black(eye), W=wing(darker yellow)
 */
@Composable
private fun PixelDuckSpinner(frame: Int) {
    val Y = DumbTheme.Colors.Yellow             // body
    val O = Color(0xFFFF8C00)                   // beak + feet
    val K = DumbTheme.Colors.Black              // eye
    val W = Color(0xFFE6BE00)                   // wing
    val T = Color.Transparent

    // 12×12 grid — row-major. Feet pixels change per frame.
    val body: List<List<Color>> = listOf(
        //  0  1  2  3  4  5  6  7  8  9  10 11
        listOf(T, T, T, T, T, Y, Y, T, T, T, T, T),  // 0  head top
        listOf(T, T, T, T, Y, Y, Y, Y, T, T, T, T),  // 1  head
        listOf(T, T, T, T, Y, Y, K, Y, O, O, T, T),  // 2  eye + beak
        listOf(T, T, T, T, Y, Y, Y, Y, O, O, O, T),  // 3  head + beak
        listOf(T, T, T, T, T, Y, Y, Y, T, T, T, T),  // 4  neck
        listOf(T, T, T, Y, Y, Y, Y, Y, Y, T, T, T),  // 5  body top
        listOf(T, T, Y, Y, W, W, Y, Y, Y, Y, T, T),  // 6  body + wing
        listOf(T, T, Y, Y, W, W, W, Y, Y, Y, T, T),  // 7  body + wing
        listOf(T, T, Y, Y, Y, Y, Y, Y, Y, Y, T, T),  // 8  body bottom
        listOf(T, T, T, Y, Y, Y, Y, Y, Y, T, T, T),  // 9  body base
    )

    // Three foot frames for waddle animation
    val feet: List<List<List<Color>>> = listOf(
        // frame 0: feet centered
        listOf(
            listOf(T, T, T, T, O, O, T, O, O, T, T, T),
            listOf(T, T, T, O, O, T, T, T, O, O, T, T),
        ),
        // frame 1: left foot forward
        listOf(
            listOf(T, T, T, O, O, T, T, T, O, T, T, T),
            listOf(T, T, O, O, O, T, T, T, O, O, T, T),
        ),
        // frame 2: right foot forward
        listOf(
            listOf(T, T, T, T, O, T, T, O, O, T, T, T),
            listOf(T, T, T, O, O, T, T, O, O, O, T, T),
        ),
    )

    val grid = body + feet[frame]
    val rows = grid.size    // 12
    val cols = grid[0].size // 12

    Canvas(modifier = Modifier.size(72.dp)) {
        val px = size.width / cols
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val color = grid[r][c]
                if (color != Color.Transparent) {
                    drawRect(
                        color = color,
                        topLeft = Offset(c * px, r * px),
                        size = Size(px, px),
                    )
                }
            }
        }
    }
}

// ── Feed ─────────────────────────────────────────────────────────────

@Composable
private fun FeedScreen(
    state: QuackUiState,
    viewModel: QuackViewModel,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    // Auto-scroll to selected item
    LaunchedEffect(state.selectedIndex) {
        if (state.posts.isNotEmpty()) {
            listState.animateScrollToItem(state.selectedIndex)
        }
    }

    // Grab focus so key events work
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Tick every 30 s so post ages stay fresh (e.g. "3 min" → "4 min")
    var ageTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            ageTick++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                // Consume both KeyDown and KeyUp for handled keys so that
                // no stale KeyUp / repeat leaks into the next screen after
                // a mode transition (e.g. feed → rules → compose).
                val isDown = event.type == KeyEventType.KeyDown
                when (event.key) {
                    Key.DirectionUp -> {
                        if (isDown) viewModel.moveSelection(-1); true
                    }
                    Key.DirectionDown -> {
                        if (isDown) viewModel.moveSelection(1); true
                    }
                    Key.SoftLeft -> {
                        if (isDown) viewModel.enterCompose(); true
                    }
                    Key.DirectionCenter -> {
                        if (isDown) viewModel.refreshFromUser(); true
                    }
                    Key.SoftRight -> {
                        if (isDown) viewModel.showRules(); true
                    }
                    Key.Back -> {
                        if (isDown) onBack(); true
                    }
                    else -> false
                }
            }
            .focusable()
    ) {
        // Post list
        if (state.posts.isEmpty()) {
            Spacer(Modifier.height(DumbTheme.Spacing.ScreenPaddingV))
            BasicText(
                text = "quack",
                style = DumbTheme.Text.PageTitle.copy(color = DumbTheme.Colors.Yellow),
                modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = "no quacks nearby.\nbe the first to quack.",
                style = DumbTheme.Text.Body,
                modifier = Modifier
                    .padding(horizontal = DumbTheme.Spacing.ScreenPaddingH)
                    .weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(state.posts) { index, post ->
                    PostRow(post, selected = index == state.selectedIndex, ageTick = ageTick)
                }
            }
        }

        // Soft keys
        SoftKeyBar(
            leftLabel = "quack",
            centerLabel = "refresh",
            rightLabel = "rulez",
        )
    }
}

@Composable
@Suppress("UNUSED_PARAMETER") // ageTick forces recomposition so formatAge stays fresh
private fun PostRow(post: QuackPost, selected: Boolean, ageTick: Int = 0) {
    val bgColor = if (selected) DumbTheme.Colors.Yellow else Color.Transparent
    val textColor = if (selected) DumbTheme.Colors.Black else DumbTheme.Colors.White
    val metaColor = if (selected) DumbTheme.Colors.Black else DumbTheme.Colors.Gray

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = DumbTheme.Spacing.ScreenPaddingH, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        BasicText(
            text = post.body,
            style = DumbTheme.Text.BodySmall.copy(color = textColor),
            modifier = Modifier.weight(1f),
        )
        val age = formatAge(post.createdAt)
        if (age.isNotEmpty()) {
            BasicText(
                text = age,
                style = DumbTheme.Text.Hint.copy(color = metaColor),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
    // Divider
    if (!selected) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF333333))
        )
    }
}

// ── Rules ───────────────────────────────────────────────────────────

@Composable
private fun RulesScreen(state: QuackUiState, viewModel: QuackViewModel) {
    val focusRequester = remember { FocusRequester() }
    val accepted = state.hasAcceptedRules

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                val isDown = event.type == KeyEventType.KeyDown
                when (event.key) {
                    Key.SoftRight -> {
                        if (isDown && event.nativeKeyEvent.repeatCount == 0) {
                            if (!accepted) {
                                viewModel.acceptRules()
                            } else {
                                viewModel.toggleNotificationsMuted()
                            }
                        }
                        true
                    }
                    Key.Back, Key.SoftLeft -> {
                        if (isDown) viewModel.exitRules(); true
                    }
                    else -> false
                }
            }
            .focusable()
    ) {
        BasicText(
            text = "rulez",
            style = DumbTheme.Text.PageTitle.copy(color = DumbTheme.Colors.Yellow),
            modifier = Modifier.padding(
                horizontal = DumbTheme.Spacing.ScreenPaddingH,
                vertical = DumbTheme.Spacing.ScreenPaddingV,
            ),
        )

        Spacer(Modifier.weight(1f))

        val rules = listOf(
            "* can quack 3 times a day",
            "* view every quack within 25 miles of u",
            "* quacks disappear after 24 hrs",
            "* quack",
        )
        for (rule in rules) {
            BasicText(
                text = rule,
                style = DumbTheme.Text.Body.copy(color = DumbTheme.Colors.White),
                modifier = Modifier
                    .padding(horizontal = DumbTheme.Spacing.ScreenPaddingH)
                    .padding(bottom = 12.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        SoftKeyBar(
            leftLabel = "back",
            centerLabel = null,
            rightLabel = if (!accepted) "accept"
                         else if (state.notificationsMuted) "unmute"
                         else "mute",
        )
    }
}

// ── Compose ──────────────────────────────────────────────────────────

@Composable
private fun ComposeScreen(
    state: QuackUiState,
    viewModel: QuackViewModel,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
    ) {
        // Header
        Spacer(Modifier.height(DumbTheme.Spacing.ScreenPaddingV))

        // Char count
        BasicText(
            text = "${state.composeText.length}/140",
            style = DumbTheme.Text.Hint,
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )

        Spacer(Modifier.height(8.dp))

        // Submit error — shown inline above the text field
        if (state.submitError != null) {
            BasicText(
                text = state.submitError,
                style = DumbTheme.Text.BodySmall.copy(color = DumbTheme.Colors.Red),
                modifier = Modifier
                    .padding(horizontal = DumbTheme.Spacing.ScreenPaddingH)
                    .padding(bottom = 8.dp),
            )
        }

        // Text field
        BasicTextField(
            value = state.composeText,
            onValueChange = {
                viewModel.updateComposeText(it)
                // Clear error when user starts typing again
                if (state.submitError != null) viewModel.clearSubmitError()
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = DumbTheme.Spacing.ScreenPaddingH)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    val isDown = event.type == KeyEventType.KeyDown
                    when (event.key) {
                        Key.SoftLeft -> {
                            if (isDown) viewModel.submitPost(); true
                        }
                        Key.Back, Key.SoftRight -> {
                            if (isDown) viewModel.exitCompose(); true
                        }
                        else -> false
                    }
                },
            textStyle = DumbTheme.Text.Body.copy(color = DumbTheme.Colors.White),
            cursorBrush = SolidColor(DumbTheme.Colors.Yellow),
            decorationBox = { innerTextField ->
                Box {
                    if (state.composeText.isEmpty()) {
                        BasicText(
                            text = "what's happening nearby?",
                            style = DumbTheme.Text.Body.copy(color = DumbTheme.Colors.Gray),
                        )
                    }
                    innerTextField()
                }
            },
        )

        // Posts remaining indicator
        val remaining = (3 - state.postsToday).coerceAtLeast(0)
        BasicText(
            text = "$remaining/3 quacks left today",
            style = DumbTheme.Text.Hint.copy(
                color = if (remaining == 0) DumbTheme.Colors.Red else DumbTheme.Colors.Gray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            ),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )

        // Soft keys
        SoftKeyBar(
            leftLabel = if (state.isSubmitting) "quacking..." else "quack",
            centerLabel = null,
            rightLabel = "back",
        )
    }
}

// ── Error ────────────────────────────────────────────────────────────

@Composable
private fun ErrorScreen(
    state: QuackUiState,
    viewModel: QuackViewModel,
    onBack: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                val isDown = event.type == KeyEventType.KeyDown
                when (event.key) {
                    Key.DirectionCenter -> { if (isDown) viewModel.retry(); true }
                    Key.Back, Key.SoftLeft -> { if (isDown) onBack(); true }
                    else -> false
                }
            }
            .focusable()
    ) {
        Spacer(Modifier.height(DumbTheme.Spacing.ScreenPaddingV))
        BasicText(
            text = state.errorMessage,
            style = DumbTheme.Text.Body.copy(color = DumbTheme.Colors.Red),
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )
        Spacer(Modifier.height(16.dp))
        BasicText(
            text = "press ok to retry\npress back to exit",
            style = DumbTheme.Text.Subtitle,
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )

        Spacer(Modifier.weight(1f))

        SoftKeyBar(
            leftLabel = "back",
            centerLabel = "retry",
            rightLabel = null,
        )
    }
}

// ── Utilities ────────────────────────────────────────────────────────

private fun formatAge(isoDate: String?): String {
    if (isoDate == null || isoDate.length < 19) return ""
    return try {
        val s = isoDate.replace("T", " ").replace("Z", "").replace(Regex("\\.\\d+.*"), "")
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val diff = (System.currentTimeMillis() - sdf.parse(s)!!.time) / 1000
        when {
            diff < 60    -> "<1 min"
            diff < 3600  -> "${diff / 60} min"
            else         -> "${diff / 3600} hr"
        }
    } catch (_: Exception) { "" }
}
