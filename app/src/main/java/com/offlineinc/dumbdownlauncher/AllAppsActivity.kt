package com.offlineinc.dumbdownlauncher

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.offlineinc.dumbdownlauncher.launcher.KeyDispatcher
import com.offlineinc.dumbdownlauncher.notifications.ui.NotificationsActivity
import com.offlineinc.dumbdownlauncher.update.UpdateCheckWorker
import com.offlineinc.dumbdownlauncher.launcher.LauncherController
import com.offlineinc.dumbdownlauncher.launcher.PlatformPreferences
import com.offlineinc.dumbdownlauncher.model.AppItem
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
        )

        /**
         * Pre-build the app list on a background thread so it's ready before
         * the user opens All Apps. Safe to call multiple times — no-ops when
         * cache is already warm.
         */
        fun warmCacheAsync(context: android.content.Context) {
            if (cachedApps != null) return
            Thread {
                if (cachedApps != null) return@Thread   // double-checked
                cachedApps = buildAppList(context)
            }.start()
        }

        /** Build the full app list. Runs on whatever thread it's called from. */
        fun buildAppList(context: android.content.Context): List<AppItem> {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolved = pm.queryIntentActivities(intent, 0)

            val appItems = resolved.mapNotNull { ri ->
                val activityInfo = ri.activityInfo ?: return@mapNotNull null
                val pkg = activityInfo.packageName
                if (pkg in hiddenPackages) return@mapNotNull null
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

            appItems.add(AppItem(
                packageName = CHANGE_PLATFORM,
                label = "smart os",
                icon = pm.defaultActivityIcon,
                launchComponent = null,
            ))
            appItems.add(AppItem(
                packageName = CHECK_UPDATES,
                label = "updates",
                icon = pm.defaultActivityIcon,
                launchComponent = null,
            ))
            // ContentProvider query — may fail silently if companion app absent.
            val pairing = try {
                com.offlineinc.dumbdownlauncher.typesync.DeviceLinkReader.readPairing(context)
            } catch (_: Exception) { null }

            if (pairing != null) {
                // Only show type sync once paired — it requires a linked device to work
                appItems.add(AppItem(
                    packageName = WEB_KEYBOARD,
                    label = "type sync",
                    icon = pm.defaultActivityIcon,
                    launchComponent = null,
                    isToggleOn = false,
                ))
            } else {
                // Pin "device pairing" at the very top when not yet paired
                appItems.add(0, AppItem(
                    packageName = DEVICE_PAIRING,
                    label = "device sync",
                    icon = pm.defaultActivityIcon,
                    launchComponent = null,
                ))
            }

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

        val cached = cachedApps
        if (cached != null) {
            items.addAll(cached)
            // Restore badge state in case the service was running when this
            // activity instance was created (e.g. rotated or recreated).
            setTypeSyncToggle(WebKeyboardService.isRunning)
        }

        setContent {
            // Seed from the service's live flag so the badge is correct if the
            // activity is recreated while the service is already running.
            var typeSyncEnabled by remember { mutableStateOf(WebKeyboardService.isRunning) }
            var showTypeSyncModal by remember { mutableStateOf(false) }
            val coroutineScope = rememberCoroutineScope()

            // Flip toggle off when the 10-min timer fires from the service
            DisposableEffect(Unit) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        typeSyncEnabled = false
                        setTypeSyncToggle(false)
                    }
                }
                registerReceiver(receiver,
                    IntentFilter(WebKeyboardService.ACTION_STOP_BROADCAST))
                onDispose { unregisterReceiver(receiver) }
            }

            AppListScreen(
                title = "all apps",
                titleEndLabel = "v${BuildConfig.VERSION_NAME}",
                items = items,
                onActivate = { item ->
                    when (item.packageName) {
                    DEVICE_PAIRING -> {
                        // Open the contact sync app for pairing setup
                        val pairingIntent = packageManager.getLaunchIntentForPackage("com.offlineinc.dumbcontactsync")
                        if (pairingIntent != null) {
                            startActivity(pairingIntent)
                            overridePendingTransition(0, 0)
                        } else {
                            Toast.makeText(this, "Install Dumb Down app to pair", Toast.LENGTH_LONG).show()
                        }
                    }
                    CHANGE_PLATFORM -> {
                        PlatformPreferences.requestShowDialog(this)
                        finish()
                    }
                    CHECK_UPDATES -> {
                        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
                        Thread {
                            val found = UpdateCheckWorker.runNow(applicationContext)
                            runOnUiThread {
                                if (found) {
                                    startActivity(
                                        Intent(this, NotificationsActivity::class.java).apply {
                                            putExtra(NotificationsActivity.EXTRA_SCROLL_TO_UPDATE, true)
                                        }
                                    )
                                    overridePendingTransition(0, 0)
                                } else {
                                    Toast.makeText(this, "Already up to date", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }.start()
                    }
                    WEB_KEYBOARD -> {
                        val newEnabled = !typeSyncEnabled
                        if (newEnabled) {
                            // ContentProvider query — must NOT run on the main thread
                            coroutineScope.launch {
                                val pairing = withContext(Dispatchers.IO) {
                                    com.offlineinc.dumbdownlauncher.typesync.DeviceLinkReader.readPairing(this@AllAppsActivity)
                                }
                                if (pairing == null) {
                                    Toast.makeText(
                                        this@AllAppsActivity,
                                        "pair with ur smartphone first using the Dumb Down app",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    typeSyncEnabled = true
                                    setTypeSyncToggle(true)
                                    startService(
                                        Intent(this@AllAppsActivity, WebKeyboardService::class.java).apply {
                                            action = WebKeyboardService.ACTION_START
                                            putExtra(WebKeyboardService.EXTRA_PHONE_NUMBER, pairing.flipPhoneNumber)
                                        }
                                    )
                                    delay(200L)
                                    showTypeSyncModal = true
                                }
                            }
                        } else {
                            typeSyncEnabled = false
                            setTypeSyncToggle(false)
                            startService(
                                Intent(this, WebKeyboardService::class.java).apply {
                                    action = WebKeyboardService.ACTION_STOP
                                }
                            )
                        }
                    }
                    else -> launchApp(item)
                }
                },
                onBack = { finish() },
                showSoftKeys = false,
                wallpaperBitmap = wallpaperState.value,
            )

            if (showTypeSyncModal) {
                AlertDialog(
                    onDismissRequest = { showTypeSyncModal = false },
                    title = { Text("type sync is on") },
                    text = { Text("go to text fields and use ur smartphone to type.\nturns off after 5 min.") },
                    confirmButton = {
                        TextButton(onClick = { showTypeSyncModal = false }) {
                            Text("got it")
                        }
                    }
                )
            }
        }

        if (cached == null) {
            Thread {
                val loaded = buildAppList(applicationContext)
                cachedApps = loaded
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    items.clear()
                    items.addAll(loaded)
                    setTypeSyncToggle(WebKeyboardService.isRunning)
                    if (items.isEmpty()) {
                        Toast.makeText(this, "No apps found.", Toast.LENGTH_LONG).show()
                    }
                    // Fix race condition: refreshDevicePairingRow() in onResume() may
                    // have run before buildAppList() finished (items was empty then, so
                    // it was a no-op). Re-run it now that items are populated so the
                    // "device pairing" row is correctly shown or hidden. Also handles
                    // the case where buildAppList() added the row defensively due to a
                    // transient ContentProvider failure — by now dumb-contacts-sync has
                    // started up and the second query below will succeed.
                    refreshDevicePairingRow()
                }
            }.start()
        }

        // Load wallpaper — reuse the grid's cached bitmap if available.
        val cachedWp = MainAppsGridActivity.cachedWallpaper
        if (cachedWp != null) {
            wallpaperState.value = cachedWp
        } else {
            Thread {
                val bmp = try {
                    val wm = WallpaperManager.getInstance(applicationContext)
                    val drawable = wm.peekDrawable() ?: wm.drawable
                    drawable?.toBitmap()
                } catch (_: Exception) { null }
                MainAppsGridActivity.cachedWallpaper = bmp
                runOnUiThread {
                    if (!isDestroyed) wallpaperState.value = bmp
                }
            }.start()
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
        // Keep the ON/OFF badge in sync whenever the user navigates back to this
        // screen — the service state is the source of truth.
        setTypeSyncToggle(WebKeyboardService.isRunning)
        // Re-check device pairing status — update the pinned "device pairing" row
        // live so it disappears as soon as the user finishes pairing without needing
        // a restart or a full cache invalidation.
        refreshDevicePairingRow()
    }

    /**
     * Show or hide the "device pairing" row based on current pairing status.
     * The ContentProvider query runs on a background thread to avoid blocking
     * the main thread (it can time-out when the companion app isn't installed).
     */
    private fun refreshDevicePairingRow() {
        Thread {
            val isPaired = try {
                com.offlineinc.dumbdownlauncher.typesync.DeviceLinkReader
                    .readPairing(applicationContext) != null
            } catch (_: Exception) { false }

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val existingIdx = items.indexOfFirst { it.packageName == DEVICE_PAIRING }
                if (isPaired && existingIdx >= 0) {
                    items.removeAt(existingIdx)
                    cachedApps = cachedApps?.filter { it.packageName != DEVICE_PAIRING }
                } else if (!isPaired && existingIdx < 0 && items.isNotEmpty()) {
                    val pairingItem = AppItem(
                        packageName = DEVICE_PAIRING,
                        label = "device pairing",
                        icon = packageManager.defaultActivityIcon,
                        launchComponent = null
                    )
                    items.add(0, pairingItem)
                    cachedApps = listOf(pairingItem) + (cachedApps ?: emptyList())
                }
            }
        }.start()
    }

    private fun launchApp(item: AppItem) {
        try {
            val component = item.launchComponent
            val intent = if (component != null) {
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setComponent(component)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (item.packageName == "com.offlineinc.dumbcontactsync") {
                        val platform = PlatformPreferences.getChoice(this@AllAppsActivity)
                        if (platform != null && platform != "skipped") {
                            putExtra("platform", platform)
                        }
                    }
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

    /** Update the WEB_KEYBOARD row's toggle indicator in the live list. */
    private fun setTypeSyncToggle(enabled: Boolean) {
        val idx = items.indexOfFirst { it.packageName == WEB_KEYBOARD }
        if (idx >= 0) items[idx] = items[idx].copy(isToggleOn = enabled)
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
