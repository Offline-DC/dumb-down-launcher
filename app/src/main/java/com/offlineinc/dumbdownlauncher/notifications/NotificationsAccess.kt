package com.offlineinc.dumbdownlauncher.notifications

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils

object NotificationAccess {

    fun isEnabled(context: Context): Boolean {
        val cn = ComponentName(context, DumbNotificationListenerService::class.java)
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false

        return flat.split(":").any { ComponentName.unflattenFromString(it) == cn }
    }
}
