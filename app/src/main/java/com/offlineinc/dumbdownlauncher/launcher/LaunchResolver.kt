// launcher/LaunchResolver.kt
package com.offlineinc.dumbdownlauncher.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager

object LaunchResolver {
    fun resolveLaunchComponent(pm: PackageManager, pkg: String): ComponentName? {
        val query = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(pkg)

        val resolved = pm.queryIntentActivities(query, 0)
            .firstOrNull()
            ?.activityInfo
            ?: return null

        return ComponentName(resolved.packageName, resolved.name)
    }
}
