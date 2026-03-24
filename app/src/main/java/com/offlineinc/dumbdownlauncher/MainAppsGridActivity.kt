package com.offlineinc.dumbdownlauncher

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    companion object {
        /**
         * Process-level cache for the 9 fixed grid apps.
         * Built once and reused on every subsequent launch for instant display.
         * Cleared when the platform preference changes (messaging app swap).
         */
        @Volatile var cachedGridItems: List<AppItem>? = null

        /**
         * Process-level wallpaper bitmap cache shared with the home screen.
         * Avoids re-reading WallpaperManager on every grid open.
         */
        @Volatile var cachedWallpaper: Bitmap? = null

        fun invalidateItemCache() { cachedGridItems = null }
        fun invalidateWallpaperCache() { cachedWallpaper = null }

        private val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

        /**
         * Pre-build the 9-item grid list on a background thread so it's ready
         * before the user opens the grid. Safe to call multiple times — no-ops
         * when cache is already warm.
         */
        fun warmCacheAsync(context: android.content.Context) {
            if (cachedGridItems != null) return
            bgExecutor.execute {
                if (cachedGridItems != null) return@execute  // double-checked
                cachedGridItems = buildGridAppListStatic(context)
                // Persist the platform key so onResume can detect staleness.
                val platform = PlatformPreferences.getChoice(context)
                context.getSharedPreferences("launcher_prefs", MODE_PRIVATE)
                    .edit().putString("grid_cache_platform", platform).apply()
            }
        }

        /**
         * Pre-load the wallpaper bitmap on a background thread. Shared between
         * the grid and all-apps screens.
         */
        fun warmWallpaperAsync(context: android.content.Context) {
            if (cachedWallpaper != null) return
            bgExecutor.execute {
                if (cachedWallpaper != null) return@execute
                try {
                    val wm = WallpaperManager.getInstance(context)
                    val drawable = wm.peekDrawable() ?: wm.drawable
                    cachedWallpaper = drawable?.toBitmap()
                } catch (_: Exception) {}
            }
        }

        /**
         * Static version of buildGridAppList that takes a Context so it can
         * be called from the companion without an Activity instance.
         */
        private fun buildGridAppListStatic(context: android.content.Context): List<AppItem> {
            val pm = context.packageManager
            val result = mutableListOf<AppItem>()

            val platform = PlatformPreferences.getChoice(context)
            val messagingPkg = when (platform) {
                "ios"     -> "com.openbubbles.messaging"
                "android" -> GOOGLE_MESSAGES
                else      -> null
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
                        result.add(AppItem(GOOGLE_MESSAGES, "smart txt", pm.defaultActivityIcon, null, false))
                        continue
                    }
                    "com.openbubbles.messaging" -> {
                        try {
                            val appInfo = pm.getApplicationInfo(pkg, 0)
                            val defaultLabel = pm.getApplicationLabel(appInfo).toString()
                            val label = com.offlineinc.dumbdownlauncher.launcher.AppLabelOverrides.getLabel(pkg, defaultLabel).lowercase()
                            val defaultIcon = pm.getApplicationIcon(appInfo)
                            val icon = com.offlineinc.dumbdownlauncher.launcher.AppIconOverrides.getIcon(context, pkg, defaultIcon)
                            val launchComponent = com.offlineinc.dumbdownlauncher.launcher.LaunchResolver.resolveLaunchComponent(pm, pkg)
                            result.add(AppItem(pkg, label, icon, launchComponent, isMuted = false))
                        } catch (_: Exception) {
                            result.add(AppItem(GOOGLE_MESSAGES, "smart txt", pm.defaultActivityIcon, null, false))
                        }
                        continue
                    }
                }

                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val defaultLabel = pm.getApplicationLabel(appInfo).toString()
                    val label = com.offlineinc.dumbdownlauncher.launcher.AppLabelOverrides.getLabel(pkg, defaultLabel).lowercase()
                    val defaultIcon = pm.getApplicationIcon(appInfo)
                    val icon = com.offlineinc.dumbdownlauncher.launcher.AppIconOverrides.getIcon(context, pkg, defaultIcon)
                    val launchComponent = com.offlineinc.dumbdownlauncher.launcher.LaunchResolver.resolveLaunchComponent(pm, pkg)
                    result.add(AppItem(pkg, label, icon, launchComponent, isMuted = false))
                } catch (_: Exception) { }
            }

            return result
        }
    }

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
        super.onCreate(savedInstanceState)

        window.statusBarColor = 0xFF000000.toInt()

        controller = LauncherController(
            context = this,
            getSelectedIndex = { 0 },
            getItems = { items },
            onStartActivity = { startActivity(it) },
            onNoAnim = { overridePendingTransition(0, 0) }
        )

        // Use cached wallpaper immediately if available, then refresh in background.
        val wallpaperState = mutableStateOf(cachedWallpaper)

        setContent {
            MainAppGridScreen(
                items = items,
                wallpaperBitmap = wallpaperState.value,
                onActivate = { item -> launchGridApp(item) },
                onBack = { finish() },
            )
        }

        bindChromeWarmup()

        // Load grid items from cache (instant) or build them on a background thread.
        val cachedItems = cachedGridItems
        if (cachedItems != null) {
            items.addAll(cachedItems)
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                val loaded = buildGridAppList()
                cachedGridItems = loaded
                // Persist the platform key used when this cache was built so
                // onResume can detect staleness without a full rebuild.
                val platform = PlatformPreferences.getChoice(this@MainAppsGridActivity)
                getSharedPreferences("launcher_prefs", MODE_PRIVATE)
                    .edit().putString("grid_cache_platform", platform).apply()
                withContext(Dispatchers.Main) {
                    if (isDestroyed) return@withContext
                    items.clear()
                    items.addAll(loaded)
                    if (items.isEmpty()) {
                        Toast.makeText(this@MainAppsGridActivity, "No apps found.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Load wallpaper on a background thread — update state when ready.
        if (cachedWallpaper == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val bmp = try {
                    val wm = WallpaperManager.getInstance(applicationContext)
                    val drawable = wm.peekDrawable() ?: wm.drawable
                    drawable?.toBitmap()
                } catch (_: Exception) { null }
                cachedWallpaper = bmp
                withContext(Dispatchers.Main) {
                    if (!isDestroyed) wallpaperState.value = bmp
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        MouseAccessibilityService.forceDisable(this)
        overridePendingTransition(0, 0)
        bindChromeWarmup()
        // If the platform choice changed while we were away, the cached list has
        // the wrong messaging app — rebuild it on the next open.
        val currentPlatform = PlatformPreferences.getChoice(this)
        val cachedPlatformKey = getSharedPreferences("launcher_prefs", MODE_PRIVATE)
            .getString("grid_cache_platform", null)
        if (currentPlatform != cachedPlatformKey) {
            invalidateItemCache()
        }
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
        // Only include smart txt when the user has actually chosen a platform.
        // null / "skipped" means the picker was dismissed — omit the slot entirely
        // rather than guessing.
        val messagingPkg = when (platform) {
            "ios"     -> "com.openbubbles.messaging"
            "android" -> GOOGLE_MESSAGES
            else      -> null   // not chosen yet — leave slot empty
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
                "com.openbubbles.messaging" -> {
                    // Use the installed OpenBubbles app; if it isn't installed yet
                    // (e.g. mid-setup or after a reinstall) fall back to the web app
                    // so the smart txt slot is never empty.
                    try {
                        val appInfo = packageManager.getApplicationInfo(pkg, 0)
                        val defaultLabel = packageManager.getApplicationLabel(appInfo).toString()
                        val label = AppLabelOverrides.getLabel(pkg, defaultLabel).lowercase()
                        val defaultIcon = packageManager.getApplicationIcon(appInfo)
                        val icon = AppIconOverrides.getIcon(this, pkg, defaultIcon)
                        val launchComponent = LaunchResolver.resolveLaunchComponent(packageManager, pkg)
                        result.add(AppItem(pkg, label, icon, launchComponent, isMuted = false))
                    } catch (_: Exception) {
                        // OpenBubbles not installed — fall back to web Google Messages
                        result.add(AppItem(GOOGLE_MESSAGES, "smart txt", packageManager.defaultActivityIcon, null, false))
                    }
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
            CONTACT_SYNC -> {
                val intent = Intent(this, com.offlineinc.dumbdownlauncher.contactsync.ContactSyncActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    private fun chromeSessionIsWarm(): Boolean {
        val lastMs = getSharedPreferences("launcher_prefs", MODE_PRIVATE)
            .getLong("messages_last_opened_ms", 0L)
        return System.currentTimeMillis() - lastMs < 6 * 60 * 60 * 1000L
    }

    private fun markMessagesOpened() =
        getSharedPreferences("launcher_prefs", MODE_PRIVATE)
            .edit().putLong("messages_last_opened_ms", System.currentTimeMillis()).apply()

    private fun openMessagesInChrome() {
        MouseAccessibilityService.setMouseEnabled(this, true)
        if (chromeSessionIsWarm()) {
            Log.d("MESSAGES", "Warm session — bringing Chrome to front (no reload)")
            Intent(Intent.ACTION_MAIN).apply {
                setPackage("com.android.chrome")
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }.let { startActivity(it) }
        } else {
            Log.d("MESSAGES", "Cold session — opening messages URL via Custom Tabs")
            openCustomTab(WEB_APP_URLS.getValue(GOOGLE_MESSAGES))
        }
        markMessagesOpened()
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
