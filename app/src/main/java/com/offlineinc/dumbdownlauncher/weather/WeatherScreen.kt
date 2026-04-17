package com.offlineinc.dumbdownlauncher.weather

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
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
            style = DumbTheme.Text.PageTitle.copy(color = DumbTheme.Colors.Yellow),
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )
        Spacer(Modifier.height(8.dp))
        BasicText(
            text = "loading…",
            style = DumbTheme.Text.Body,
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
            style = DumbTheme.Text.PageTitle.copy(color = DumbTheme.Colors.Yellow),
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )
        Spacer(Modifier.height(8.dp))
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
            style = DumbTheme.Text.PageTitle.copy(color = DumbTheme.Colors.Yellow),
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )

        Spacer(Modifier.height(8.dp))

        // Current conditions: icon + temp + high/low
        Row(
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = rememberVectorPainter(state.icon),
                contentDescription = state.condition,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.width(12.dp))
            BasicText(
                text = "${state.temp}°F",
                style = DumbTheme.Text.PageTitle.copy(
                    fontSize = 42.sp,
                    color = DumbTheme.Colors.White,
                ),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                BasicText(
                    text = "H: ${state.highTemp}°",
                    style = DumbTheme.Text.Body.copy(color = DumbTheme.Colors.Gray),
                )
                BasicText(
                    text = "L: ${state.lowTemp}°",
                    style = DumbTheme.Text.Body.copy(color = DumbTheme.Colors.Gray),
                )
            }
        }

        // Condition label — centered
        BasicText(
            text = state.condition,
            style = DumbTheme.Text.Body.copy(
                color = DumbTheme.Colors.Gray,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DumbTheme.Spacing.ScreenPaddingH, vertical = 4.dp),
        )

        // Divider
        Spacer(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DumbTheme.Spacing.ScreenPaddingH, vertical = 6.dp)
                .height(1.dp)
                .background(Color(0xFF333333))
        )

        // Today's summary
        BasicText(
            text = state.todaySummary,
            style = DumbTheme.Text.BodySmall,
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )

        Spacer(Modifier.weight(1f))

        // Divider before tomorrow
        Spacer(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DumbTheme.Spacing.ScreenPaddingH)
                .height(1.dp)
                .background(Color(0xFF333333))
        )

        // Tomorrow — subtle, pushed to bottom
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DumbTheme.Spacing.ScreenPaddingH, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = "tomorrow",
                style = DumbTheme.Text.Label,
            )
            Spacer(Modifier.width(8.dp))
            Image(
                painter = rememberVectorPainter(state.tomorrowIcon),
                contentDescription = state.tomorrowCondition,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            BasicText(
                text = state.tomorrowCondition,
                style = DumbTheme.Text.Label,
            )
            Spacer(Modifier.width(6.dp))
            BasicText(
                text = "${state.tomorrowHigh}° / ${state.tomorrowLow}°",
                style = DumbTheme.Text.Label,
            )
        }

    }
}
