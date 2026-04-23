package com.offlineinc.dumbdownlauncher.weather

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.offlineinc.dumbdownlauncher.quack.LocationConsent
import com.offlineinc.dumbdownlauncher.ui.SoftKeyBar
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

/**
 * Hosts the Weather UI within the launcher app.
 * Follows the same pattern as QuackActivity and ContactSyncActivity.
 *
 * On first launch, shows a full-page consent screen (not a modal) asking
 * if the user wants weather to access their location. Soft-left = yes,
 * soft-right = no. If no, finishes back to all apps. If yes, remembers
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
            var needsConsent by remember {
                mutableStateOf(!WeatherLocationConsentStore.hasConsented(this@WeatherActivity))
            }

            if (needsConsent) {
                LocationConsentScreen(
                    onAccept = {
                        WeatherLocationConsentStore.setConsented(this@WeatherActivity, true)
                        // Kick off the launcher-wide location prewarm + periodic
                        // refresh now that the user has opted in. Previously this
                        // ran unconditionally at every boot; now it waits until
                        // consent is granted in either quack or weather.
                        LocationConsent.onConsentGranted(this@WeatherActivity)
                        needsConsent = false
                        requestLocationAndLoad()
                    },
                    onDecline = { finish() },
                )
            } else {
                WeatherScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overridePendingTransition(0, 0)
        if (!WeatherLocationConsentStore.hasConsented(this)) return
        // First open or returning after consent: kick off initial load.
        // Returning later: silent refresh (only fires if already on display).
        if (viewModel.state.value.mode == WeatherMode.LOADING) {
            requestLocationAndLoad()
        } else {
            viewModel.refreshWeather()
        }
    }

    private fun requestLocationAndLoad() {
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
            finish()
        }
    }
}

// ── Location consent — full page, matches quack's rules screen style ────

@Composable
private fun LocationConsentScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
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
                    Key.SoftLeft -> { onAccept(); true }
                    Key.SoftRight, Key.Back -> { onDecline(); true }
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> { onAccept(); true }
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
            text = "weather needs your location to show local conditions.",
            style = DumbTheme.Text.Body,
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )

        Spacer(Modifier.height(16.dp))

        BasicText(
            text = "allow weather to access your location?",
            style = DumbTheme.Text.Body.copy(color = DumbTheme.Colors.Yellow),
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )

        Spacer(Modifier.weight(1f))

        SoftKeyBar(
            leftLabel = "yes",
            rightLabel = "no",
        )
    }
}
