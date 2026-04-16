package com.offlineinc.dumbdownlauncher.contactsync.icloud

import android.content.Context
import com.offlineinc.dumbdownlauncher.contactsync.sync.ContactSyncApiClient
import com.offlineinc.dumbdownlauncher.contactsync.sync.ContactSyncRepository
import com.offlineinc.dumbdownlauncher.contactsync.sync.ContactSyncStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object ServiceLocator {
    @Volatile private var store: ContactSyncStore? = null
    @Volatile private var apiClient: ContactSyncApiClient? = null
    @Volatile private var syncRepo: ContactSyncRepository? = null

    fun contactSyncStore(ctx: Context): ContactSyncStore {
        return store ?: synchronized(this) {
            store ?: ContactSyncStore(ctx.applicationContext).also { store = it }
        }
    }

    fun contactSyncApiClient(ctx: Context): ContactSyncApiClient {
        return apiClient ?: synchronized(this) {
            apiClient ?: ContactSyncApiClient(
                OkHttpClient.Builder()
                    .pingInterval(20, TimeUnit.SECONDS)
                    .build()
            ).also { apiClient = it }
        }
    }

    fun syncRepository(ctx: Context): ContactSyncRepository {
        return syncRepo ?: synchronized(this) {
            syncRepo ?: ContactSyncRepository(
                context = ctx.applicationContext,
                apiClient = contactSyncApiClient(ctx),
                store = contactSyncStore(ctx)
            ).also { syncRepo = it }
        }
    }

    fun syncRepositoryOrNull(): ContactSyncRepository? = syncRepo
}
