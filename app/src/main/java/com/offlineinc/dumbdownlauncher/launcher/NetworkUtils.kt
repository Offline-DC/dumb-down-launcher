package com.offlineinc.dumbdownlauncher.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log

/**
 * Utility for checking network availability and deferring work until
 * connectivity is established.  Designed for the launcher's boot path
 * where cellular/Wi-Fi may not be ready yet.
 */
object NetworkUtils {

    private const val TAG = "NetworkUtils"

    /**
     * Returns `true` when the device has an active network with internet
     * capability (Wi-Fi, cellular, ethernet, …).
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Runs [action] immediately if the network is already available,
     * otherwise registers a one-shot callback that fires as soon as
     * connectivity appears.  Safe to call from any thread.
     */
    fun whenNetworkAvailable(context: Context, action: () -> Unit) {
        if (isNetworkAvailable(context)) {
            Log.d(TAG, "Network already available — running action immediately")
            action()
            return
        }

        Log.d(TAG, "Network not available — registering callback")
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Log.w(TAG, "ConnectivityManager unavailable — running action anyway")
            action()
            return
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network became available — running deferred action")
                cm.unregisterNetworkCallback(this)
                action()
            }
        })
    }
}
