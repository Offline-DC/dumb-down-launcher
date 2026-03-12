package com.offlineinc.dumbdownlauncher

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.telephony.TelephonyManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.offlineinc.dumbdownlauncher.launcher.KeyDispatcher
import com.offlineinc.dumbdownlauncher.notifications.ui.NotificationsActivity
import com.offlineinc.dumbdownlauncher.update.UpdateCheckWorker
import com.offlineinc.dumbdownlauncher.launcher.LauncherController
import com.offlineinc.dumbdownlauncher.launcher.PlatformPreferences
import com.offlineinc.dumbdownlauncher.model.AppItem
import com.offlineinc.dumbdownlauncher.ui.AppListScreen

class AllAppsActivity : AppCompatActivity() {

    companion object {
        @Volatile private var cachedApps: List<AppItem>? = null
        fun invalidateCache() { cachedApps = null }
    }

    private val hiddenPackages = setOf(
        // smart txt (shown on main screen instead)
        "com.openbubbles.messaging",
        "com.offline.googlemessageslauncher",
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
    )

    private val items = mutableStateListOf<AppItem>()
    private lateinit var controller: LauncherController

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
                            val phone = getDevicePhoneNumber()
                            if (phone == null) {
                                Toast.makeText(
                                    this,
                                    "Couldn't read phone number from SIM",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                typeSyncEnabled = true
                                setTypeSyncToggle(true)
                                startService(
                                    Intent(this, WebKeyboardService::class.java).apply {
                                        action = WebKeyboardService.ACTION_START
                                        putExtra(WebKeyboardService.EXTRA_PHONE_NUMBER, phone)
                                    }
                                )
                                // Delay slightly so the center-key UP event that
                                // triggered this toggle has already been consumed
                                // before the modal appears and steals focus.
                                coroutineScope.launch {
                                    delay(300L)
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
                showSoftKeys = false
            )

            if (showTypeSyncModal) {
                AlertDialog(
                    onDismissRequest = { showTypeSyncModal = false },
                    title = { Text("type sync is on") },
                    text = { Text("go to text fields and use ur smartphone to type.\nturns off after 10 min.") },
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
                val loaded = buildAppList()
                cachedApps = loaded
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    items.clear()
                    items.addAll(loaded)
                    setTypeSyncToggle(WebKeyboardService.isRunning)
                    if (items.isEmpty()) {
                        Toast.makeText(this, "No apps found.", Toast.LENGTH_LONG).show()
                    }
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

    /** Reads the device's own phone number from the SIM. Returns null if unavailable. */
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun getDevicePhoneNumber(): String? {
        return try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val number = tm.line1Number
            if (number.isNullOrBlank()) null else number
        } catch (e: Exception) {
            Log.e("AllAppsActivity", "getDevicePhoneNumber failed: ${e.message}")
            null
        }
    }

    private fun buildAppList(): List<AppItem> {
        val pm = packageManager
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
                    .getIcon(this, pkg, defaultIcon)
                val component = ComponentName(activityInfo.packageName, activityInfo.name)
                AppItem(pkg, label, icon, component)
            } catch (_: Exception) {
                null
            }
        }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toMutableList()

        appItems.add(
            AppItem(
                packageName = CHANGE_PLATFORM,
                label = "smart os",
                icon = pm.defaultActivityIcon,
                launchComponent = null
            )
        )

        appItems.add(
            AppItem(
                packageName = CHECK_UPDATES,
                label = "updates",
                icon = pm.defaultActivityIcon,
                launchComponent = null
            )
        )

        appItems.add(
            AppItem(
                packageName = WEB_KEYBOARD,
                label = "type sync",
                icon = pm.defaultActivityIcon,
                launchComponent = null,
                isToggleOn = false
            )
        )

        return appItems
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
