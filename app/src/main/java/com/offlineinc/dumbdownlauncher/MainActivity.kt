package com.offlineinc.dumbdownlauncher

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.core.content.ContextCompat
import com.offlineinc.dumbdownlauncher.launcher.KeyDispatcher
import com.offlineinc.dumbdownlauncher.launcher.LaunchResolver
import com.offlineinc.dumbdownlauncher.launcher.LauncherController
import com.offlineinc.dumbdownlauncher.model.AppItem
import com.offlineinc.dumbdownlauncher.notifications.ui.NotificationsActivity
import com.offlineinc.dumbdownlauncher.ui.AppListScreen
import com.offlineinc.dumbdownlauncher.ui.DND_TOGGLE
import androidx.core.content.edit

const val ALL_APPS = "__ALL_APPS__"
const val NOTIFICATIONS = "__NOTIFICATIONS__"

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("launcher_prefs", MODE_PRIVATE) }

    private var messagesMuted by mutableStateOf(false)

    private val messageApps = listOf(
        "com.openbubbles.messaging",
        "com.whatsapp",
        "com.android.mms"
    )

    private val allowedPackages = listOf(
        DND_TOGGLE,
        "com.openbubbles.messaging",
        "com.whatsapp",
        "com.android.mms",
        "com.android.contacts",
        "com.android.dialer",
        "com.android.settings",
        "com.google.android.apps.mapslite",
        "com.tcl.camera",
        "com.apple.android.music"
    )

    private var selectedIndex = 0
    private val items: SnapshotStateList<AppItem> = mutableStateListOf()
    private lateinit var controller: LauncherController

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        messagesMuted = prefs.getBoolean("messages_muted", false)

        window.statusBarColor = 0xFF000000.toInt()

        loadApps()

        controller = LauncherController(
            context = this,
            getSelectedIndex = { selectedIndex },
            getItems = { items },
            onStartActivity = { startActivity(it) },
            onNoAnim = { overridePendingTransition(0, 0) }
        )

        setContent {
            AppListScreen(
                title = null,
                items = items,
                onActivate = { item ->
                    if (item.packageName == DND_TOGGLE) {
                        return@AppListScreen
                    }

                    val component = item.launchComponent ?: return@AppListScreen
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        setComponent(component)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                },

                showSoftKeys = true,
                softKeyLeftLabel = "notifications",
                softKeyRightLabel = "all apps",
                onSoftKeyLeft = {
                    startActivity(Intent(this, NotificationsActivity::class.java))
                    overridePendingTransition(0, 0)
                },
                onSoftKeyRight = {
                    startActivity(Intent(this, AllAppsActivity::class.java))
                    overridePendingTransition(0, 0)
                },
                messagesMuted = messagesMuted,
                onToggleMessagesMuted = { enabled ->
                    messagesMuted = enabled
                    prefs.edit { putBoolean("messages_muted", enabled) }
                    applyMuteState(enabled)
                    loadApps()
                },
            )
        }


        if (items.isEmpty()) {
            Toast.makeText(this, "No allowed apps found/installed.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        MouseAccessibilityService.forceDisable(this)
        applyMuteState(messagesMuted)
        overridePendingTransition(0, 0)
    }

    private fun runSu(cmd: String) {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "sh -c \"$cmd\""))
        } catch (e: Exception) {
            Log.e("DUMB_DND", "Shell failed: $cmd", e)
        }
    }

    private fun applyMuteState(enabled: Boolean) {
        if (enabled) {
            runSu("cmd notification set_dnd priority")

            for (pkg in messageApps) {
                runSu("cmd notification disallow_dnd $pkg")
            }
        } else {
            runSu("cmd notification set_dnd off")
        }
    }

    private fun loadApps() {
        items.clear()

        val muteIconPackages = setOf(
            "com.openbubbles.messaging",
            "com.whatsapp",
            "com.android.mms"
        )

        for (pkg in allowedPackages) {
            try {
                if (pkg == DND_TOGGLE) {
                    val icon = packageManager.defaultActivityIcon

                    items.add(
                        AppItem(
                            packageName = DND_TOGGLE,
                            label = "mute all texts",
                            icon = icon,
                            launchComponent = null
                        )
                    )
                    continue
                }

                val appInfo = packageManager.getApplicationInfo(pkg, 0)

                val defaultLabel =
                    packageManager.getApplicationLabel(appInfo).toString()

                val label =
                    com.offlineinc.dumbdownlauncher.launcher.AppLabelOverrides
                        .getLabel(pkg, defaultLabel)
                        .lowercase()

                val defaultIcon =
                    packageManager.getApplicationIcon(appInfo)

                val icon =
                    com.offlineinc.dumbdownlauncher.launcher.AppIconOverrides
                        .getIcon(this, pkg, defaultIcon)

                val launchComponent =
                    LaunchResolver.resolveLaunchComponent(packageManager, pkg)

                val isMuted = messagesMuted && pkg in muteIconPackages

                items.add(
                    AppItem(
                        pkg,
                        label,
                        icon,
                        launchComponent,
                        isMuted = isMuted
                    )
                )

            } catch (_: Exception) {
                // ignore
            }
        }

        val uberPkg = resolveInstalledPackage(
            "com.ubercab",
            "com.offline.uberlauncher"
        )

        if (uberPkg != null) {
            try {
                val appInfo = packageManager.getApplicationInfo(uberPkg, 0)

                val defaultIcon =
                    packageManager.getApplicationIcon(appInfo)

                val icon =
                    com.offlineinc.dumbdownlauncher.launcher.AppIconOverrides
                        .getIcon(this, uberPkg, defaultIcon)

                val launchComponent =
                    LaunchResolver.resolveLaunchComponent(packageManager, uberPkg)

                items.add(
                    AppItem(
                        packageName = uberPkg,
                        label = "uber",
                        icon = icon,
                        launchComponent = launchComponent,
                        isMuted = false
                    )
                )
            } catch (_: Exception) { }
        }

        selectedIndex = 0.coerceAtMost(items.lastIndex)
    }

    private fun resolveInstalledPackage(primary: String, fallback: String): String? {
        return try {
            packageManager.getApplicationInfo(primary, 0)
            primary
        } catch (_: Exception) {
            try {
                packageManager.getApplicationInfo(fallback, 0)
                fallback
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.d(
                "DUMB_KEYS",
                "DOWN keyCode=${event.keyCode} scanCode=${event.scanCode} unicode=${event.unicodeChar}"
            )
        }

        val result = KeyDispatcher.handle(event)

        if (result.consumed) {
            Log.d(
                "DUMB_KEYS",
                "CONSUMED → notif=${result.openNotifications} allApps=${result.openAllApps} dial=${result.dialDigits} blankDial=${result.openDialerBlank}"
            )
        }

        if (!result.consumed) return super.dispatchKeyEvent(event)

        return when {
            result.openNotifications -> {
                Log.d("DUMB_KEYS", "ACTION: Open Notifications")
                startActivity(Intent(this, NotificationsActivity::class.java))
                overridePendingTransition(0, 0)
                true
            }
            result.openAllApps -> {
                Log.d("DUMB_KEYS", "ACTION: Open All Apps")
                startActivity(Intent(this, AllAppsActivity::class.java))
                overridePendingTransition(0, 0)
                true
            }
            result.openDialerBlank -> {
                Log.d("DUMB_KEYS", "ACTION: Open Blank Dialer")
                controller.openDialerBlank()
                true
            }
            result.dialDigits != null -> {
                Log.d("DUMB_KEYS", "ACTION: Dial Digit ${result.dialDigits}")
                controller.openDialerWithDigits(result.dialDigits)
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }
}
