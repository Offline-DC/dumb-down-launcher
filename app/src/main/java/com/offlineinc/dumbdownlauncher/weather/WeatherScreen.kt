package com.offlineinc.dumbdownlauncher.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.ui.SoftKeyBar
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

@Composable
fun WeatherScreen(
    viewModel: WeatherViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
    ) {
        when (state.mode) {
            WeatherMode.LOADING -> LoadingScreen(onBack)
            WeatherMode.DISPLAY -> DisplayScreen(state, viewModel, onBack)
            WeatherMode.ERROR   -> ErrorScreen(state, viewModel, onBack)
        }
    }
}

// ── Loading ─────────────────────────────────────────────────────────────

@Composable
private fun LoadingScreen(onBack: () -> Unit) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Back -> { onBack(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        Spacer(Modifier.height(DumbTheme.Spacing.ScreenPaddingV))
        BasicText(
            text = "weather",
            // Weather uses the Helvetica body font — bigger than the
            // standard PageTitle to own more of the screen.
            style = DumbTheme.Text.PageTitle.copy(
                fontFamily = DumbTheme.Body,
                fontSize = 24.sp,
                // Softened yellow — matches the muted icon tint so the title
                // feels like a quiet label, not a headline demanding focus.
                color = DumbTheme.Colors.Yellow.copy(alpha = 0.55f),
            ),
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )
        Spacer(Modifier.height(8.dp))
        BasicText(
            text = "loading…",
            style = DumbTheme.Text.Body.copy(fontSize = 18.sp),
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )
    }
}

// ── Error ───────────────────────────────────────────────────────────────

@Composable
private fun ErrorScreen(
    state: WeatherUiState,
    viewModel: WeatherViewModel,
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
                    Key.DirectionCenter -> { if (isDown) viewModel.loadWeather(); true }
                    Key.Back, Key.SoftLeft -> { if (isDown) onBack(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        Spacer(Modifier.height(DumbTheme.Spacing.ScreenPaddingV))
        BasicText(
            text = "weather",
            // Weather uses the Helvetica body font — see LoadingScreen.
            style = DumbTheme.Text.PageTitle.copy(
                fontFamily = DumbTheme.Body,
                fontSize = 24.sp,
                // Softened yellow — matches the muted icon tint so the title
                // feels like a quiet label, not a headline demanding focus.
                color = DumbTheme.Colors.Yellow.copy(alpha = 0.55f),
            ),
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )
        Spacer(Modifier.height(8.dp))
        BasicText(
            text = state.errorMessage,
            style = DumbTheme.Text.Body.copy(
                fontSize = 18.sp,
                color = DumbTheme.Colors.Red,
            ),
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )
        Spacer(Modifier.height(16.dp))
        BasicText(
            text = "press ok to retry\npress back to exit",
            style = DumbTheme.Text.Subtitle.copy(fontSize = 15.sp),
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )

        Spacer(Modifier.weight(1f))

        SoftKeyBar(
            leftLabel = "back",
            centerLabel = "retry",
        )
    }
}

// ── Main weather display ────────────────────────────────────────────────

@Composable
private fun DisplayScreen(
    state: WeatherUiState,
    viewModel: WeatherViewModel,
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
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Back -> { onBack(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        Spacer(Modifier.height(DumbTheme.Spacing.ScreenPaddingV))

        // Title
        BasicText(
            text = "weather",
            // Weather uses the Helvetica body font — see LoadingScreen.
            style = DumbTheme.Text.PageTitle.copy(
                fontFamily = DumbTheme.Body,
                fontSize = 24.sp,
                // Softened yellow — matches the muted icon tint so the title
                // feels like a quiet label, not a headline demanding focus.
                color = DumbTheme.Colors.Yellow.copy(alpha = 0.55f),
            ),
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )

        // Weather information block — lives in a weighted, vertically-centered
        // inner Column so it sits in the middle of the space between the
        // "weather" title above and the tomorrow row below. horizontalAlignment
        // centers each child row/text without each one needing its own
        // fillMaxWidth + textAlign.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Row 1 — hero temperature, centered.
            BasicText(
                text = "${state.temp}°F",
                // Hero temperature — Helvetica, 52sp.
                style = DumbTheme.Text.PageTitle.copy(
                    fontFamily = DumbTheme.Body,
                    fontSize = 52.sp,
                    color = DumbTheme.Colors.White,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(4.dp))

            // Row 2 — high / low.
            BasicText(
                text = "H: ${state.highTemp}°  /  L: ${state.lowTemp}°",
                style = DumbTheme.Text.Body.copy(
                    fontSize = 18.sp,
                    color = DumbTheme.Colors.Gray,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
            )

            Spacer(Modifier.height(2.dp))

            // Row 3 — condition description (e.g. "mostly clear").
            BasicText(
                text = state.condition,
                style = DumbTheme.Text.Body.copy(
                    fontSize = 18.sp,
                    color = DumbTheme.Colors.Gray,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DumbTheme.Spacing.ScreenPaddingH, vertical = 2.dp),
            )

            // Divider
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DumbTheme.Spacing.ScreenPaddingH, vertical = 6.dp)
                    .height(1.dp)
                    .background(Color(0xFF333333))
            )

            // Today's summary.
            BasicText(
                text = state.todaySummary,
                style = DumbTheme.Text.BodySmall.copy(
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
            )
        }

        // Divider before tomorrow
        Spacer(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DumbTheme.Spacing.ScreenPaddingH)
                .height(1.dp)
                .background(Color(0xFF333333))
        )

        // Tomorrow — subtle, pushed to bottom.
        // Kept on a single row: tighter horizontal padding, smaller gaps, and
        // 12sp text so the longest conditions (e.g. "thunderstorm") still fit.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tomorrow row — one size down (12sp) to fit on a single row at 240px.
            // Text-only now: "tomorrow · condition · H°/L°" reads cleanly
            // without the icon competing for the limited row width.
            val tomorrowStyle = DumbTheme.Text.Label.copy(fontSize = 12.sp)
            BasicText(
                text = "tomorrow",
                style = tomorrowStyle,
            )
            // Weighted spacer pushes the condition + temperature to the
            // right edge of the row — "tomorrow" stays anchored left,
            // while "Cloudy  H°/L°" clusters flush-right as a group.
            Spacer(Modifier.weight(1f))
            BasicText(
                text = state.tomorrowCondition,
                style = tomorrowStyle,
            )
            Spacer(Modifier.width(6.dp))
            BasicText(
                text = "${state.tomorrowHigh}° / ${state.tomorrowLow}°",
                style = tomorrowStyle,
            )
        }

    }
}
