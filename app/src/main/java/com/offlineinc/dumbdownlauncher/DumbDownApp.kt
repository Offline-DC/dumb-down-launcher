package com.offlineinc.dumbdownlauncher

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class DumbDownApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }
}
