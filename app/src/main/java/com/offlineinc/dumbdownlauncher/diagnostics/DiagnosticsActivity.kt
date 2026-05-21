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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.BuildConfig
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

/**
 * Hidden settings screen for the battery diagnostics module. Opened via
 * long-press on the "quack" row in All Apps (see AllAppsActivity).
 *
 * D-pad navigable:
 *   ↑/↓     – move selection
 *   Center  – toggle / activate selected row
 *   Back    – exit
 *
 * No real "settings" menu UI exists in the launcher yet, so this screen
 * mirrors the AppListScreen style: BasicText rows on a black background
 * with the yellow accent for the selected row.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var store: DiagnosticsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF000000.toInt()

        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            Toast.makeText(this, "Diagnostics disabled in this build", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        store = DiagnosticsStore(this)

        setContent {
            DiagnosticsScreen(
                store = store,
                onToggleEnabled = { newValue ->
                    if (newValue) {
                        if (store.enabledSinceMs == 0L) store.enabledSinceMs = System.currentTimeMillis()
                        store.enabled = true
                        DiagnosticsService.startIfEnabled(this@DiagnosticsActivity)
                        Toast.makeText(this@DiagnosticsActivity, "Diagnostics started", Toast.LENGTH_SHORT).show()
                    } else {
                        store.enabled = false
                        DiagnosticsService.stop(this@DiagnosticsActivity)
                        Toast.makeText(this@DiagnosticsActivity, "Diagnostics stopped", Toast.LENGTH_SHORT).show()
                    }
                },
                onResetSession = {
                    val fresh = store.resetSession()
                    Toast.makeText(this@DiagnosticsActivity, "New session: ${fresh.take(8)}…", Toast.LENGTH_SHORT).show()
                },
                onBack = { finish() },
            )
        }
    }
}

// ── Compose UI ─────────────────────────────────────────────────────────

@Composable
private fun DiagnosticsScreen(
    store: DiagnosticsStore,
    onToggleEnabled: (Boolean) -> Unit,
    onResetSession: () -> Unit,
    onBack: () -> Unit,
) {
    val fontFamily = DumbTheme.BioRhyme
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    var enabled by remember { mutableStateOf(store.enabled) }
    val sessionId = remember { store.captureSessionId }
    var sessionVersion by remember { mutableIntStateOf(0) }
    // Reading captureSessionId again after resetSession() yields the new uuid.
    val displaySessionId = remember(sessionVersion) { store.captureSessionId }

    val rows = listOf(
        Row.Toggle("diagnostics", value = enabled) {
            val next = !enabled
            enabled = next
            onToggleEnabled(next)
        },
        Row.Action("reset session") {
            sessionVersion += 1
            onResetSession()
        },
        Row.Info("session id", displaySessionId.take(8) + "…"),
        Row.Info("enabled since", if (store.enabledSinceMs == 0L) "—" else formatMs(store.enabledSinceMs)),
        Row.Info("schema version", DiagnosticsConfig.SCHEMA_VERSION.toString()),
        Row.Info("adb pull", "/sdcard/Android/data/${BuildConfig.APPLICATION_ID}/files/diag/"),
    )

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
                            when (val r = rows[selectedIndex]) {
                                is Row.Toggle -> r.onToggle()
                                is Row.Action -> r.onActivate()
                                is Row.Info -> { /* no-op */ }
                            }
                            true
                        }
                        Key.Back -> { onBack(); true }
                        else -> false
                    }
                }
        ) {
            BasicText(
                text = "battery diagnostics",
                style = TextStyle(color = DumbTheme.Colors.White, fontSize = 20.sp, fontFamily = fontFamily),
                modifier = Modifier.padding(bottom = 8.dp),
            )

            BasicText(
                text = "Collects battery samples, screen/power/doze events, and "
                    + "privileged dumpsys + logcat snapshots into /sdcard/Android/data/"
                    + "${BuildConfig.APPLICATION_ID}/files/diag/. Files are also written to "
                    + "the app private dir as the canonical copy. Use the adb pull command "
                    + "below to retrieve them.",
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
                        DiagRow(row = row, selected = index == selectedIndex, fontFamily = fontFamily)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagRow(
    row: Row,
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
        val secondary: String? = when (row) {
            is Row.Toggle -> if (row.value) "on" else "off"
            is Row.Info -> row.value
            is Row.Action -> "press center to run"
        }
        if (secondary != null) {
            Spacer(Modifier.size(2.dp))
            BasicText(
                text = secondary,
                style = TextStyle(
                    fontFamily = fontFamily,
                    fontSize = 12.sp,
                    color = if (selected) DumbTheme.Colors.Black else DumbTheme.Colors.White.copy(alpha = 0.6f),
                ),
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

private sealed class Row(val label: String) {
    class Toggle(label: String, val value: Boolean, val onToggle: () -> Unit) : Row(label)
    class Action(label: String, val onActivate: () -> Unit) : Row(label)
    class Info(label: String, val value: String) : Row(label)
}

private fun formatMs(ms: Long): String {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
    return fmt.format(java.util.Date(ms))
}
