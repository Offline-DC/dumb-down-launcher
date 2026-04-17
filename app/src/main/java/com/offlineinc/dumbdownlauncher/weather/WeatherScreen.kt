package com.offlineinc.dumbdownlauncher.weather

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

// ── Colors matching the standalone weather app ──────────────────────────
private val WeatherPink = Color(0xFFFE7DF3)
private val WeatherDim = Color(0xFF888888)
private val WeatherDivider = Color(0xFF333344)
private val ForecastBg = Color(0xFF252540)
private val ForecastSelectedBorder = Color(0xFFF9F594)

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
            WeatherMode.LOADING -> WeatherLoadingScreen(onBack)
            WeatherMode.DISPLAY -> WeatherDisplayScreen(state, viewModel, onBack)
            WeatherMode.ERROR   -> WeatherErrorScreen(state, onBack)
        }
    }
}

// ── Loading ─────────────────────────────────────────────────────────────

@Composable
private fun WeatherLoadingScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Back -> { onBack(); true }
                    else -> false
                }
            }
            .focusable(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BasicText(
            text = "loading weather…",
            style = DumbTheme.Text.Body.copy(color = DumbTheme.Colors.Gray),
        )
    }
}

// ── Error ───────────────────────────────────────────────────────────────

@Composable
private fun WeatherErrorScreen(state: WeatherUiState, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Back -> { onBack(); true }
                    else -> false
                }
            }
            .focusable(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BasicText(
            text = state.errorMessage,
            style = DumbTheme.Text.Body.copy(
                color = DumbTheme.Colors.Yellow,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )
    }
}

// ── Main weather display ────────────────────────────────────────────────

@Composable
private fun WeatherDisplayScreen(
    state: WeatherUiState,
    viewModel: WeatherViewModel,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()

    // Scroll to selected forecast item
    LaunchedEffect(state.selectedForecastIndex) {
        if (state.forecasts.isNotEmpty()) {
            listState.animateScrollToItem(state.selectedForecastIndex)
        }
    }

    // Determine what to show in the top display based on forecast selection
    val displayForecast = state.forecasts.getOrNull(state.selectedForecastIndex)
    val displayTemp = displayForecast?.temp ?: state.temp
    val displayCondition = if (displayForecast != null) {
        if (displayForecast.isNow) "Now: ${displayForecast.condition}"
        else "${displayForecast.hour}: ${displayForecast.condition}"
    } else {
        state.condition
    }
    val displayIcon = displayForecast?.iconRes ?: state.iconRes

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
            .padding(12.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        viewModel.moveForecastSelection(-1); true
                    }
                    Key.DirectionRight -> {
                        viewModel.moveForecastSelection(1); true
                    }
                    Key.Back -> {
                        onBack(); true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        // Updated time
        BasicText(
            text = "Updated: ${state.updatedAt}",
            style = TextStyle(
                fontFamily = DumbTheme.BioRhyme,
                fontSize = 11.sp,
                color = WeatherDim,
            ),
            modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(Modifier.height(4.dp))

        // Current weather section - centered
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Weather icon
            Image(
                painter = painterResource(id = displayIcon),
                contentDescription = "Weather icon",
                modifier = Modifier.size(64.dp),
            )

            // Temperature row: current temp + high/low
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                // Current temperature
                BasicText(
                    text = "${displayTemp}°F",
                    style = TextStyle(
                        fontFamily = DumbTheme.BioRhyme,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        color = DumbTheme.Colors.White,
                    ),
                )

                Spacer(Modifier.width(12.dp))

                // High / Low stacked
                Column {
                    BasicText(
                        text = "H: ${state.highTemp}°",
                        style = TextStyle(
                            fontFamily = DumbTheme.BioRhyme,
                            fontSize = 14.sp,
                            color = DumbTheme.Colors.Gray,
                        ),
                    )
                    BasicText(
                        text = "L: ${state.lowTemp}°",
                        style = TextStyle(
                            fontFamily = DumbTheme.BioRhyme,
                            fontSize = 14.sp,
                            color = DumbTheme.Colors.Gray,
                        ),
                    )
                }
            }

            // Condition text
            BasicText(
                text = displayCondition,
                style = TextStyle(
                    fontFamily = DumbTheme.BioRhyme,
                    fontSize = 18.sp,
                    color = WeatherPink,
                ),
            )
        }

        // Divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(WeatherDivider),
        )

        // Forecast label
        BasicText(
            text = "Forecast",
            style = TextStyle(
                fontFamily = DumbTheme.BioRhyme,
                fontSize = 14.sp,
                color = DumbTheme.Colors.Gray,
            ),
            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
        )

        // Hourly forecast row
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(state.forecasts) { index, forecast ->
                ForecastItem(
                    forecast = forecast,
                    isSelected = index == state.selectedForecastIndex,
                )
            }
        }
    }
}

// ── Forecast item ───────────────────────────────────────────────────────

@Composable
private fun ForecastItem(
    forecast: HourlyForecast,
    isSelected: Boolean,
) {
    val shape = RoundedCornerShape(4.dp)
    Column(
        modifier = Modifier
            .clip(shape)
            .background(ForecastBg)
            .then(
                if (isSelected) Modifier.border(1.dp, ForecastSelectedBorder, shape)
                else Modifier
            )
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            text = forecast.hour,
            style = TextStyle(
                fontFamily = DumbTheme.BioRhyme,
                fontSize = 12.sp,
                color = DumbTheme.Colors.Gray,
            ),
        )
        Spacer(Modifier.height(2.dp))
        Image(
            painter = painterResource(id = forecast.iconRes),
            contentDescription = forecast.condition,
            modifier = Modifier.size(32.dp),
        )
    }
}
