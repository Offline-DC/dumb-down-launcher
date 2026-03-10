package com.offlineinc.dumbdownlauncher

import android.app.Application
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import com.offlineinc.dumbdownlauncher.coverdisplay.CoverDisplayService
import com.offlineinc.dumbdownlauncher.update.UpdateCheckWorker

class DumbDownApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        UpdateCheckWorker.schedule(this)
        // Start the cover display service. As the HOME launcher we are always alive,
        // so no foreground notification is required. The service is START_STICKY and
        // re-attaches automatically when the cover display is connected.
        startService(Intent(this, CoverDisplayService::class.java))
    }
}
