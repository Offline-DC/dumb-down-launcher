package com.offlineinc.dumbdownlauncher

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.offlineinc.dumbdownlauncher.launcher.KeyDispatcher
import com.offlineinc.dumbdownlauncher.notifications.ui.NotificationsActivity
import com.offlineinc.dumbdownlauncher.update.UpdateCheckWorker
import com.offlineinc.dumbdownlauncher.launcher.LauncherController
import com.offlineinc.dumbdownlauncher.launcher.PlatformPreferences
import com.offlineinc.dumbdownlauncher.model.AppItem
import com.offlineinc.dumbdownlauncher.pairing.PairingStore
import com.offlineinc.dumbdownlauncher.ui.AppListScreen

class AllAppsActivity : AppCompatActivity() {

    companion object {
        @Volatile var cachedApps: List<AppItem>? = null
        fun invalidateCache() { cachedApps = null }

        private val hiddenPackages = setOf(
            // uber (handled by launcher WebViewActivity)
            "com.offline.uberlauncher",
            // launchers
            "com.offlineinc.dumbdownlauncher",
            "com.android.launcher3",
            // Contact sync is now integrated into the launcher — hide standalone APK
            "com.offlineinc.dumbcontactsync",
            // Smart txt apps — handled via the virtual "smart txt" row instead
            "com.openbubbles.messaging",
            "com.offline.googlemessageslauncher",
            // apps to hide
            "com.topjohnwu.magisk",           // Magisk
            "com.android.chrome",             // Chrome
            "com.android.quicksearchbox",     // Search
            "com.iqqijni.dvt912key",          // 12-key keyboard
            "com.polariswireless.zclient",    // ZClient
            "com.mediatek.engineermode",      // MTK engineer mode
            // Trustonic TEE services (not user-facing)
            "com.trustonic.rsu.support",
            "com.trustonic.alpsservice",
            // Carrier device unlock tool
            "com.att.deviceunlock",
            // MTK system tools not meant for end users
            "com.mediatek.lbs.em2.ui",        // MTK LBS engineering
            "com.mediatek.duraspeed",         // MTK DuraSpeed
            "com.mediatek.callrecorder",      // MTK call recorder
            "com.mediatek.calendarimporter",  // MTK calendar importer
            // SIM tool kit
            "com.android.stk",
            // Snake — now built into the launcher as a virtual app
            "com.snake",
        )

        private val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

        /**
         * Pre-build the app list on a background thread so it's ready before
         * the user opens All Apps. Safe to call multiple times — no-ops when
         * cache is already warm.
         */
        fun warmCacheAsync(context: android.content.Context) {
            if (cachedApps != null) return
            bgExecutor.execute {
                if (cachedApps != null) return@execute   // double-checked
                cachedApps = buildAppList(context)
            }
        }

        /** Build the full app list. Runs on whatever thread it's called from. */
        fun buildAppList(context: android.content.Context): List<AppItem> {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolved = pm.queryIntentActivities(intent, 0)

            val pairingStore = com.offlineinc.dumbdownlauncher.pairing.PairingStore(context)
            val audioPackagesToHide = if (pairingStore.hideAudioBundle)
                MouseAccessibilityService.AUDIO_APP_PACKAGES
            else emptySet()

            val appItems = resolved.mapNotNull { ri ->
                val activityInfo = ri.activityInfo ?: return@mapNotNull null
                val pkg = activityInfo.packageName
                if (pkg in hiddenPackages) return@mapNotNull null
                if (pkg in audioPackagesToHide) return@mapNotNull null
                val appInfo = activityInfo.applicationInfo
                try {
                    val defaultLabel = pm.getApplicationLabel(appInfo).toString()
                    val label = com.offlineinc.dumbdownlauncher.launcher.AppLabelOverrides
                        .getLabel(pkg, defaultLabel)
                        .lowercase()
                    val defaultIcon = pm.getApplicationIcon(appInfo)
                    val icon = com.offlineinc.dumbdownlauncher.launcher.AppIconOverrides
                        .getIcon(context, pkg, defaultIcon)
                    val component = ComponentName(activityInfo.packageName, activityInfo.name)
                    AppItem(pkg, label, icon, component)
                } catch (_: Exception) {
                    null
                }
            }
                .distinctBy { it.packageName }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                .toMutableList()

            // Show contact sync (built-in) when paired, unless smart txt is hidden
            // (hideSmartTxt subscribers don't have access to contact sync)
            if (pairingStore.isPaired && !pairingStore.hideSmartTxt) {
                appItems.add(AppItem(
                    packageName = CONTACT_SYNC,
                    label = "contact sync",
                    icon = pm.defaultActivityIcon,
                    launchComponent = null,
                ))
            }

            // Show quack (built-in) when we can read the device's own phone number.
            // Uses the waiting variant so a cold boot (SIM still loading) doesn't
            // hide quack forever — runs on the background bgExecutor thread.
            if (com.offlineinc.dumbdownlauncher.launcher.PhoneNumberReader.isAvailableWithWait(context)) {
                appItems.add(AppItem(
                    packageName = QUACK,
                    label = "quack",
                    icon = pm.defaultActivityIcon,
                    launchComponent = null,
                ))
            }

            // Snake — always available as a built-in game
            appItems.add(AppItem(
                packageName = SNAKE,
                label = "snake",
                icon = pm.defaultActivityIcon,
                launchComponent = null,
            ))

            // Weather — always available as a built-in app
            appItems.add(AppItem(
                packageName = WEATHER,
                label = "weather",
                icon = pm.defaultActivityIcon,
                launchComponent = null,
            ))

            // Show smart txt when platform is chosen (iOS → OpenBubbles, Android → Google Messages)
            val platform = PlatformPreferences.getChoice(context)
            val smartTxtPkg = when (platform) {
                "ios"     -> "com.openbubbles.messaging"
                "android" -> GOOGLE_MESSAGES
                else      -> null
            }
            if (smartTxtPkg != null) {
                appItems.add(AppItem(
                    packageName = smartTxtPkg,
                    label = "smart txt",
                    icon = pm.defaultActivityIcon,
                    launchComponent = null,
                ))
            }

            if (!pairingStore.hideSmartTxt) {
                appItems.add(AppItem(
                    packageName = DEVICE_SETUP,
                    label = "device setup",
                    icon = pm.defaultActivityIcon,
                    launchComponent = null,
                ))
            }
            appItems.add(AppItem(
                packageName = CHECK_UPDATES,
                label = "updates",
                icon = pm.defaultActivityIcon,
                launchComponent = null,
            ))

            // Re-sort so virtual items (contact sync, device setup, updates, etc.)
            // appear in alphabetical order alongside real apps.
            appItems.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

            return appItems
        }
    }

    private val items = mutableStateListOf<AppItem>()
    private lateinit var controller: LauncherController
    private val wallpaperState = mutableStateOf<Bitmap?>(null)

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            invalidateCache()
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

        val cached = cachedApps
        if (cached != null) {
            // Defensive dedupe — LazyColumn crashes if two items share the same key
            items.addAll(cached.distinctBy { it.packageName })
        }

        setContent {
            // showUnpairDialog removed — unpairing handled in PairingScreen

            AppListScreen(
                title = "all apps",
                titleEndLabel = "v${BuildConfig.VERSION_NAME}",
                items = items,
                onLongActivate = { item ->
                    if (item.packageName == DEVICE_SETUP) {
                        // Factory-reset-style wipe. PairingStore.clear() only
                        // removes pairing credentials now (so a normal unpair
                        // doesn't force re-registration), so we explicitly
                        // clear the phone number and deviceRegistered flag
                        // here to send the user through the full onboarding
                        // (including registration) again.
                        PlatformPreferences.clearAll(this@AllAppsActivity)
                        val pairingStore = PairingStore(this@AllAppsActivity)
                        pairingStore.clear()
                        pairingStore.flipPhoneNumber = null
                        pairingStore.deviceRegistered = false
                        // Clear device registration prefs so the next boot re-registers
                        this@AllAppsActivity.getSharedPreferences("device_registration", MODE_PRIVATE)
                            .edit().clear().apply()
                        // Drop the cached phone number so it's re-read from SIM
                        com.offlineinc.dumbdownlauncher.launcher.PhoneNumberReader.invalidateCache()
                        Toast.makeText(
                            this@AllAppsActivity,
                            "Setup cleared — restart to go through setup again",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                onActivate = { item ->
                    when (item.packageName) {
                    DEVICE_SETUP -> {
                        // Set the flag then bring MainActivity to the front, clearing
                        // the back stack (AllApps + Grid) so onResume fires immediately.
                        PlatformPreferences.requestShowDialog(this@AllAppsActivity)
                        startActivity(
                            Intent(this@AllAppsActivity, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                        )
                        overridePendingTransition(0, 0)
                    }
                    CHECK_UPDATES -> {
                        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
                        lifecycleScope.launch(Dispatchers.IO) {
                            val found = UpdateCheckWorker.runNow(applicationContext)
                            withContext(Dispatchers.Main) {
                                if (found) {
                                    startActivity(
                                        Intent(this@AllAppsActivity, NotificationsActivity::class.java).apply {
                                            putExtra(NotificationsActivity.EXTRA_SCROLL_TO_UPDATE, true)
                                        }
                                    )
                                    overridePendingTransition(0, 0)
                                } else {
                                    Toast.makeText(this@AllAppsActivity, "Already up to date", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    GOOGLE_MESSAGES -> {
                        MainAppsGridActivity.openMessagesInChrome(this@AllAppsActivity)
                    }
                    "com.openbubbles.messaging" -> {
                        if (MouseAccessibilityService.isOpenBubblesMouseNeeded(this@AllAppsActivity)) {
                            MouseAccessibilityService.setMouseEnabled(this@AllAppsActivity, true)
                        }
                        val launchIntent = packageManager.getLaunchIntentForPackage("com.openbubbles.messaging")
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent)
                            overridePendingTransition(0, 0)
                        } else {
                            Toast.makeText(this@AllAppsActivity, "OpenBubbles not installed", Toast.LENGTH_SHORT).show()
                        }
                    }
                    QUACK -> {
                        startActivity(
                            Intent(this@AllAppsActivity, com.offlineinc.dumbdownlauncher.quack.QuackActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        )
                        overridePendingTransition(0, 0)
                    }
                    SNAKE -> {
                        startActivity(
                            Intent(this@AllAppsActivity, com.offlineinc.dumbdownlauncher.snake.SnakeActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        )
                        overridePendingTransition(0, 0)
                    }
                    WEATHER -> {
                        startActivity(
                            Intent(this@AllAppsActivity, com.offlineinc.dumbdownlauncher.weather.WeatherActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        )
                        overridePendingTransition(0, 0)
                    }
                    CONTACT_SYNC -> {
                        startActivity(
                            Intent(this@AllAppsActivity, com.offlineinc.dumbdownlauncher.contactsync.ContactSyncActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        )
                        overridePendingTransition(0, 0)
                    }
                    else -> {
                        if (item.packageName in MouseAccessibilityService.AUDIO_APP_PACKAGES) {
                            MouseAccessibilityService.setMouseEnabled(this@AllAppsActivity, true)
                        }
                        launchApp(item)
                    }
                }
                },
                onBack = { finish() },
                showSoftKeys = false,
                wallpaperBitmap = wallpaperState.value,
            )

            // Unpair dialog removed — unpairing is now handled in
            // the PairingScreen's "device linked" state.
        }

        if (cached == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val loaded = buildAppList(applicationContext).distinctBy { it.packageName }
                cachedApps = loaded
                withContext(Dispatchers.Main) {
                    if (isDestroyed) return@withContext
                    items.clear()
                    items.addAll(loaded)
                    if (items.isEmpty()) {
                        Toast.makeText(this@AllAppsActivity, "No apps found.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Load wallpaper — reuse the grid's cached bitmap if available.
        val cachedWp = MainAppsGridActivity.cachedWallpaper
        if (cachedWp != null) {
            wallpaperState.value = cachedWp
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                val bmp = try {
                    val wm = WallpaperManager.getInstance(applicationContext)
                    val drawable = wm.peekDrawable() ?: wm.drawable
                    drawable?.toBitmap()
                } catch (_: Exception) { null }
                MainAppsGridActivity.cachedWallpaper = bmp
                withContext(Dispatchers.Main) {
                    if (!isDestroyed) wallpaperState.value = bmp
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(packageChangeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(packageChangeReceiver)
    }

    override fun onResume() {
        super.onResume()
        overridePendingTransition(0, 0)
        // Re-check device pairing status — update the pinned "device pairing" row
        // live so it disappears as soon as the user finishes pairing without needing
        // a restart or a full cache invalidation.
        refreshDevicePairingRow()
    }

    /**
     * Refresh pairing-dependent rows (contact sync) on resume.
     * Quack is shown independently whenever the device phone number is available.
     * "device setup" is always visible — it doubles as the unpair entry point.
     * Type sync is no longer shown here — it's toggled via star-key long-press.
     */
    private fun refreshDevicePairingRow() {
        lifecycleScope.launch(Dispatchers.IO) {
            val isPaired = try {
                com.offlineinc.dumbdownlauncher.typesync.DeviceLinkReader
                    .readPairing(applicationContext) != null
            } catch (_: Exception) { false }

            // Wait for the SIM if it isn't ready yet — onResume can fire before
            // the modem has finished loading on cold boot. Runs on Dispatchers.IO.
            val hasPhoneNumber = com.offlineinc.dumbdownlauncher.launcher.PhoneNumberReader
                .isAvailableWithWait(applicationContext)

            withContext(Dispatchers.Main) {
                if (isDestroyed) return@withContext

                if (isPaired) {
                    // Add "contact sync" if it isn't already in the list
                    val hasContactSync = items.any { it.packageName == CONTACT_SYNC }
                    if (!hasContactSync) {
                        val contactSyncItem = AppItem(
                            packageName = CONTACT_SYNC,
                            label = "contact sync",
                            icon = packageManager.defaultActivityIcon,
                            launchComponent = null,
                        )
                        items.add(contactSyncItem)
                        val cache = cachedApps ?: emptyList()
                        if (cache.none { it.packageName == CONTACT_SYNC }) {
                            cachedApps = cache + contactSyncItem
                        }
                    }
                } else {
                    // Remove "contact sync" if present when not paired
                    val contactSyncIdx = items.indexOfFirst { it.packageName == CONTACT_SYNC }
                    if (contactSyncIdx >= 0) {
                        items.removeAt(contactSyncIdx)
                        cachedApps = cachedApps?.filter { it.packageName != CONTACT_SYNC }
                    }
                }

                // Quack visibility is independent of pairing — show whenever we can
                // read the device's own phone number (SIM is present and readable).
                val hasQuack = items.any { it.packageName == QUACK }
                if (hasPhoneNumber && !hasQuack) {
                    val quackItem = AppItem(
                        packageName = QUACK,
                        label = "quack",
                        icon = packageManager.defaultActivityIcon,
                        launchComponent = null,
                    )
                    items.add(quackItem)
                    val cache = cachedApps ?: emptyList()
                    if (cache.none { it.packageName == QUACK }) {
                        cachedApps = cache + quackItem
                    }
                } else if (!hasPhoneNumber && hasQuack) {
                    val quackIdx = items.indexOfFirst { it.packageName == QUACK }
                    if (quackIdx >= 0) {
                        items.removeAt(quackIdx)
                        cachedApps = cachedApps?.filter { it.packageName != QUACK }
                    }
                }

                // Re-sort so dynamically added items appear in alphabetical order.
                // distinctBy is defensive: if a stale cache somehow duplicates a
                // package (e.g. QUACK added by both buildAppList + refresh), the
                // LazyColumn key contract would otherwise crash the app.
                val sorted = items
                    .distinctBy { it.packageName }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                items.clear()
                items.addAll(sorted)
                // Also dedupe the static cache so future onCreate paths are clean.
                cachedApps = cachedApps?.distinctBy { it.packageName }
            }
        }
    }

    private fun launchApp(item: AppItem) {
        try {
            val component = item.launchComponent
            val intent = if (component != null) {
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setComponent(component)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                packageManager.getLaunchIntentForPackage(item.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            if (intent == null) {
                Toast.makeText(this, "Can't launch ${item.label}", Toast.LENGTH_SHORT).show()
                return
            }

            startActivity(intent)
            overridePendingTransition(0, 0)
        } catch (e: Exception) {
            Toast.makeText(this, "Launch failed: ${item.label}", Toast.LENGTH_SHORT).show()
        }
    }

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
