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
import com.offlineinc.dumbdownlauncher.quack.NetworkLocationFetcher
import com.offlineinc.dumbdownlauncher.quack.QuackLocationStore

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
    private var pageReady = false
    @Volatile private var beaconInFlight = false

    private val listener = object : LocationListener {
        // live updates from the providers are always current → treat as fresh
        override fun onLocationChanged(l: Location) = pushLocation(l, fresh = true)
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
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // The JS bridge functions don't exist until the page is loaded,
                // so any fix pushed during onCreate is silently dropped. Once the
                // page is ready, seed it with the cached general-area location
                // (for centring + search bias, no dot) and re-push last-known.
                pageReady = true
                pushCachedApprox()
                if (locationStarted) pushLastKnown()
            }
        }
        web.addJavascriptInterface(Bridge(), "Bridge")
        web.setBackgroundColor(0xFF000000.toInt())
        setContentView(web)

        // black system bars with light icons, regardless of day/night theme
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        window.insetsController?.setSystemBarsAppearance(
            0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)

        web.loadUrl("file:///android_asset/map/index.html")

        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // Mirror quack/weather: kick off the one-shot BeaconDB lookup on *every*
        // launch (not only when the permission is first granted) so a returning
        // user who already said yes gets a fresh general-area fix right away.
        // Only needs coarse permission, so it fires even when GPS isn't granted.
        startBeaconFix()
        if (hasLocationPermission()) startLocationUpdates()
        else requestPermissions(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION), 1)
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** BeaconDB only needs coarse location (Wi-Fi/cell scan), like quack/weather. */
    private fun hasAnyLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, perms, grants)
        // on "yes": fire the one-shot beacon (coarse is enough) and, if GPS was
        // granted, start the live providers too
        startBeaconFix()
        if (hasLocationPermission()) startLocationUpdates()
    }

    /** Actively (re)request location. Called at launch, on permission grant,
     *  on resume, and every time the user hits locate — re-registering is
     *  safe and guarantees the GPS engine actually spins up (status-bar icon). */
    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        locationStarted = true
        try {
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                web.evaluateJavascript("window.__onLocationOff && __onLocationOff()", null)
            }
            lm.removeUpdates(listener)
            // request explicitly on GPS + network — getProviders(true) can be
            // empty/partial at cold start, which used to register nothing
            for (p in arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.allProviders.contains(p)) {
                    try { lm.requestLocationUpdates(p, 1000L, 0f, listener) } catch (_: Exception) {}
                }
            }
            pushLastKnown()
        } catch (_: Exception) {}
    }

    /** Push the best cached fix from the providers. A recent fix becomes the
     *  blue-dot location; a stale one is only used to centre the map / bias
     *  search (so we never show a stale dot when returning to the app). */
    @Suppress("MissingPermission")
    private fun pushLastKnown() {
        var best: Location? = null
        for (p in lm.allProviders) {
            try {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best!!.time) best = l
            } catch (_: Exception) {}
        }
        best?.let { pushLocation(it, fresh = isRecent(it)) }
    }

    /** A one-shot BeaconDB lookup, mirroring how quack/weather fetch on open.
     *  Delivers a fresh coarse fix (so it's allowed to show the dot) and
     *  persists it to the shared store so the next open can centre instantly.
     *  Fired on every launch/open and on permission grant; the in-flight guard
     *  just prevents overlapping scans. Needs only coarse permission. */
    private fun startBeaconFix() {
        if (beaconInFlight || !hasAnyLocationPermission()) return
        beaconInFlight = true
        Thread({
            try {
                val fix = NetworkLocationFetcher.fetch(this) ?: return@Thread
                QuackLocationStore.save(this, fix.lat, fix.lng)
                runOnUiThread {
                    web.evaluateJavascript(
                        "window.__onLocation && __onLocation(${fix.lat},${fix.lng},${fix.accuracyMeters})", null)
                }
            } catch (_: Exception) {
            } finally {
                beaconInFlight = false
            }
        }, "DumbMap-BeaconDB").start()
    }

    /** Seed the page with the shared persisted location (the same cache quack
     *  and weather read) so the map opens on the user's general area before any
     *  live fix arrives. Centres + biases search only — never shows the dot. */
    private fun pushCachedApprox() {
        if (!pageReady) return
        val p = QuackLocationStore.loadIfUsable(this) ?: return
        web.evaluateJavascript(
            "window.__onApproxLocation && __onApproxLocation(${p.first},${p.second})", null)
    }

    private fun isRecent(l: Location) =
        System.currentTimeMillis() - l.time <= RECENT_FIX_MAX_AGE_MS

    override fun onResume() {
        super.onResume()
        // let the page drop a now-stale dot before we re-request fixes, so a
        // returning user never sees an out-of-date location
        if (pageReady) web.evaluateJavascript("window.__onResume && __onResume()", null)
        // re-fetch a fresh BeaconDB fix on every open, the way quack/weather do
        startBeaconFix()
        if (locationStarted) startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        try { lm.removeUpdates(listener) } catch (_: Exception) {}  // save battery while away
    }

    /** A fresh fix drives the location dot; a stale one only re-centres the map
     *  and biases search (via __onApproxLocation), so the dot is never stale. */
    private fun pushLocation(l: Location, fresh: Boolean) = runOnUiThread {
        val fn = if (fresh) "__onLocation" else "__onApproxLocation"
        val args = if (fresh) "${l.latitude},${l.longitude},${l.accuracy}"
                   else "${l.latitude},${l.longitude}"
        web.evaluateJavascript("window.$fn && $fn($args)", null)
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
            .setContentTitle("map navigation running")
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
        fun startLocation() = runOnUiThread {
            // hitting locate without the permission re-prompts instead of
            // silently doing nothing
            if (hasLocationPermission()) startLocationUpdates()
            else requestPermissions(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }

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
        /** A fix older than this isn't shown as the live dot (only used to
         *  centre/bias search) so returning to the app never shows a stale dot. */
        private const val RECENT_FIX_MAX_AGE_MS = 2 * 60 * 1000L  // 2 minutes
    }
}
