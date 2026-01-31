package com.offlineinc.dumbdownlauncher.launcher

object AppLabelOverrides {

    private val overrides = mapOf(
        "com.android.dialer" to "Call History",
        "com.android.contacts" to "Contacts",
        "com.android.settings" to "Settings",
        "com.android.mms" to "Messages"
    )

    fun getLabel(packageName: String, defaultLabel: String): String {
        return overrides[packageName] ?: defaultLabel
    }
}
