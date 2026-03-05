package com.offlineinc.dumbdownlauncher

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.offlineinc.dumbdownlauncher.update.UpdateCheckWorker

class DumbDownApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        UpdateCheckWorker.schedule(this)
    }
}
