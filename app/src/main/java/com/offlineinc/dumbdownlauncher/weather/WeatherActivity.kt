package com.offlineinc.dumbdownlauncher.weather

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.lifecycle.ViewModelProvider
import com.offlineinc.dumbdownlauncher.ui.SoftKeyBar
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

/**
 * Hosts the Weather UI within the launcher app.
 * Follows the same pattern as QuackActivity and ContactSyncActivity.
 *
 * On first launch, shows a modal asking for location consent.
 * If declined, finishes back to all apps. If accepted, remembers
 * the choice and loads weather using Quack's persisted location.
 */
class WeatherActivity : AppCompatActivity() {

    companion object {
        private const val LOC_PERM_REQ = 43
    }

    private lateinit var viewModel: WeatherViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF000000.toInt()

        viewModel = ViewModelProvider(this)[WeatherViewModel::class.java]

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DumbTheme.Colors.Black)
            ) {
                var showConsentModal by remember {
                    mutableStateOf(!WeatherLocationConsentStore.hasConsented(this@WeatherActivity))
                }

                if (showConsentModal) {
                    LocationConsentModal(
                        onAccept = {
                            WeatherLocationConsentStore.setConsented(this@WeatherActivity, true)
                            showConsentModal = false
                            requestLocationAndLoad()
                        },
                        onDecline = {
                            finish()
                        },
                    )
                } else {
                    WeatherScreen(
                        viewModel = viewModel,
                        onBack = { finish() },
                    )

                    // Trigger load when consent is already given
                    LaunchedEffect(Unit) {
                        requestLocationAndLoad()
                    }
                }
            }
        }
    }

    private fun requestLocationAndLoad() {
        // Check if we actually have system location permission
        val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                LOC_PERM_REQ,
            )
        } else {
            viewModel.loadWeather()
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(req, perms, grants)
        if (req == LOC_PERM_REQ && grants.isNotEmpty() && grants.any { it == PackageManager.PERMISSION_GRANTED }) {
            viewModel.loadWeather()
        } else {
            // Permission denied at system level — go back
            finish()
        }
    }
}

// ── Location consent modal ──────────────────────────────────────────────

@Composable
private fun LocationConsentModal(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    // Track which button is focused: 0 = yes, 1 = no
    var selectedButton by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DumbTheme.Spacing.ScreenPaddingH)
                .clip(RoundedCornerShape(DumbTheme.Corner.Medium))
                .background(Color(0xFF1A1A2E))
                .padding(20.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {
                            selectedButton = 0; true
                        }
                        Key.DirectionRight -> {
                            selectedButton = 1; true
                        }
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            if (selectedButton == 0) onAccept() else onDecline()
                            true
                        }
                        Key.Back -> {
                            onDecline(); true
                        }
                        Key.SoftLeft -> {
                            onAccept(); true
                        }
                        Key.SoftRight -> {
                            onDecline(); true
                        }
                        else -> false
                    }
                }
                .focusRequester(focusRequester)
                .focusable(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                text = "weather",
                style = DumbTheme.Text.PageTitle.copy(color = DumbTheme.Colors.Yellow),
            )

            Spacer(Modifier.height(12.dp))

            BasicText(
                text = "weather needs your location to show local conditions.\n\nallow weather to access your location?",
                style = DumbTheme.Text.BodySmall.copy(
                    textAlign = TextAlign.Center,
                ),
            )

            Spacer(Modifier.height(20.dp))

            // Yes / No buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                ConsentButton(
                    text = "yes",
                    isSelected = selectedButton == 0,
                )
                Spacer(Modifier.width(16.dp))
                ConsentButton(
                    text = "no",
                    isSelected = selectedButton == 1,
                )
            }
        }

        // Soft key bar at bottom
        SoftKeyBar(
            leftLabel = "yes",
            rightLabel = "no",
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ConsentButton(
    text: String,
    isSelected: Boolean,
) {
    val bg = if (isSelected) DumbTheme.Colors.Yellow else Color(0xFF333344)
    val textColor = if (isSelected) DumbTheme.Colors.Black else DumbTheme.Colors.White

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = DumbTheme.Text.Body.copy(color = textColor),
        )
    }
}
