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
import com.offline.dpadmessenger.backend.signal.SignalRepository
import com.offline.dpadmessenger.backend.signal.ui.SignalApp
import com.offline.dpadmessenger.ui.theme.DpadMessengerTheme

/**
 * Hosts the in-app Signal messenger ("Dumb Signal").
 *
 * The Signal twin of [MessengerActivity]. Launched from "signal (beta)" in
 * All Apps (see [com.offlineinc.dumbdownlauncher.AllAppsActivity]). Like its
 * Google Messages sibling it's a thin shell — all UI lives in
 * [SignalApp], the same Compose entry point that the backend's standalone
 * `:app` demo uses, so the chat UX is identical between the demo and the
 * launcher integration.
 *
 * [SignalApp] gates on link state: when no Signal account is linked it drives
 * the device-link provisioning client and shows the QR screen; the user scans
 * that QR from their primary Signal app (Settings → Linked Devices → Link New
 * Device — Signal's standard secondary-device flow). Once the primary confirms
 * the link, it flips to the chat UI backed by the real Signal repository. The
 * swap happens behind the `MessageRepository` interface, so this Activity stays
 * a thin shell. Unlike the Google Messages flow there's no `companionSignIn`
 * slot — Signal's QR link is fully self-contained.
 *
 * Base class is [AppCompatActivity] (not ComponentActivity) on purpose:
 * androidx.activity 1.9+ auto-calls enableEdgeToEdge() inside
 * ComponentActivity.onCreate(), which draws content behind the system bars and
 * leaves a black navigation-bar strip at the bottom on these devices.
 * AppCompatActivity keeps traditional window fitting, matching every other
 * launcher activity (and [MessengerActivity]).
 *
 * `android:windowSoftInputMode="adjustResize"` (manifest) resizes the window
 * for the predictive-text IME so the composer sits directly above the candidate
 * strip instead of being covered by it.
 */
class SignalMessengerActivity : AppCompatActivity() {

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

        // Incoming Signal messages post system notifications; on Android 13+
        // that needs the runtime POST_NOTIFICATIONS grant. Ask once on open if
        // missing (mirrors MessengerActivity).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            // Force light — the launcher globally sets MODE_NIGHT_YES (see
            // DumbDownApp.onCreate), but the messenger should look like a
            // bright, classic texting app (matches MessengerActivity).
            DpadMessengerTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val deepLink = deepLinkRoom.value
                    SignalApp(
                        initialRoomId = deepLink?.first,
                        initialRoomKey = deepLink?.second,
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

    companion object {
        /** Conversation-id extra for notification deep-links into a thread.
         *  Mirrors [MessengerActivity.EXTRA_CONVERSATION_ID]; a future Signal
         *  notifier targets this Activity by name (see SignalConfig). */
        const val EXTRA_CONVERSATION_ID = "signal.conversation_id"
    }
}
