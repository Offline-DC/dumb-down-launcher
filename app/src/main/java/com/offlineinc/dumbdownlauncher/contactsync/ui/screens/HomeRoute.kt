package com.offlineinc.dumbdownlauncher.contactsync.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlineinc.dumbdownlauncher.contactsync.sync.DeviceLinkReader
import com.offlineinc.dumbdownlauncher.contactsync.ui.screens.home.HomeScreen
import com.offlineinc.dumbdownlauncher.contactsync.ui.screens.home.HomeViewModel

@Composable
fun HomeRoute(
    isOnboarding: Boolean = false,
    onFinish: () -> Unit = {},
    vm: HomeViewModel = viewModel()
) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()

    // Reset state and reconnect on every resume (not just first composition)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Re-read pairing data — the user may have
                // unpaired and re-paired, giving a new sharedSecret.
                DeviceLinkReader.readAndCache(ctx)

                // Don't reset if a sync is actively running
                if (!vm.isSyncing) {
                    vm.resetForReconnect()
                    vm.refreshStatus(ctx)
                    vm.connectWebSocket(ctx)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.disconnectWebSocket()
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        vm.refreshPermissions(ctx)
        // If permission was just granted, kick off a fresh connection attempt
        // so the user doesn't have to back out and re-enter the page
        val granted = grants.values.all { it }
        if (granted && !vm.isSyncing) {
            vm.resetForReconnect()
            vm.connectWebSocket(ctx)
        }
    }

    HomeScreen(
        ui = ui,
        isOnboarding = isOnboarding,
        onGrantContactsPermission = {
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.WRITE_CONTACTS
                )
            )
        },
        onSyncNow = { vm.syncNow(ctx) },
        onClearMessages = { vm.clearMessages() },
        onFinish = onFinish
    )
}
