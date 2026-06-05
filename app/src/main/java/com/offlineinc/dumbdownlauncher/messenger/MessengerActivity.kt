package com.offlineinc.dumbdownlauncher.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.offline.dpadmessenger.ui.theme.DpadMessengerTheme
import com.offlineinc.dumbdownlauncher.gmessages.ui.GoogleMessagesApp

/**
 * Hosts the in-app Google Messages messenger.
 *
 * Launched from [com.offlineinc.dumbdownlauncher.MainActivity.launchSmartTxtForPlatform]
 * when the user has picked `"android"` as their platform. Replaces the
 * previous behaviour of opening `https://messages.google.com/web` in Chrome
 * Custom Tabs (which left the user at a desktop-style web app on a 240x320
 * screen — unusable).
 *
 * The Activity itself is a thin shell — all UI lives in
 * `com.offline.dpadmessenger.ui.DpadMessengerApp`, the same Compose entry
 * point that `dpad-messenger-backend/app` uses. That keeps the chat UX
 * identical between the standalone demo and the launcher integration.
 *
 * The Activity defers all logic to [GoogleMessagesApp], which gates on
 * pairing state: if the device isn't yet paired with the user's primary
 * phone it shows the QR pairing screen (mirroring the Signal link flow);
 * once paired it shows the chat UI backed by the Google Messages
 * repository. The swap happens behind the [com.offline.dpadmessenger.data
 * .MessageRepository] interface, so the Activity stays a thin shell.
 */
class MessengerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            DpadMessengerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GoogleMessagesApp()
                }
            }
        }
    }
}
