package com.offlineinc.dumbdownlauncher.launcher

object AppLabelOverrides {

    private val overrides = mapOf(
        "com.android.dialer" to "call history",
        "com.android.contacts" to "contacts",
        "com.android.settings" to "settings",
        "com.android.mms" to "dumb txt",
        "com.openbubbles.messaging" to "smart txt"
    )

    fun getLabel(packageName: String, defaultLabel: String): String {
        return overrides[packageName] ?: defaultLabel
    }
}
