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
import kotlinx.coroutines.withContext

private const val TAG = "ContactSyncNav"

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
        val info = withContext(Dispatchers.IO) {
            DeviceLinkReader.readAndCache(ctx)
        }
        if (info != null) {
            Log.i(TAG, "Pairing data cached: pairingId=${info.pairingId}")
        } else {
            // Not paired — clear local sync data
            if (store.isPaired) {
                Log.i(TAG, "Unpaired — clearing local contact sync data")
                store.clear()
            }
        }
        isPaired = store.isPaired
        ready = true
    }

    if (!ready) return

    if (isPaired) {
        HomeRoute(isOnboarding = isOnboarding, onFinish = onFinish)
    } else {
        // Not paired — show a message directing user to the launcher
        NoPairingMessage()
    }
}
