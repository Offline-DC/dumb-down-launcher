package com.offlineinc.dumbdownlauncher

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * dumb map (beta) — D-pad maps app (Leaflet + OSM/CARTO tiles, Nominatim
 * search, OSRM directions) living in assets/map/. The page is fully
 * keypad-driven; this activity just hosts the WebView, forwards the
 * softkeys/back (which never reach the DOM as useful key events), and
 * pushes GPS fixes over a JS bridge.
 */
class MapsActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var lm: LocationManager
    private var locationStarted = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) = pushLocation(l)
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true   // asset page -> nominatim/osrm fetch
            // identify politely to the OSM-ecosystem servers
            userAgentString = "DumbMap/1.0 (TCL Flip 2; +jack@offline.community) $userAgentString"
        }
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "Bridge")
        web.setBackgroundColor(0xFF000000.toInt())
        setContentView(web)
        web.loadUrl("file:///android_asset/map/index.html")

        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (hasLocationPermission()) startLocationUpdates()
        else requestPermissions(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION), 1)
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, perms, grants)
        if (hasLocationPermission()) startLocationUpdates()
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        if (locationStarted || !hasLocationPermission()) return
        locationStarted = true
        try {
            for (p in lm.getProviders(true)) {
                lm.requestLocationUpdates(p, 2000L, 2f, listener)
                lm.getLastKnownLocation(p)?.let { pushLocation(it) }
            }
        } catch (_: Exception) {}
    }

    private fun pushLocation(l: Location) = runOnUiThread {
        web.evaluateJavascript(
            "window.__onLocation && __onLocation(${l.latitude},${l.longitude},${l.accuracy})", null)
    }

    private fun js(name: String, repeat: Boolean) {
        web.evaluateJavascript(
            "window.${if (repeat) "__keyRepeat" else "__key"}('$name')", null)
    }

    /** Softkeys never reach the DOM as useful key events — forward them.
     *  Real TCL Flip 2 hardware sends KEYCODE_SOFT_LEFT / KEYCODE_SOFT_RIGHT;
     *  emulators often send MENU for the left softkey. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val name = when (e.keyCode) {
            KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_MENU -> "SoftLeft"
            KeyEvent.KEYCODE_SOFT_RIGHT -> "SoftRight"
            else -> null
        } ?: return super.dispatchKeyEvent(e)
        when (e.action) {
            KeyEvent.ACTION_DOWN -> js(name, e.repeatCount > 0)
            // release the JS fire-gate, otherwise rapid presses get eaten
            KeyEvent.ACTION_UP -> web.evaluateJavascript("window.__keyUp && __keyUp('$name')", null)
        }
        return true
    }

    /** Called only after the IME has had its chance to consume Back. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        js("Back", false)
        // JS decides: close overlay / clear route / or call Bridge.exitApp()
    }

    /** Sticky "navigation running" notification while in turn-by-turn mode. */
    private fun showNavNotification(show: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!show) { nm.cancel(NAV_NOTIF_ID); return }
        nm.createNotificationChannel(
            NotificationChannel("nav", "Navigation", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MapsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "nav")
            .setSmallIcon(R.drawable.ic_map_pin)
            .setContentTitle("dumb map navigation running")
            .setContentText("tap to return to the map")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        nm.notify(NAV_NOTIF_ID, n)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun exitApp() = runOnUiThread { finish() }   // back to the launcher

        @JavascriptInterface
        fun startLocation() = runOnUiThread { startLocationUpdates() }

        @JavascriptInterface
        fun setNavigating(on: Boolean) = runOnUiThread { showNavNotification(on) }
    }

    override fun onDestroy() {
        try { lm.removeUpdates(listener) } catch (_: Exception) {}
        try { showNavNotification(false) } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val NAV_NOTIF_ID = 4242
    }
}
