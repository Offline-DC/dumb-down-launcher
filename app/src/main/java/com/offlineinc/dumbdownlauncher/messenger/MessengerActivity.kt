package com.offlineinc.dumbdownlauncher.messenger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.offline.dpadmessenger.backend.gmessages.ui.GoogleMessagesApp
import com.offline.dpadmessenger.ui.theme.DpadMessengerTheme

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
 *
 * Base class is [AppCompatActivity] (not ComponentActivity) on purpose:
 * androidx.activity 1.9+ auto-calls enableEdgeToEdge() inside
 * ComponentActivity.onCreate(), which draws content behind the system bars
 * and leaves a black navigation-bar strip at the bottom on these devices.
 * AppCompatActivity keeps traditional window fitting, so the content fills
 * the screen without that gap. (All the other launcher activities are
 * AppCompatActivity for the same reason.)
 *
 * `android:windowSoftInputMode="adjustResize"` (manifest) resizes the window
 * for the predictive-text IME so the composer sits directly above the
 * candidate strip instead of being covered by it. The nav bar stays the shared
 * theme's black (preferred over a white bar under the strip).
 */
class MessengerActivity : AppCompatActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result ignored */ }

    /** Conversation id from a tapped message notification (null = none).
     *  Second = monotonically bumped key so repeat taps re-navigate. */
    private val deepLinkRoom = androidx.compose.runtime.mutableStateOf<Pair<String, Long>?>(null)

    private fun consumeDeepLink(intent: android.content.Intent?) {
        val convId = intent?.getStringExtra(EXTRA_CONVERSATION_ID) ?: return
        deepLinkRoom.value = convId to System.nanoTime()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeDeepLink(intent)

        // Incoming texts post system notifications; on Android 13+ that needs
        // the runtime POST_NOTIFICATIONS grant. Ask once on open if missing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            // Force light — the launcher globally sets MODE_NIGHT_YES (see
            // DumbDownApp.onCreate), but the messenger should look like a
            // bright, classic texting app.
            DpadMessengerTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val deepLink = deepLinkRoom.value
                    GoogleMessagesApp(
                        initialRoomId = deepLink?.first,
                        initialRoomKey = deepLink?.second,
                        // Sign in to Google by transferring cookies from the
                        // companion smartphone over the Type Sync relay.
                        onCompanionSignIn = {
                            startActivity(
                                android.content.Intent(
                                    this,
                                    GoogleCookieReceiveActivity::class.java,
                                ),
                            )
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Notification tapped while this Activity already exists.
        consumeDeepLink(intent)
    }

    override fun onStop() {
        super.onStop()
        // The session keeps running in the launcher process; tell it the UI
        // is gone so the previously-open thread notifies like any other.
        com.offline.dpadmessenger.backend.gmessages.GoogleMessagesRepository.notifyUiHidden()
    }

    companion object {
        /** Must match GoogleMessagesNotifier.EXTRA_CONVERSATION_ID (the
         *  notifier targets this Activity by name, not by class). */
        const val EXTRA_CONVERSATION_ID = "gmessages.conversation_id"
    }
}
