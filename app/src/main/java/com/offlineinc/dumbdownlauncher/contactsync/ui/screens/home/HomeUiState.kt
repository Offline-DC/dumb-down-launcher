package com.offlineinc.dumbdownlauncher.contactsync.ui.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlineinc.dumbdownlauncher.contactsync.icloud.ServiceLocator
import com.offlineinc.dumbdownlauncher.contactsync.icloud.hasContactsPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import android.util.Log

data class HomeUiState(
    val hasContactsPerm: Boolean? = null,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val isSyncing: Boolean = false,
    val syncComplete: Boolean = false,
    val canClose: Boolean = false,
    val lastSyncMillis: Long = 0L,
    val totalContactCount: Int = 0,
    val contactCountLoaded: Boolean = false,
    val flipPhoneNumber: String? = null,
    val status: String? = null,
    val error: String? = null
)

private const val TAG = "HomeViewModel"

class HomeViewModel : ViewModel() {
    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    private var connectJob: Job? = null

    /** True while a sync transfer is actively running. */
    val isSyncing: Boolean get() = _ui.value.isSyncing

    fun refreshPermissions(ctx: Context) {
        val hasPerm = hasContactsPermissions(ctx)
        Log.d(TAG, "[ContactSync] refreshPermissions: hasContactsPerm=$hasPerm")
        _ui.update { it.copy(hasContactsPerm = hasPerm) }
    }

    fun clearMessages() {
        _ui.update { it.copy(status = null, error = null) }
    }

    fun refreshStatus(ctx: Context) {
        val store = ServiceLocator.contactSyncStore(ctx.applicationContext)
        val hasPerm = hasContactsPermissions(ctx)
        Log.i(TAG, "[ContactSync] refreshStatus: lastSync=${store.lastSyncMillis}, hasPerm=$hasPerm, phone=${store.flipPhoneNumber}")
        _ui.update {
            it.copy(
                hasContactsPerm = hasPerm,
                lastSyncMillis = store.lastSyncMillis,
                flipPhoneNumber = store.flipPhoneNumber
            )
        }
        if (hasPerm) {
            viewModelScope.launch(Dispatchers.IO) {
                val total = readLocalContactCount(ctx)
                Log.i(TAG, "[ContactSync] refreshStatus: localTotal=$total")
                _ui.update { it.copy(totalContactCount = total, contactCountLoaded = true) }
            }
        }
    }

    private fun readLocalContactCount(ctx: Context): Int {
        return try {
            ctx.contentResolver.query(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.Contacts._ID),
                null, null, null
            )?.use { cursor -> cursor.count } ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "[ContactSync] readLocalContactCount failed", e)
            0
        }
    }

    fun unpair(ctx: Context) {
        Log.i(TAG, "[ContactSync] unpair: starting")
        disconnectWebSocket()
        viewModelScope.launch {
            try {
                val store = ServiceLocator.contactSyncStore(ctx.applicationContext)
                val apiClient = ServiceLocator.contactSyncApiClient(ctx.applicationContext)
                val phoneNumber = store.flipPhoneNumber
                val secret = store.sharedSecret

                if (phoneNumber != null && secret != null) {
                    Log.i(TAG, "[ContactSync] unpair: calling API for phone=$phoneNumber")
                    withContext(Dispatchers.IO) {
                        try {
                            apiClient.unpair(phoneNumber, secret)
                            Log.i(TAG, "[ContactSync] unpair: API call success")
                        } catch (e: Exception) {
                            Log.w(TAG, "[ContactSync] unpair: API call failed", e)
                        }
                    }
                } else {
                    Log.i(TAG, "[ContactSync] unpair: no stored credentials, skipping API call")
                }

                store.clear()
                Log.i(TAG, "[ContactSync] unpair: store cleared")
            } catch (e: Exception) {
                Log.e(TAG, "[ContactSync] unpair: FAILED", e)
            }
        }
    }

    fun resetForReconnect() {
        disconnectWebSocket()
        _ui.update {
            it.copy(
                isConnecting = false, isConnected = false,
                isSyncing = false, syncComplete = false, canClose = false,
                error = null, status = null
            )
        }
    }

    fun connectWebSocket(ctx: Context) {
        if (connectJob?.isActive == true) return
        Log.i(TAG, "[ContactSync] connectWebSocket: starting")
        _ui.update {
            it.copy(
                isConnecting = true, isConnected = false,
                isSyncing = false, syncComplete = false,
                error = null, status = null
            )
        }

        connectJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ServiceLocator.syncRepository(ctx).connectAndWaitForReady(
                        onPeerDisconnectedAfterReady = {
                            if (_ui.value.isSyncing) {
                                Log.i(TAG, "[ContactSync] peer disconnected after ready — sync in progress, ignoring")
                            } else {
                                Log.w(TAG, "[ContactSync] peer disconnected after ready — resetting to connecting")
                                _ui.update {
                                    it.copy(isConnecting = true, isConnected = false, status = null)
                                }
                                connectJob = null
                                connectWebSocket(ctx)
                            }
                        }
                    )
                }
                Log.i(TAG, "[ContactSync] connectWebSocket: both_ready — connected!")
                _ui.update {
                    it.copy(isConnecting = false, isConnected = true, status = "connected!")
                }
            } catch (e: CancellationException) {
                // If the ViewModel scope itself is dead, don't retry — just stop
                if (!viewModelScope.isActive) throw e
                // Otherwise the inner coroutine was cancelled (e.g. WebSocket timeout) — silently retry
                Log.w(TAG, "[ContactSync] connectWebSocket: cancelled — will retry silently")
                _ui.update { it.copy(isConnecting = true, isConnected = false, error = null) }
                connectJob = null
                connectWebSocket(ctx)
            } catch (t: Throwable) {
                Log.e(TAG, "[ContactSync] connectWebSocket: FAILED", t)
                // Don't show error — silently retry
                _ui.update { it.copy(isConnecting = true, isConnected = false, error = null) }
                delay(2000)
                connectJob = null
                connectWebSocket(ctx)
            }
        }
    }

    fun disconnectWebSocket() {
        connectJob?.cancel()
        connectJob = null
        ServiceLocator.syncRepositoryOrNull()?.disconnectWebSocket()
        _ui.update { it.copy(isConnecting = false, isConnected = false) }
    }

    fun syncNow(ctx: Context) {
        Log.i(TAG, "[ContactSync] syncNow: starting")

        // Guard: if WebSocket was closed (e.g. peer disconnected), go back to connecting
        if (ServiceLocator.syncRepositoryOrNull()?.isWebSocketConnected != true) {
            Log.w(TAG, "[ContactSync] syncNow: WebSocket not connected — reconnecting")
            _ui.update { it.copy(isConnecting = true, isConnected = false, status = null) }
            connectWebSocket(ctx)
            return
        }

        viewModelScope.launch {
            _ui.update { it.copy(isSyncing = true, error = null, status = "syncing...") }

            try {
                val baseCount = _ui.value.totalContactCount
                withContext(Dispatchers.IO) {
                    ServiceLocator.syncRepository(ctx).syncWithConnectedWebSocket(
                        onProgress = { smartImported ->
                            _ui.update {
                                it.copy(
                                    totalContactCount = baseCount + smartImported,
                                    contactCountLoaded = true,
                                    canClose = true
                                )
                            }
                        },
                        onCanClose = {
                            _ui.update { it.copy(canClose = true) }
                        }
                    )
                }

                val store = ServiceLocator.contactSyncStore(ctx.applicationContext)
                val total = readLocalContactCount(ctx)
                Log.i(TAG, "[ContactSync] syncNow: SUCCESS — localTotal=$total, lastSync=${store.lastSyncMillis}")
                _ui.update {
                    it.copy(
                        isSyncing = false,
                        syncComplete = true,
                        isConnected = false,
                        status = "sync complete!",
                        lastSyncMillis = store.lastSyncMillis,
                        totalContactCount = total
                    )
                }
            } catch (t: Throwable) {
                if (_ui.value.isSyncing && (t.message?.contains("disconnected", ignoreCase = true) == true)) {
                    Log.w(TAG, "[ContactSync] syncNow: peer disconnected during sync — treating as success", t)
                    val store = ServiceLocator.contactSyncStore(ctx.applicationContext)
                    val total = readLocalContactCount(ctx)
                    _ui.update {
                        it.copy(
                            isSyncing = false,
                            syncComplete = true,
                            isConnected = false,
                            status = "sync complete!",
                            lastSyncMillis = store.lastSyncMillis,
                            totalContactCount = total
                        )
                    }
                } else {
                    Log.e(TAG, "[ContactSync] syncNow: FAILED", t)
                    _ui.update {
                        it.copy(
                            isSyncing = false, isConnected = false,
                            error = t.message ?: "Sync failed", status = null
                        )
                    }
                    // Reconnect after failure
                    delay(1500)
                    connectWebSocket(ctx)
                }
            }
        }
    }
}
