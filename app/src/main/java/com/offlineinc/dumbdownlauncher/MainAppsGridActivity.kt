package com.offlineinc.dumbdownlauncher

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.compose.runtime.mutableStateListOf
import com.offlineinc.dumbdownlauncher.launcher.AppIconOverrides
import com.offlineinc.dumbdownlauncher.launcher.AppLabelOverrides
import com.offlineinc.dumbdownlauncher.launcher.KeyDispatcher
import com.offlineinc.dumbdownlauncher.launcher.LaunchResolver
import com.offlineinc.dumbdownlauncher.launcher.LauncherController
import com.offlineinc.dumbdownlauncher.launcher.PlatformPreferences
import com.offlineinc.dumbdownlauncher.model.AppItem
import com.offlineinc.dumbdownlauncher.ui.MainAppGridScreen

/**
 * Displays a 3×3 grid of the 9 main apps.
 * Opened from the homepage when the user presses Center/Enter.
 * Back key returns to the homepage (MainActivity).
 */
class MainAppsGridActivity : AppCompatActivity() {

    private val items = mutableStateListOf<AppItem>()
    private lateinit var controller: LauncherController

    private var customTabsSession: CustomTabsSession? = null
    private var customTabsBound = false
    private val customTabsConnection = object : CustomTabsServiceConnection() {
        override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
            client.warmup(0)
            customTabsSession = client.newSession(null)?.also {
                it.mayLaunchUrl(Uri.parse(WEB_APP_URLS.getValue(GOOGLE_MESSAGES)), null, null)
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            customTabsSession = null
            customTabsBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        window.statusBarColor = 0xFF000000.toInt()

        controller = LauncherController(
            context = this,
            getSelectedIndex = { 0 },
            getItems = { items },
            onStartActivity = { startActivity(it) },
            onNoAnim = { overridePendingTransition(0, 0) }
        )

        setContent {
            MainAppGridScreen(
                items = items,
                onActivate = { item -> launchGridApp(item) },
                onBack = { finish() },
            )
        }

        bindChromeWarmup()

        Thread {
            val loaded = buildGridAppList()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                items.addAll(loaded)
                if (items.isEmpty()) {
                    Toast.makeText(this, "No apps found.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        MouseAccessibilityService.forceDisable(this)
        overridePendingTransition(0, 0)
        bindChromeWarmup()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (customTabsBound) {
            unbindService(customTabsConnection)
            customTabsBound = false
        }
    }

    /**
     * Build the fixed list of 9 main apps for the grid.
     * Order: smart txt, whatsapp, sms, contacts, call history,
     *        settings, maps lite, camera, uber
     * (Apple Music removed per user request)
     */
    private fun buildGridAppList(): List<AppItem> {
        val result = mutableListOf<AppItem>()

        val platform = PlatformPreferences.getChoice(this)
        val messagingPkg = when (platform) {
            "ios" -> "com.openbubbles.messaging"
            "android" -> GOOGLE_MESSAGES
            else -> null
        }

        val allowedPackages = listOfNotNull(
            messagingPkg,
            "com.whatsapp",
            "com.android.mms",
            "com.android.contacts",
            "com.android.dialer",
            "com.android.settings",
            "com.google.android.apps.mapslite",
            "com.tcl.camera",
            "com.ubercab.uberlite",
        )

        for (pkg in allowedPackages) {
            when (pkg) {
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

        return result
    }

    /**
     * Launch an app from the grid — mirrors the logic in MainActivity.
     */
    private fun launchGridApp(item: AppItem) {
        when (item.packageName) {
            GOOGLE_MESSAGES -> openMessagesInChrome()
            "com.offlineinc.dumbcontactsync" -> {
                val component = item.launchComponent ?: return
                val platform = PlatformPreferences.getChoice(this)
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
                val component = item.launchComponent ?: return
                if (item.packageName == "com.openbubbles.messaging" ||
                    item.packageName == "com.ubercab.uberlite" ||
                    item.packageName == "com.google.android.apps.mapslite") {
                    MouseAccessibilityService.setMouseEnabled(this, true)
                }
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setComponent(component)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                overridePendingTransition(0, 0)
            }
        }
    }

    // ── Chrome Custom Tabs for smart txt ──────────────────────────────────

    private fun hasLaunchedMessagesOnce(): Boolean =
        getSharedPreferences("launcher_prefs", MODE_PRIVATE)
            .getBoolean("chrome_messages_launched", false)

    private fun markMessagesLaunched() =
        getSharedPreferences("launcher_prefs", MODE_PRIVATE)
            .edit().putBoolean("chrome_messages_launched", true).apply()

    private fun openMessagesInChrome() {
        MouseAccessibilityService.setMouseEnabled(this, true)
        if (!hasLaunchedMessagesOnce()) {
            Log.d("MESSAGES", "First launch from grid — opening with URL via Custom Tabs")
            openCustomTab(WEB_APP_URLS.getValue(GOOGLE_MESSAGES))
            markMessagesLaunched()
        } else {
            Log.d("MESSAGES", "Subsequent launch from grid — ACTION_MAIN, no reload")
            Intent(Intent.ACTION_MAIN).apply {
                setPackage("com.android.chrome")
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }.let { startActivity(it) }
        }
        overridePendingTransition(0, 0)
    }

    private fun bindChromeWarmup() {
        if (customTabsBound) return
        if (PlatformPreferences.getChoice(this) != "android") return
        val bound = CustomTabsClient.bindCustomTabsService(this, "com.android.chrome", customTabsConnection)
        if (bound) customTabsBound = true
    }

    private fun openCustomTab(url: String) {
        MouseAccessibilityService.setMouseEnabled(this, true)
        CustomTabsIntent.Builder(customTabsSession)
            .setUrlBarHidingEnabled(true)
            .build()
            .apply { intent.setPackage("com.android.chrome") }
            .launchUrl(this, Uri.parse(url))
        overridePendingTransition(0, 0)
    }

    // ── Key dispatch for dialer ──────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val result = KeyDispatcher.handle(event)
        if (!result.consumed) return super.dispatchKeyEvent(event)

        return when {
            result.resetDialSession -> true
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
