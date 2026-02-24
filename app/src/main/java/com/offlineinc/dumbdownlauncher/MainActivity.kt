package com.offlineinc.dumbdownlauncher

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.offlineinc.dumbdownlauncher.launcher.AppIconOverrides
import com.offlineinc.dumbdownlauncher.launcher.AppLabelOverrides
import com.offlineinc.dumbdownlauncher.launcher.KeyDispatcher
import com.offlineinc.dumbdownlauncher.launcher.LaunchResolver
import com.offlineinc.dumbdownlauncher.launcher.LauncherController
import com.offlineinc.dumbdownlauncher.launcher.dnd.DndMuteManager
import com.offlineinc.dumbdownlauncher.model.AppItem
import com.offlineinc.dumbdownlauncher.notifications.ui.NotificationsActivity
import com.offlineinc.dumbdownlauncher.ui.AppListScreen
import com.offlineinc.dumbdownlauncher.ui.DND_TOGGLE

const val ALL_APPS = "__ALL_APPS__"
const val NOTIFICATIONS = "__NOTIFICATIONS__"

class MainActivity : AppCompatActivity() {
    private lateinit var dndMuteManager: DndMuteManager

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

    private val muteIconPackages = setOf(
        "com.openbubbles.messaging",
        "com.whatsapp",
        "com.android.mms"
    )

    private var selectedIndex = 0
    private val items: SnapshotStateList<AppItem> = mutableStateListOf()
    private lateinit var controller: LauncherController

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        window.statusBarColor = 0xFF000000.toInt()

        dndMuteManager = DndMuteManager(
            appContext = applicationContext,
            scope = lifecycleScope,
        )
        dndMuteManager.refreshFromSystem()

        loadApps()

        controller = LauncherController(
            context = this,
            getSelectedIndex = { selectedIndex },
            getItems = { items },
            onStartActivity = { startActivity(it) },
            onNoAnim = { overridePendingTransition(0, 0) }
        )

        setContent {
            val muted by dndMuteManager.muted.collectAsState()

            LaunchedEffect(muted) {
                applyMutedToItems(muted)
            }

            AppListScreen(
                title = null,
                items = items,
                onActivate = { item ->
                    if (item.packageName == DND_TOGGLE) return@AppListScreen
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
                messagesMuted = muted,
                onToggleMessagesMuted = { enabled ->
                    if (!dndMuteManager.hasPolicyAccess()) {
                        startActivity(dndMuteManager.makePolicyAccessIntent())
                        return@AppListScreen
                    }
                    getSharedPreferences("launcher_prefs", MODE_PRIVATE)
                        .edit { putBoolean("messages_muted", enabled) }
                    dndMuteManager.setMuted(enabled)
                }
            )
        }

        if (items.isEmpty()) {
            Toast.makeText(this, "No allowed apps found/installed.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        MouseAccessibilityService.forceDisable(this)
        overridePendingTransition(0, 0)
    }

    private fun loadApps() {
        items.clear()

        for (pkg in allowedPackages) {
            if (pkg == DND_TOGGLE) {
                val icon = packageManager.defaultActivityIcon
                items.add(
                    AppItem(
                        packageName = DND_TOGGLE,
                        label = "mute all texts",
                        icon = icon,
                        launchComponent = null,
                        isMuted = false
                    )
                )
                continue
            }

            try {
                val appInfo = packageManager.getApplicationInfo(pkg, 0)
                val defaultLabel = packageManager.getApplicationLabel(appInfo).toString()
                val label = AppLabelOverrides.getLabel(pkg, defaultLabel).lowercase()
                val defaultIcon = packageManager.getApplicationIcon(appInfo)
                val icon = AppIconOverrides.getIcon(this, pkg, defaultIcon)
                val launchComponent = LaunchResolver.resolveLaunchComponent(packageManager, pkg)
                items.add(AppItem(pkg, label, icon, launchComponent, isMuted = false))
            } catch (_: Exception) { }
        }

        val uberPkg = resolveInstalledPackage("com.ubercab", "com.offline.uberlauncher")
        if (uberPkg != null) {
            try {
                val appInfo = packageManager.getApplicationInfo(uberPkg, 0)
                val defaultIcon = packageManager.getApplicationIcon(appInfo)
                val icon = AppIconOverrides.getIcon(this, uberPkg, defaultIcon)
                val launchComponent = LaunchResolver.resolveLaunchComponent(packageManager, uberPkg)
                items.add(AppItem(uberPkg, "uber", icon, launchComponent, isMuted = false))
            } catch (_: Exception) { }
        }

        applyMutedToItems(dndMuteManager.muted.value)
        selectedIndex = 0.coerceAtMost(items.lastIndex)
    }

    private fun applyMutedToItems(muted: Boolean) {
        for (i in items.indices) {
            val it = items[i]
            if (it.packageName in muteIconPackages) {
                items[i] = it.copy(isMuted = muted)
            }
        }
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
            else -> super.dispatchKeyEvent(event)
        }
    }
}
