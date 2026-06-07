package com.offlineinc.dumbdownlauncher.messenger

import android.os.Bundle
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
import com.offlineinc.dumbdownlauncher.pairing.PairingStore
import com.offlineinc.dumbdownlauncher.typesync.TypeSyncService
import com.offline.dpadmessenger.focus.onDpadAction
import com.offline.dpadmessenger.ui.theme.DpadMessengerTheme

/**
 * Hosts the "Sign in with your Google account via your phone" flow. The flip
 * phone listens on the Type Sync relay; the companion smartphone signs into
 * Google and sends the cookies over (see [GmessagesCookieRelayClient]).
 * Opened by [MessengerActivity] when the user picks the account sign-in.
 */
class GoogleCookieReceiveActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DpadMessengerTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CookieReceiveScreen(onDone = { finish() })
                }
            }
        }
    }
}

@Composable
private fun CookieReceiveScreen(onDone: () -> Unit) {
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

    // Text sync and this cookie receiver both connect to the relay as the single
    // "phone" role — running both at once makes them evict each other. Suspend
    // text sync for the lifetime of this screen so the cookie receiver owns the
    // slot, then hand it back on exit.
    DisposableEffect(Unit) {
        TypeSyncService.suspendForGmessages()
        onDispose { TypeSyncService.resumeFromGmessages() }
    }

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
            val client = GmessagesCookieRelayClient(context)
            client.start(
                onCookies = { signingIn = true },
                onEmoji = { emoji = it },
            ) { ok, msg -> result = ok to (msg ?: "") }
            onDispose { client.stop() }
        }
    }
    BackHandler(onBack = onDone)

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
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
                    text = "on ur smart phone, open Dumb Down → Smart Txt → " +
                        "“sign in to google,” then approve. keep this screen open.",
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
                        .onDpadAction { if (success) onDone() else retry(); true }
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
