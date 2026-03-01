package com.offlineinc.dumbdownlauncher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.mutableStateListOf
import com.offlineinc.dumbdownlauncher.launcher.KeyDispatcher
import com.offlineinc.dumbdownlauncher.launcher.LauncherController
import com.offlineinc.dumbdownlauncher.model.AppItem
import com.offlineinc.dumbdownlauncher.ui.AppListScreen

class AllAppsActivity : AppCompatActivity() {

    companion object {
        @Volatile private var cachedApps: List<AppItem>? = null
        fun invalidateCache() { cachedApps = null }
    }

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

        // Controller still useful for dial shortcuts, etc.
        controller = LauncherController(
            context = this,
            getSelectedIndex = { 0 }, // not used for launching anymore
            getItems = { items },
            onStartActivity = { startActivity(it) },
            onNoAnim = { overridePendingTransition(0, 0) }
        )

        val cached = cachedApps
        if (cached != null) {
            items.addAll(cached)
        }

        setContent {
            AppListScreen(
                title = "all apps",
                items = items,
                onActivate = { item ->
                    launchApp(item)
                },
                onBack = { finish() },
                showSoftKeys = false
            )
        }

        if (cached == null) {
            Thread {
                val loaded = buildAppList()
                cachedApps = loaded
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    items.clear()
                    items.addAll(loaded)
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
                // Fallback (some packages may not resolve via your LaunchResolver)
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

    private fun buildAppList(): List<AppItem> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolved = pm.queryIntentActivities(intent, 0)

        return resolved.mapNotNull { ri ->
            val activityInfo = ri.activityInfo ?: return@mapNotNull null
            val pkg = activityInfo.packageName
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
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val result = KeyDispatcher.handle(event)
        if (!result.consumed) return super.dispatchKeyEvent(event)

        return when {
            result.resetDialSession -> {
                // controller.resetDialSession()
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
