package com.offlineinc.dumbdownlauncher.quack

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
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
import com.offlineinc.dumbdownlauncher.ui.SoftKeyBar
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

/**
 * Hosts the Quack anonymous message board within the launcher app.
 * Follows the same pattern as ContactSyncActivity.
 *
 * On first launch, shows a full-page consent screen (not a modal) asking
 * if the user wants quack to access their location. Soft-left = yes,
 * soft-right = no. If no, finishes back to all apps. If yes, remembers
 * the choice, kicks off the launcher-wide location prewarm + periodic
 * refresh, and requests the runtime location permission.
 */
class QuackActivity : AppCompatActivity() {

    companion object {
        private const val LOC_PERM_REQ = 42
    }

    private lateinit var viewModel: QuackViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF000000.toInt()

        viewModel = ViewModelProvider(this)[QuackViewModel::class.java]

        setContent {
            var needsConsent by remember {
                mutableStateOf(!QuackLocationConsentStore.hasConsented(this@QuackActivity))
            }

            if (needsConsent) {
                LocationConsentScreen(
                    onAccept = {
                        QuackLocationConsentStore.setConsented(this@QuackActivity, true)
                        // Kick off the launcher-wide location prewarm + periodic
                        // refresh now that the user has opted in. Previously this
                        // ran unconditionally at every boot; now it waits until
                        // consent is granted in either quack or weather.
                        LocationConsent.onConsentGranted(this@QuackActivity)
                        needsConsent = false
                        requestLocationPermission()
                    },
                    onDecline = { finish() },
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DumbTheme.Colors.Black)
                ) {
                    QuackScreen(
                        viewModel = viewModel,
                        onBack = { finish() },
                    )
                }
            }
        }

        // If consent was already granted, go straight to requesting the runtime
        // location permission and kicking the feed off — otherwise wait until
        // the consent screen's "accept" path calls requestLocationPermission.
        if (QuackLocationConsentStore.hasConsented(this)) {
            requestLocationPermission()
        }
    }

    private fun requestLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startLocation()
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                LOC_PERM_REQ,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        overridePendingTransition(0, 0)
        // Silently refresh posts (not location) every time the user returns
        // to the quack screen so the feed is always fresh. Once loaded, focus
        // snaps to the most recent quack (index 0). No-op until consent has
        // been granted — otherwise we'd fire off a feed load before we even
        // have a location cache.
        if (QuackLocationConsentStore.hasConsented(this)) {
            viewModel.onResumeRefresh()
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(req, perms, grants)
        if (req == LOC_PERM_REQ && grants.isNotEmpty() && grants[0] == PackageManager.PERMISSION_GRANTED) {
            viewModel.startLocation()
        } else {
            Toast.makeText(this, "Location permission required for quack", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}

// ── Location consent — full page, matches weather's consent screen style ────

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
            text = "quack",
            style = DumbTheme.Text.PageTitle.copy(color = DumbTheme.Colors.Yellow),
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )

        Spacer(Modifier.height(8.dp))

        BasicText(
            text = "quack needs your location to show quacks within 25 miles of you.",
            style = DumbTheme.Text.Body,
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )

        Spacer(Modifier.height(16.dp))

        BasicText(
            text = "allow quack to access your location?",
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
