package com.offlineinc.dumbdownlauncher.contactsync.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.offlineinc.dumbdownlauncher.contactsync.icloud.ServiceLocator
import com.offlineinc.dumbdownlauncher.contactsync.sync.DeviceLinkReader
import com.offlineinc.dumbdownlauncher.contactsync.ui.screens.HomeRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "ContactSyncNav"

/** Max retries when pairing data isn't found — handles race where
 *  PairingStore writes haven't fully propagated yet. */
private const val PAIRING_CHECK_RETRIES = 3
private const val PAIRING_CHECK_DELAY_MS = 500L

@Composable
fun ContactSyncNav(
    isOnboarding: Boolean = false,
    onFinish: () -> Unit = {}
) {
    val ctx = LocalContext.current.applicationContext
    val store = remember { ServiceLocator.contactSyncStore(ctx) }

    // Always refresh pairing data from PairingStore — the user may have
    // re-paired, which gives a new sharedSecret the server expects.
    var ready by remember { mutableStateOf(false) }
    var isPaired by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        Log.i(TAG, "Refreshing pairing data from PairingStore")

        // Retry a few times to handle the race condition where we arrive
        // right after pairing and the PairingStore writes haven't landed yet.
        var info: DeviceLinkReader.PairingInfo? = null
        for (attempt in 1..PAIRING_CHECK_RETRIES) {
            info = withContext(Dispatchers.IO) {
                DeviceLinkReader.readAndCache(ctx)
            }
            if (info != null) {
                Log.i(TAG, "Pairing data cached on attempt $attempt: pairingId=${info.pairingId}")
                break
            }
            if (attempt < PAIRING_CHECK_RETRIES) {
                Log.i(TAG, "Pairing data not found on attempt $attempt — retrying in ${PAIRING_CHECK_DELAY_MS}ms")
                delay(PAIRING_CHECK_DELAY_MS)
            }
        }

        if (info == null) {
            // Still not paired after retries — clear stale local data
            if (store.isPaired) {
                Log.i(TAG, "Unpaired — clearing local contact sync data")
                store.clear()
            }
        }

        isPaired = store.isPaired
        ready = true

        // If not paired, don't show a dead-end screen — finish the activity
        // so the user goes back to the pairing screen (onboarding) or launcher.
        if (!store.isPaired) {
            Log.i(TAG, "Not paired — finishing activity to return to pairing flow")
            onFinish()
        }
    }

    if (!ready) return

    if (isPaired) {
        HomeRoute(isOnboarding = isOnboarding, onFinish = onFinish)
    }
    // If not paired, onFinish() was already called above — no dead-end screen.
}
