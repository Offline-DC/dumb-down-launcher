package com.offlineinc.dumbdownlauncher.diagnostics

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.offlineinc.dumbdownlauncher.BuildConfig
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hidden diagnostics menu, opened via long-press on the "weather" row in
 * All Apps (see AllAppsActivity). Replaces the old behavior where that
 * long-press flipped the rolling-ADB-logs flag directly — both diagnostics
 * opt-ins now live here:
 *
 *   • rolling adb logs   – the rolling 24h logcat tail
 *                          ([RebootLoggingStore] / [RebootLoggingService])
 *   • battery analysis   – battery sampling + privileged dumpsys snapshots
 *                          ([DiagnosticsStore] / [DiagnosticsService]);
 *                          row only shown when BuildConfig.DIAGNOSTICS_ENABLED
 *   • submit logs        – zips the whole diag/ tree (battery + rolling
 *                          logs) and uploads it to S3 via the backend's
 *                          presigned-URL flow ([DiagLogUploader])
 *
 * D-pad navigable, mirroring DiagnosticsActivity:
 *   ↑/↓     – move selection
 *   Center  – toggle selected row
 *   Back    – exit
 */
class DiagMenuActivity : AppCompatActivity() {

    private lateinit var rebootLoggingStore: RebootLoggingStore
    private lateinit var diagnosticsStore: DiagnosticsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF000000.toInt()

        rebootLoggingStore = RebootLoggingStore(this)
        diagnosticsStore = DiagnosticsStore(this)

        setContent {
            DiagMenuScreen(
                rebootLoggingStore = rebootLoggingStore,
                diagnosticsStore = diagnosticsStore,
                onToggleRollingLogs = { newValue ->
                    rebootLoggingStore.enabled = newValue
                    if (newValue) {
                        rebootLoggingStore.enabledSinceMs = System.currentTimeMillis()
                        RebootLoggingService.startIfEnabled(applicationContext)
                        Toast.makeText(
                            this@DiagMenuActivity,
                            "diagnostic logging on — rolling 24h logcat being collected",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        RebootLoggingService.stop(applicationContext)
                        Toast.makeText(
                            this@DiagMenuActivity,
                            "diagnostic logging off",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                onToggleBatteryAnalysis = { newValue ->
                    if (newValue) {
                        if (diagnosticsStore.enabledSinceMs == 0L) {
                            diagnosticsStore.enabledSinceMs = System.currentTimeMillis()
                        }
                        diagnosticsStore.enabled = true
                        DiagnosticsService.startIfEnabled(this@DiagMenuActivity)
                        Toast.makeText(
                            this@DiagMenuActivity,
                            "battery analysis on",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        diagnosticsStore.enabled = false
                        DiagnosticsService.stop(this@DiagMenuActivity)
                        Toast.makeText(
                            this@DiagMenuActivity,
                            "battery analysis off",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                onSubmitLogs = { onDone ->
                    Toast.makeText(
                        this@DiagMenuActivity,
                        "submitting logs…",
                        Toast.LENGTH_SHORT,
                    ).show()
                    lifecycleScope.launch(Dispatchers.IO) {
                        val result = runCatching { DiagLogUploader.submit(applicationContext) }
                        withContext(Dispatchers.Main) {
                            result.fold(
                                onSuccess = { upload ->
                                    Toast.makeText(
                                        this@DiagMenuActivity,
                                        "logs submitted (${upload.sizeBytes / 1024 / 1024} MB)",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                },
                                onFailure = { t ->
                                    Toast.makeText(
                                        this@DiagMenuActivity,
                                        "submit failed: ${t.message ?: "unknown error"}",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                },
                            )
                            onDone()
                        }
                    }
                },
                onBack = { finish() },
            )
        }
    }
}

// ── Compose UI ─────────────────────────────────────────────────────────

@Composable
private fun DiagMenuScreen(
    rebootLoggingStore: RebootLoggingStore,
    diagnosticsStore: DiagnosticsStore,
    onToggleRollingLogs: (Boolean) -> Unit,
    onToggleBatteryAnalysis: (Boolean) -> Unit,
    onSubmitLogs: (onDone: () -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val fontFamily = DumbTheme.BioRhyme
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    var rollingLogsEnabled by remember { mutableStateOf(rebootLoggingStore.enabled) }
    var batteryAnalysisEnabled by remember { mutableStateOf(diagnosticsStore.enabled) }
    var uploading by remember { mutableStateOf(false) }

    val rows = buildList {
        add(
            MenuRow.Toggle(
                label = "rolling adb logs",
                value = rollingLogsEnabled,
                onActivate = {
                    val next = !rollingLogsEnabled
                    rollingLogsEnabled = next
                    onToggleRollingLogs(next)
                },
            )
        )
        // Compile-time gated, same as the long-press-on-quack hook in
        // AllAppsActivity — production builds drop the row entirely.
        if (BuildConfig.DIAGNOSTICS_ENABLED) {
            add(
                MenuRow.Toggle(
                    label = "battery analysis",
                    value = batteryAnalysisEnabled,
                    onActivate = {
                        val next = !batteryAnalysisEnabled
                        batteryAnalysisEnabled = next
                        onToggleBatteryAnalysis(next)
                    },
                )
            )
        }
        add(
            MenuRow.Action(
                label = "submit logs",
                secondary = if (uploading) "uploading…" else "press center to upload",
                onActivate = {
                    // One in-flight upload at a time; center-mashing while
                    // a 40 MB zip crawls up LTE must not queue duplicates.
                    if (!uploading) {
                        uploading = true
                        onSubmitLogs { uploading = false }
                    }
                },
            )
        )
    }

    var selectedIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(modifier = Modifier.fillMaxSize().background(DumbTheme.Colors.Black)) {
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
                            selectedIndex = (selectedIndex + 1) % rows.size
                            true
                        }
                        Key.DirectionUp -> {
                            selectedIndex = (selectedIndex - 1 + rows.size) % rows.size
                            true
                        }
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            rows[selectedIndex].onActivate()
                            true
                        }
                        Key.Back -> { onBack(); true }
                        else -> false
                    }
                }
        ) {
            BasicText(
                text = "diagnostics",
                style = TextStyle(color = DumbTheme.Colors.White, fontSize = 20.sp, fontFamily = fontFamily),
                modifier = Modifier.padding(bottom = 8.dp),
            )

            BasicText(
                text = "rolling adb logs keeps a 24h logcat buffer on device. "
                    + "battery analysis collects battery samples and privileged "
                    + "dumpsys snapshots. submit logs zips both and uploads "
                    + "them for support. press center to toggle / run.",
                style = TextStyle(
                    color = DumbTheme.Colors.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    fontFamily = fontFamily,
                ),
                modifier = Modifier.padding(bottom = 10.dp),
            )

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(rows) { index, row ->
                        DiagMenuRow(row = row, selected = index == selectedIndex, fontFamily = fontFamily)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagMenuRow(
    row: MenuRow,
    selected: Boolean,
    fontFamily: androidx.compose.ui.text.font.FontFamily,
) {
    val bg = if (selected) DumbTheme.Colors.Yellow else Color.Transparent
    val fg = if (selected) DumbTheme.Colors.Black else DumbTheme.Colors.White
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        BasicText(
            text = row.label,
            style = TextStyle(fontFamily = fontFamily, fontSize = 18.sp, color = fg),
        )
        Spacer(Modifier.size(2.dp))
        BasicText(
            text = when (row) {
                is MenuRow.Toggle -> if (row.value) "on" else "off"
                is MenuRow.Action -> row.secondary
            },
            style = TextStyle(
                fontFamily = fontFamily,
                fontSize = 12.sp,
                color = if (selected) DumbTheme.Colors.Black else DumbTheme.Colors.White.copy(alpha = 0.6f),
            ),
        )
        Spacer(Modifier.height(2.dp))
    }
}

private sealed class MenuRow(val label: String, val onActivate: () -> Unit) {
    class Toggle(label: String, val value: Boolean, onActivate: () -> Unit) :
        MenuRow(label, onActivate)

    class Action(label: String, val secondary: String, onActivate: () -> Unit) :
        MenuRow(label, onActivate)
}
