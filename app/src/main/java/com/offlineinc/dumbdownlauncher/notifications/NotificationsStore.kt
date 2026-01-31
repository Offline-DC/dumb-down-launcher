package com.offlineinc.dumbdownlauncher.notifications

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.offlineinc.dumbdownlauncher.notifications.model.NotificationItem
import java.util.concurrent.ConcurrentHashMap

object NotificationStore {

    private val map = ConcurrentHashMap<String, NotificationItem>()
    private val live = MutableLiveData<List<NotificationItem>>(emptyList())

    fun items(): LiveData<List<NotificationItem>> = live

    fun upsert(item: NotificationItem) {
        map[item.key] = item
        publish()
    }

    fun remove(key: String) {
        map.remove(key)
        publish()
    }

    fun setAll(items: List<NotificationItem>) {
        map.clear()
        for (it in items) map[it.key] = it
        publish()
    }

    private fun publish() {
        // newest first
        live.postValue(map.values.sortedByDescending { it.postTime })
    }
}
