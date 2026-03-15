package com.offlineinc.dumbdownlauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.offlineinc.dumbdownlauncher.launcher.DpadShortcutResolver
import com.offlineinc.dumbdownlauncher.launcher.KeyDispatcher
import com.offlineinc.dumbdownlauncher.launcher.LauncherController
import com.offlineinc.dumbdownlauncher.launcher.PlatformPreferences
import com.offlineinc.dumbdownlauncher.launcher.dnd.DndMuteManager
import com.offlineinc.dumbdownlauncher.notifications.ui.NotificationsActivity
import com.offlineinc.dumbdownlauncher.ui.DpadDirection
import com.offlineinc.dumbdownlauncher.ui.HomeScreen
import com.offlineinc.dumbdownlauncher.ui.PlatformChoiceDialog
import com.offlineinc.dumbdownlauncher.ui.RestartPhoneDialog

const val ALL_APPS = "__ALL_APPS__"
const val NOTIFICATIONS = "__NOTIFICATIONS__"
const val CHANGE_PLATFORM = "__CHANGE_PLATFORM__"
const val GOOGLE_MESSAGES = "__GOOGLE_MESSAGES__"
const val CHECK_UPDATES = "__CHECK_UPDATES__"
const val WEB_KEYBOARD = "__WEB_KEYBOARD__"
const val DEVICE_PAIRING = "__DEVICE_PAIRING__"

val WEB_APP_URLS = mapOf(
    GOOGLE_MESSAGES to "https://messages.google.com/web",
)

class MainActivity : AppCompatActivity() {
    private lateinit var dndMuteManager: DndMuteManager
    private lateinit var controller: LauncherController
    private val showPlatformDialog = mutableStateOf(false)
    private val showRestartDialog = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        window.statusBarColor = 0xFF000000.toInt()

        dndMuteManager = DndMuteManager(
            appContext = applicationContext,
            scope = lifecycleScope,
        )
        dndMuteManager.refreshFromSystem()

        val choiceOnCreate = PlatformPreferences.getChoice(this)
        Log.d("PLATFORM", "onCreate: getChoice=$choiceOnCreate showDialog=${showPlatformDialog.value}")
        if (choiceOnCreate != "ios" && choiceOnCreate != "android") {
            showPlatformDialog.value = true
            Log.d("PLATFORM", "onCreate: setting showPlatformDialog=true")
        }

        // Controller is kept for dialer digit launching from KeyDispatcher
        controller = LauncherController(
            context = this,
            getSelectedIndex = { 0 },
            getItems = { emptyList() },
            onStartActivity = { startActivity(it) },
            onNoAnim = { overridePendingTransition(0, 0) }
        )

        setContent {
            val muted by dndMuteManager.muted.collectAsState()
            val showDialog by showPlatformDialog
            val showRestart by showRestartDialog

            Box(modifier = Modifier.fillMaxSize()) {
                HomeScreen(
                    messagesMuted = muted,
                    onOpenAppsGrid = {
                        startActivity(Intent(this@MainActivity, MainAppsGridActivity::class.java))
                        overridePendingTransition(0, 0)
                    },
                    onOpenNotifications = {
                        startActivity(Intent(this@MainActivity, NotificationsActivity::class.java))
                        overridePendingTransition(0, 0)
                    },
                    onOpenAllApps = {
                        startActivity(Intent(this@MainActivity, AllAppsActivity::class.java))
                        overridePendingTransition(0, 0)
                    },
                    onDpadDirection = { direction ->
                        launchDpadShortcut(direction)
                    },
                )

                if (showDialog) {
                    PlatformChoiceDialog(
                        onChoose = { choice ->
                            val previousChoice = PlatformPreferences.getChoice(this@MainActivity)
                            if (choice != "skipped" && choice != "skip") {
                                PlatformPreferences.saveChoice(this@MainActivity, choice)
                                Log.d("PLATFORM", "onChoose: saved choice=$choice")
                            } else {
                                Log.d("PLATFORM", "onChoose: skipped/dismissed, not saving")
                            }
                            showPlatformDialog.value = false
                            if (previousChoice != null && previousChoice != choice) {
                                showRestartDialog.value = true
                            }
                        }
                    )
                }

                if (showRestart) {
                    RestartPhoneDialog(
                        onRestart = {
                            showRestartDialog.value = false
                            Thread {
                                try {
                                    ProcessBuilder("su", "-c", "reboot")
                                        .redirectErrorStream(true)
                                        .start()
                                        .waitFor()
                                } catch (t: Throwable) {
                                    Log.e("MainActivity", "Reboot failed: ${t.message}")
                                }
                            }.start()
                        },
                        onDismiss = { showRestartDialog.value = false }
                    )
                }
            }
        }

        val permissionsNeeded = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (permissionsNeeded.isNotEmpty()) {
            requestPermissions(permissionsNeeded.toTypedArray(), 0)
        }
    }

    override fun onResume() {
        super.onResume()
        MouseAccessibilityService.forceDisable(this)
        overridePendingTransition(0, 0)
        if (PlatformPreferences.consumeShowDialog(this)) {
            showPlatformDialog.value = true
        }
    }

    /**
     * Launch the app assigned to a D-pad direction in TCL Flip 2 settings.
     * Reads from Settings.System "keyshortcut_{direction}key".
     */
    private fun launchDpadShortcut(direction: DpadDirection) {
        val resolverDirection = when (direction) {
            DpadDirection.UP    -> DpadShortcutResolver.Direction.UP
            DpadDirection.DOWN  -> DpadShortcutResolver.Direction.DOWN
            DpadDirection.LEFT  -> DpadShortcutResolver.Direction.LEFT
            DpadDirection.RIGHT -> DpadShortcutResolver.Direction.RIGHT
        }

        val intent = DpadShortcutResolver.buildLaunchIntent(this, resolverDirection)
        if (intent != null) {
            try {
                startActivity(intent)
                overridePendingTransition(0, 0)
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to launch shortcut for $direction: ${e.message}")
                Toast.makeText(this, "Shortcut app not found", Toast.LENGTH_SHORT).show()
            }
        } else {
            // No shortcut configured — open TCL Settings so user can set one up
            openTclKeyShortcutSettings()
        }
    }

    /**
     * Opens the TCL Flip 2 Settings app. Tries the key shortcut
     * settings page first, falls back to main settings.
     */
    private fun openTclKeyShortcutSettings() {
        try {
            // TCL key shortcut settings activity
            val intent = Intent().apply {
                component = android.content.ComponentName(
                    "com.android.settings",
                    "com.android.settings.Settings\$KeyShortcutSettingsActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            overridePendingTransition(0, 0)
        } catch (_: Exception) {
            try {
                // Fallback: open main Settings
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                overridePendingTransition(0, 0)
            } catch (_: Exception) {
                Toast.makeText(this, "Set shortcuts in Settings → Key shortcuts", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.d("DUMB_KEYS", "DOWN keyCode=${event.keyCode} scanCode=${event.scanCode} unicode=${event.unicodeChar}")
        }

        val result = KeyDispatcher.handle(event)

        if (result.consumed) {
            Log.d("DUMB_KEYS", "CONSUMED → notif=${result.openNotifications} allApps=${result.openAllApps} dial=${result.dialDigits} blankDial=${result.openDialerBlank}")
        }

        if (!result.consumed) return super.dispatchKeyEvent(event)

        return when {
            result.openNotifications -> {
                startActivity(Intent(this, NotificationsActivity::class.java))
                overridePendingTransition(0, 0)
                true
            }
            result.openAllApps -> {
                startActivity(Intent(this, AllAppsActivity::class.java))
                overridePendingTransition(0, 0)
                true
            }
            result.openDialerBlank -> {
                controller.openDialerBlank()
                true
            }
            result.dialDigits != null -> {
                controller.openDialerWithDigits(result.dialDigits)
                true
            }
            // Center/Enter is handled by HomeScreen's onPreviewKeyEvent → onOpenAppsGrid
            // so it won't reach here, but if it does, open the grid
            result.activateSelected -> {
                startActivity(Intent(this, MainAppsGridActivity::class.java))
                overridePendingTransition(0, 0)
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }
}
