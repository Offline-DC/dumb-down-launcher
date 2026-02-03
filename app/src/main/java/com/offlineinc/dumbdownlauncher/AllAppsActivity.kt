package com.offlineinc.dumbdownlauncher

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.offlineinc.dumbdownlauncher.launcher.KeyDispatcher
import com.offlineinc.dumbdownlauncher.launcher.LaunchResolver
import com.offlineinc.dumbdownlauncher.launcher.LauncherController
import com.offlineinc.dumbdownlauncher.model.AppItem
import com.offlineinc.dumbdownlauncher.notifications.ui.NotificationsActivity
import com.offlineinc.dumbdownlauncher.ui.AppListScreen

class AllAppsActivity : AppCompatActivity() {

    private val items = mutableListOf<AppItem>()
    private lateinit var controller: LauncherController

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        window.statusBarColor = 0xFF000000.toInt()

        loadAllApps()

        // Controller is still useful for dialer shortcuts + notification/access logic etc.
        controller = LauncherController(
            context = this,
            getSelectedIndex = { 0 }, // not used for launching anymore
            getItems = { items },
            onStartActivity = { startActivity(it) },
            onNoAnim = { overridePendingTransition(0, 0) }
        )

        setContent {
            AppListScreen(
                title = "All Apps",
                items = items,
                onActivate = { item -> /* unchanged */ },
                onBack = { finish() },
                showSoftKeys = false
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // If you implemented dial-session reset in controller, keep this:
        // controller.resetDialSession()
        overridePendingTransition(0, 0)
    }

    private fun loadAllApps() {
        items.clear()

        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolved = pm.queryIntentActivities(intent, 0)

        val appItems = resolved.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val defaultLabel = pm.getApplicationLabel(appInfo).toString()
                val label = com.offlineinc.dumbdownlauncher.launcher.AppLabelOverrides
                    .getLabel(pkg, defaultLabel)
                val defaultIcon = pm.getApplicationIcon(appInfo)
                val icon = com.offlineinc.dumbdownlauncher.launcher.AppIconOverrides
                    .getIcon(this, pkg, defaultIcon)

                val component = LaunchResolver.resolveLaunchComponent(pm, pkg)
                AppItem(pkg, label, icon, component)
            } catch (_: Exception) {
                null
            }
        }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

        items.addAll(appItems)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val result = KeyDispatcher.handle(event)
        if (!result.consumed) return super.dispatchKeyEvent(event)


        // ✅ Only intercept dial shortcuts here.
        // Let Compose handle Enter/DPAD.
        return when {
            result.resetDialSession -> {        // if you added this to KeyDispatcher
                // controller.resetDialSession()
                true
            }
            result.openDialerBlank -> {
                controller.openDialerBlank()
                true
            }
            result.dialDigits != null -> {
                // If you added openDialerWithDigit use that; otherwise this works:
                controller.openDialerWithDigits(result.dialDigits)
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }
}
