// launcher/LauncherIntents.kt
package com.offlineinc.dumbdownlauncher.launcher

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.offlineinc.dumbdownlauncher.ALL_APPS
import com.offlineinc.dumbdownlauncher.NOTIFICATIONS

object LauncherIntents {

    fun launchOverrideFor(pkg: String): Intent? {
        return when (pkg) {
            NOTIFICATIONS -> Intent(ACTION_OPEN_NOTIFICATIONS_DRAWER)
            ALL_APPS -> Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
            "com.android.settings" -> Intent(Settings.ACTION_SETTINGS)
            else -> null
        }
    }

    // "fake" action string for our controller to intercept
    private const val ACTION_OPEN_NOTIFICATIONS_DRAWER =
        "com.offlineinc.dumbdownlauncher.action.OPEN_NOTIFICATIONS_DRAWER"

    fun dialIntent(digits: String): Intent {
        val encoded = Uri.encode(digits) // important for * and #
        val uri = Uri.parse("tel:$encoded")
        return Intent(Intent.ACTION_DIAL, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun appDetailsIntent(pkg: String): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$pkg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
