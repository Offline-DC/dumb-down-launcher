package com.offlineinc.dumbdownlauncher.storage

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity

/**
 * Hosts the "Free up space" suggestions UI. Same activity-per-mini-app
 * pattern as `QuackActivity`, `WeatherActivity`, etc. Reached from the
 * AllApps grid via the virtual [com.offlineinc.dumbdownlauncher.FREE_UP_SPACE]
 * package id.
 *
 * The screen is intentionally NOT a comprehensive storage manager — it's
 * a short list of actionable suggestions, one button per category the
 * launcher's manual cleanups can address. The auto-tier (nightly
 * WhatsApp/OpenBubbles/call-log cleanups) is acknowledged only via a
 * single grey footer line so users understand message media is being
 * handled in the background.
 */
class FreeUpSpaceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF000000.toInt()
        setContent {
            FreeUpSpaceScreen(onBack = { finish() })
        }
    }

    override fun onResume() {
        super.onResume()
        overridePendingTransition(0, 0)
    }
}
