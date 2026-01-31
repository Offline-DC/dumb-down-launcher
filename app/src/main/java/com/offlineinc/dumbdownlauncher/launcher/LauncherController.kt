package com.offlineinc.dumbdownlauncher.launcher

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.offlineinc.dumbdownlauncher.NOTIFICATIONS
import com.offlineinc.dumbdownlauncher.model.AppItem
import com.offlineinc.dumbdownlauncher.notifications.NotificationAccess
import com.offlineinc.dumbdownlauncher.notifications.ui.NotificationsActivity
import android.provider.Settings
import com.offlineinc.dumbdownlauncher.ALL_APPS

class LauncherController(
    private val context: Context,
    private val getSelectedIndex: () -> Int,
    private val getItems: () -> List<AppItem>,
    private val onStartActivity: (Intent) -> Unit,
    private val onNoAnim: () -> Unit
) {

    fun launchSelected() {
        val items = getItems()
        if (items.isEmpty()) return
        val item = items[getSelectedIndex().coerceIn(0, items.lastIndex)]

        if (item.packageName == ALL_APPS) {
            safeStart(
                Intent(context, com.offlineinc.dumbdownlauncher.AllAppsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "Couldn't open All Apps"
            )
            return
        }

        if (item.packageName == NOTIFICATIONS) {
            if (!NotificationAccess.isEnabled(context)) {
                Toast.makeText(context, "Enable Notification Access to view notifications.", Toast.LENGTH_LONG).show()
                safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), null)
                return
            }

            safeStart(Intent(context, NotificationsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null)
            return
        }

        // Override intent path first
        val overrideIntent = LauncherIntents.launchOverrideFor(item.packageName)
        if (overrideIntent != null) {
            safeStart(overrideIntent, "Couldn't open ${item.label}")
            return
        }

        // Normal launch
        val component = item.launchComponent
        if (component == null) {
            Toast.makeText(context, "No launcher activity for ${item.label}", Toast.LENGTH_SHORT).show()
            safeStart(LauncherIntents.appDetailsIntent(item.packageName), null)
            return
        }

        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setComponent(component)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        safeStart(intent, "Couldn't launch ${item.label}")
    }

    fun openDialerBlank() {
        openDialerWithDigits("")
    }

    fun openDialerWithDigits(digits: String) {
        safeStart(LauncherIntents.dialIntent(digits), "No dialer available.")
    }

    private fun safeStart(intent: Intent, toastOnFail: String?) {
        try {
            onStartActivity(intent)
            onNoAnim()
        } catch (_: Exception) {
            if (toastOnFail != null) {
                Toast.makeText(context, toastOnFail, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
