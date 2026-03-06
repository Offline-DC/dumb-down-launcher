package com.offlineinc.dumbdownlauncher

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.offlineinc.dumbdownlauncher.launcher.AppIconOverrides
import com.offlineinc.dumbdownlauncher.launcher.AppLabelOverrides
import com.offlineinc.dumbdownlauncher.launcher.KeyDispatcher
import com.offlineinc.dumbdownlauncher.launcher.LaunchResolver
import com.offlineinc.dumbdownlauncher.launcher.LauncherController
import com.offlineinc.dumbdownlauncher.launcher.PlatformPreferences
import com.offlineinc.dumbdownlauncher.launcher.dnd.DndMuteManager
import com.offlineinc.dumbdownlauncher.model.AppItem
import com.offlineinc.dumbdownlauncher.notifications.ui.NotificationsActivity
import com.offlineinc.dumbdownlauncher.ui.AppListScreen
import com.offlineinc.dumbdownlauncher.ui.DND_TOGGLE
import com.offlineinc.dumbdownlauncher.ui.PlatformChoiceDialog
import com.offlineinc.dumbdownlauncher.ui.RestartPhoneDialog

const val ALL_APPS = "__ALL_APPS__"
const val NOTIFICATIONS = "__NOTIFICATIONS__"
const val CHANGE_PLATFORM = "__CHANGE_PLATFORM__"
const val GOOGLE_MESSAGES = "__GOOGLE_MESSAGES__"
const val UBER = "__UBER__"

val WEB_APP_URLS = mapOf(
    GOOGLE_MESSAGES to "https://messages.google.com/web",
    UBER to "https://m.uber.com",
)

class MainActivity : AppCompatActivity() {
    private lateinit var dndMuteManager: DndMuteManager

    private val muteIconPackages: Set<String> get() {
        val platform = PlatformPreferences.getChoice(this)
        return buildSet {
            when (platform) {
                "ios" -> add("com.openbubbles.messaging")
                "android" -> add(GOOGLE_MESSAGES)
            }
            add("com.whatsapp")
            add("com.android.mms")
        }
    }

    private var selectedIndex = 0
    private val items: SnapshotStateList<AppItem> = mutableStateListOf()
    private lateinit var controller: LauncherController
    private val showPlatformDialog = mutableStateOf(false)
    private val showRestartDialog = mutableStateOf(false)

    private var customTabsSession: CustomTabsSession? = null
    private val customTabsConnection = object : CustomTabsServiceConnection() {
        override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
            client.warmup(0)
            customTabsSession = client.newSession(null)?.also {
                it.mayLaunchUrl(Uri.parse(WEB_APP_URLS.getValue(GOOGLE_MESSAGES)), null, null)
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            customTabsSession = null
        }
    }

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

        controller = LauncherController(
            context = this,
            getSelectedIndex = { selectedIndex },
            getItems = { items },
            onStartActivity = { startActivity(it) },
            onNoAnim = { overridePendingTransition(0, 0) }
        )

        setContent {
            val muted by dndMuteManager.muted.collectAsState()
            val showDialog by showPlatformDialog
            val showRestart by showRestartDialog

            LaunchedEffect(muted) {
                applyMutedToItems(muted)
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AppListScreen(
                    title = null,
                    items = items,
                    onActivate = { item ->
                        when (item.packageName) {
                            DND_TOGGLE -> return@AppListScreen
                            GOOGLE_MESSAGES -> openInChrome(WEB_APP_URLS.getValue(GOOGLE_MESSAGES))
                            UBER -> openCustomTab(WEB_APP_URLS.getValue(UBER), "org.chromium.chrome")
                            "com.offlineinc.dumbcontactsync" -> {
                                val component = item.launchComponent ?: return@AppListScreen
                                val platform = PlatformPreferences.getChoice(this@MainActivity)
                                val intent = Intent(Intent.ACTION_MAIN).apply {
                                    addCategory(Intent.CATEGORY_LAUNCHER)
                                    setComponent(component)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    if (platform != null && platform != "skipped") {
                                        putExtra("platform", platform)
                                    }
                                }
                                startActivity(intent)
                                overridePendingTransition(0, 0)
                            }
                            else -> {
                                val component = item.launchComponent ?: return@AppListScreen
                                val intent = Intent(Intent.ACTION_MAIN).apply {
                                    addCategory(Intent.CATEGORY_LAUNCHER)
                                    setComponent(component)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(intent)
                                overridePendingTransition(0, 0)
                            }
                        }
                    },
                    showSoftKeys = true,
                    softKeyLeftLabel = "notifications",
                    softKeyRightLabel = "all apps",
                    onSoftKeyLeft = {
                        startActivity(Intent(this@MainActivity, NotificationsActivity::class.java))
                        overridePendingTransition(0, 0)
                    },
                    onSoftKeyRight = {
                        startActivity(Intent(this@MainActivity, AllAppsActivity::class.java))
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
                            reloadApps()
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }

        if (PlatformPreferences.getChoice(this) == "android") {
            CustomTabsClient.bindCustomTabsService(this, "com.android.chrome", customTabsConnection)
        }

        Thread {
            val loaded = buildMainAppList()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                items.addAll(loaded)
                applyMutedToItems(dndMuteManager.muted.value)
                if (items.isEmpty()) {
                    Toast.makeText(this, "No allowed apps found/installed.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (PlatformPreferences.getChoice(this) == "android") {
            unbindService(customTabsConnection)
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

    private fun reloadApps() {
        items.clear()
        selectedIndex = 0
        Thread {
            val loaded = buildMainAppList()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                items.addAll(loaded)
                applyMutedToItems(dndMuteManager.muted.value)
            }
        }.start()
    }

    private fun buildMainAppList(): List<AppItem> {
        val result = mutableListOf<AppItem>()

        val platform = PlatformPreferences.getChoice(this)
        val messagingPkg = when (platform) {
            "ios" -> "com.openbubbles.messaging"
            "android" -> GOOGLE_MESSAGES
            else -> null  // skipped or null — hide smart txt
        }

        val allowedPackages = listOfNotNull(
            DND_TOGGLE,
            messagingPkg,
            "com.whatsapp",
            "com.android.mms",
            "com.android.contacts",
            "com.android.dialer",
            "com.android.settings",
            "com.google.android.apps.mapslite",
            "com.tcl.camera",
            "com.apple.android.music"
        )

        for (pkg in allowedPackages) {
            when (pkg) {
                DND_TOGGLE -> {
                    result.add(AppItem(DND_TOGGLE, "mute all texts", packageManager.defaultActivityIcon, null, false))
                    continue
                }
                GOOGLE_MESSAGES -> {
                    result.add(AppItem(GOOGLE_MESSAGES, "smart txt", packageManager.defaultActivityIcon, null, false))
                    continue
                }
            }

            try {
                val appInfo = packageManager.getApplicationInfo(pkg, 0)
                val defaultLabel = packageManager.getApplicationLabel(appInfo).toString()
                val label = AppLabelOverrides.getLabel(pkg, defaultLabel).lowercase()
                val defaultIcon = packageManager.getApplicationIcon(appInfo)
                val icon = AppIconOverrides.getIcon(this, pkg, defaultIcon)
                val launchComponent = LaunchResolver.resolveLaunchComponent(packageManager, pkg)
                result.add(AppItem(pkg, label, icon, launchComponent, isMuted = false))
            } catch (_: Exception) { }
        }

        // Uber — use icon from real app if installed, otherwise default
        val uberIcon = try {
            packageManager.getApplicationIcon(packageManager.getApplicationInfo("com.ubercab", 0))
        } catch (_: Exception) { packageManager.defaultActivityIcon }
        result.add(AppItem(UBER, "uber", uberIcon, null, false))

        return result
    }

    private fun openInChrome(url: String) {
        MouseAccessibilityService.setMouseEnabled(this, true)
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage("com.android.chrome")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }.let { startActivity(it) }
        overridePendingTransition(0, 0)
    }

    private fun openCustomTab(url: String, pkg: String = "com.android.chrome") {
        MouseAccessibilityService.setMouseEnabled(this, true)
        androidx.browser.customtabs.CustomTabsIntent.Builder(customTabsSession)
            .setUrlBarHidingEnabled(true)
            .build()
            .apply { intent.setPackage(pkg) }
            .launchUrl(this, Uri.parse(url))
        overridePendingTransition(0, 0)
    }

    private fun applyMutedToItems(muted: Boolean) {
        val currentMuteIconPackages = muteIconPackages
        for (i in items.indices) {
            val it = items[i]
            if (it.packageName in currentMuteIconPackages) {
                items[i] = it.copy(isMuted = muted)
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
