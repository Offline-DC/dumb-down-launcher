package com.offlineinc.dumbdownlauncher.messenger

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.focus.dpadFocusHighlight
import com.offlineinc.dumbdownlauncher.MouseAccessibilityService
import com.offlineinc.dumbdownlauncher.pairing.PairingStore
import com.offline.dpadmessenger.focus.onDpadAction
import com.offline.dpadmessenger.ui.theme.DpadMessengerTheme

/**
 * Hosts the "Sign in with your Google account via your phone" flow. The flip
 * phone listens on its single live Type Sync relay (owned by
 * [MouseAccessibilityService]); the companion smartphone signs into Google and
 * sends the cookies over, which the relay receives and hands back here via
 * [GmessagesCookieCallbacks].
 *
 * NOTE: the launcher now renders [GoogleCookieSignInScreen] INLINE inside
 * MessengerActivity (via GoogleMessagesApp's companionSignIn slot), so there is
 * a single "waiting for your phone" screen rather than this Activity stacked on
 * top of a duplicate prompt. This Activity is kept as a standalone entry point
 * (manifest-declared) but is no longer launched in the normal flow.
 */
class GoogleCookieReceiveActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DpadMessengerTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GoogleCookieSignInScreen(onPaired = { finish() }, onExit = { finish() })
                }
            }
        }
    }
}

/**
 * The single Google Messages sign-in / pairing screen: waits for the companion
 * to send the login over the relay, shows the UKey2 emoji to match, then the
 * result. Calls [onPaired] when pairing completes (host flips to the chat UI)
 * and [onExit] when the user backs out.
 */
@Composable
fun GoogleCookieSignInScreen(
    onPaired: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // null = still waiting; otherwise (success, message).
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    // The UKey2 verification emoji (shown while pairing, before the result).
    var emoji by remember { mutableStateOf<String?>(null) }
    // True once the login has arrived and Google sign-in + pairing is running.
    var signingIn by remember { mutableStateOf(false) }
    // Bump to restart the relay client (Retry): re-connects and waits for a
    // fresh login from the companion. Re-checks the device link too.
    var attempt by remember { mutableStateOf(0) }

    // The cookie transfer reuses the Type Sync / Device Link pairing. If the
    // dumb phone isn't linked to a smart phone yet, there's no encrypted channel
    // to receive the login on — so guard up front with a friendly setup prompt
    // instead of spinning forever on "waiting for ur smart phone".
    val linked = remember(attempt) {
        val ps = PairingStore(context)
        !ps.sharedSecret.isNullOrEmpty() && !ps.flipPhoneNumber.isNullOrEmpty()
    }

    DisposableEffect(attempt) {
        if (!linked) {
            result = false to (
                "type sync isn’t set up yet. on ur dumb phone, go to Device Setup and " +
                    "link ur phones first, then come back and try again."
                )
            onDispose { }
        } else {
            result = null
            emoji = null
            signingIn = false
            // Receive the login over the single, live Type Sync relay owned by
            // MouseAccessibilityService (the same socket text sync uses — no
            // competing connection). Register our UI callbacks, then make sure
            // the relay is up so it owns the phone slot and can receive.
            MouseAccessibilityService.setGmessagesCookieCallbacks(
                GmessagesCookieCallbacks(
                    onCookies = { signingIn = true },
                    onEmoji = { emoji = it },
                    onResult = { ok, msg -> result = ok to (msg ?: "") },
                )
            )
            MouseAccessibilityService.startRelay(context, null)
            onDispose { MouseAccessibilityService.setGmessagesCookieCallbacks(null) }
        }
    }

    // Keep the relay alive and listening for the WHOLE time we're waiting — the
    // companion may take a while to sign in and resends the cookies until acked,
    // so the flip must keep owning the `phone` slot. startRelay() is idempotent
    // (no-ops if already connected with the same creds; reconnects if it dropped).
    // Also logs a heartbeat so a missed token is diagnosable (tag GMCookieWait):
    // if the companion sends while connected=false here, that's the lost-token bug.
    LaunchedEffect(attempt, linked) {
        if (!linked) return@LaunchedEffect
        var waitedSec = 0
        while (result == null) {
            val connected = MouseAccessibilityService.isRelayConnected()
            Log.i(
                "GMCookieWait",
                "waiting for smart phone… ${waitedSec}s elapsed; relayConnected=$connected " +
                    "signingIn=$signingIn emoji=${emoji != null}",
            )
            if (!connected) {
                Log.w("GMCookieWait", "relay NOT connected while waiting — re-asserting startRelay()")
                MouseAccessibilityService.startRelay(context, null)
            }
            kotlinx.coroutines.delay(5_000)
            waitedSec += 5
        }
        Log.i("GMCookieWait", "wait ended after ${waitedSec}s: success=${result?.first}")
    }
    BackHandler(onBack = onExit)

    fun retry() {
        result = null
        emoji = null
        signingIn = false
        attempt++ // restarts the DisposableEffect → new relay client
    }

    val buttonFocus = remember { FocusRequester() }
    // Focus the button only once it appears (on a result). While waiting there
    // is no button — just the spinner; hardware Back exits.
    LaunchedEffect(result) { if (result != null) runCatching { buttonFocus.requestFocus() } }

    Box(
        modifier = modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val r = result
            val e = emoji
            if (r == null && e != null) {
                // Pairing in progress: show the verification emoji to match.
                Text(
                    text = "Open Google Messages on your phone and tap this emoji to pair:",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = e,
                    style = MaterialTheme.typography.displayLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                Text(
                    text = "Keep this screen open.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else if (r == null && signingIn) {
                // Login received — signing into Google + pairing (emoji next).
                CircularProgressIndicator()
                Text(
                    text = "signing in to google…",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    text = "hang tight — an emoji to confirm will pop up here in a moment.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else if (r == null) {
                CircularProgressIndicator()
                Text(
                    text = "waiting for ur smart phone…",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    text = "on a computer, go to dumb.co/signin and sign in. then on " +
                        "ur smart phone, tap “scan desktop code” and scan the QR. " +
                        "keep this screen open.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Text(
                    text = if (r.first) "✓ Connected" else "Couldn't sign in",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (r.first) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = r.second,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // No "Cancel" while waiting — the screen waits perpetually until a
            // result arrives (or the user presses Back). On success → "Done"
            // (closes; the messenger then shows your chats). On failure →
            // "Retry" (restarts the flow so it waits for a fresh login).
            val r2 = result
            if (r2 != null) {
                val success = r2.first
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(top = 24.dp)
                        .focusRequester(buttonFocus)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .dpadFocusHighlight(
                            shape = RoundedCornerShape(24.dp),
                            borderColor = MaterialTheme.colorScheme.onPrimary,
                        )
                        .focusable()
                        .onDpadAction { if (success) onPaired() else retry(); true }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = if (success) "Done" else "Retry",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}
