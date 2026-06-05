package com.offlineinc.dumbdownlauncher.gmessages.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.offline.dpadmessenger.ui.DpadMessengerApp
import com.offlineinc.dumbdownlauncher.gmessages.GoogleMessagesAccountStore
import com.offlineinc.dumbdownlauncher.gmessages.GoogleMessagesPairing
import com.offlineinc.dumbdownlauncher.gmessages.GoogleMessagesPairingResult
import com.offlineinc.dumbdownlauncher.gmessages.GoogleMessagesRepository

/**
 * Top-level entry for the in-app Google Messages experience.
 *
 * Decides what to show based on pairing state, mirroring how
 * `dpad-messenger-backend/app` gates the Signal chat UI behind device
 * linking:
 *  - Already paired → the chat UI ([DpadMessengerApp]) backed by the real
 *    Google Messages repository.
 *  - Not paired → kick off [GoogleMessagesPairingClient], show the
 *    [GoogleMessagesLinkScreen] (connecting spinner → scannable QR), and
 *    flip to the chat UI once the phone confirms the pair.
 *
 * The chat UI and the link UI share the same Activity/host — no navigation
 * plumbing needed, just a state swap.
 */
@Composable
fun GoogleMessagesApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { GoogleMessagesAccountStore(context) }

    // Snapshot pairing status once. Flips to true when pairing completes.
    var paired by remember { mutableStateOf(store.isPaired()) }

    if (paired) {
        val repository = remember { GoogleMessagesRepository.create(context) }
        DpadMessengerApp(repository = repository, modifier = modifier)
        return
    }

    // Not paired: drive the process-scoped pairing client and render the
    // link screen. The client is intentionally NOT tied to this composition's
    // lifecycle — its long-poll must keep running while the user leaves the
    // app to scan the QR on their primary phone, and survive Activity
    // recreation (fold/unfold). See [GoogleMessagesPairing].
    val client = remember { GoogleMessagesPairing.getOrStart(context) }
    val state by client.state.collectAsState()

    LaunchedEffect(state) {
        if (state is GoogleMessagesPairingResult.Paired) {
            GoogleMessagesPairing.reset() // success — drop the pairing client
            paired = true
        }
    }

    GoogleMessagesLinkScreen(state = state, modifier = modifier)
}
