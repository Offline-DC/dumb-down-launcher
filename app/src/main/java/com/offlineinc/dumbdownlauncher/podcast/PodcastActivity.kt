package com.offlineinc.dumbdownlauncher.podcast

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * dumb podcast — D-pad podcast player living in assets/podcast/. Same
 * virtual-app pattern as [com.offlineinc.dumbdownlauncher.MapsActivity]:
 * a full-screen WebView renders the whole UI, hardware softkeys are forwarded
 * into JS, and a JavascriptInterface ("Bridge") lets the page drive native
 * playback + downloads.
 *
 * Audio lives in [PlaybackService] (MediaSession + foreground notification) so
 * it keeps playing when the app is backgrounded and shows AntennaPod-style
 * transport controls in the shade. Progress/state flow back to JS through the
 * bound-service Listener.
 */
class PodcastActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var downloads: Downloads
    private var svc: PlaybackService? = null
    private var pageReady = false
    private var pendingOpenNp = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            svc = (b as PlaybackService.LocalBinder).service
            svc?.setListener(listener)
        }
        override fun onServiceDisconnected(name: ComponentName?) { svc = null }
    }

    private val listener = object : PlaybackService.Listener {
        override fun onProgress(episodeId: String, positionMs: Long, durationMs: Long, playing: Boolean) {
            js("window.__onProgress && __onProgress(${jsStr(episodeId)},$positionMs,$durationMs,$playing)")
        }
        override fun onState(episodeId: String, state: String) {
            js("window.__onState && __onState(${jsStr(episodeId)},${jsStr(state)})")
        }
    }

    /** DownloadManager broadcasts completion; tell JS so the UI flips to "downloaded". */
    private val dlReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val id = i?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            val eid = downloads.episodeForDownload(id) ?: return
            // Read status BEFORE finalize: finalize drops the id→download mapping,
            // which would otherwise mask a FAILED download as "none".
            val status = downloads.statusJson(eid)
            Log.i("PodcastDL", "complete id=$id eid=$eid status=$status")
            downloads.finalize(eid)
            js("window.__onDownload && __onDownload(${jsStr(eid)},$status)")
        }
    }

    @Suppress("DEPRECATION") // allowUniversalAccessFromFileURLs
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloads = Downloads(this)

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // localStorage: subscriptions + resume positions
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            allowUniversalAccessFromFileURLs = true   // file:// page can fetch() iTunes + RSS
            userAgentString = "Podcast/1.0 (TCL Flip 2; +jack@offline.community) $userAgentString"
        }
        web.setBackgroundColor(0xFF000000.toInt())
        web.addJavascriptInterface(Bridge(), "Bridge")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                if (pendingOpenNp) { pendingOpenNp = false; js("window.__openNowPlaying && __openNowPlaying()") }
            }
        }
        setContentView(web)

        // black system bars, matching MapsActivity / the dumb theme
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        web.loadUrl("file:///android_asset/podcast/index.html")
        handleOpenNpIntent(intent)

        // started + bound: started so playback survives the activity being backgrounded
        val svcIntent = Intent(this, PlaybackService::class.java)
        startService(svcIntent)
        bindService(svcIntent, conn, Context.BIND_AUTO_CREATE)

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(dlReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(dlReceiver, filter)
        }
    }

    override fun onDestroy() {
        try { svc?.setListener(null) } catch (_: Exception) {}
        try { unbindService(conn) } catch (_: Exception) {}
        try { unregisterReceiver(dlReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    /** Tapping the notification on a running app reuses this singleTop activity. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenNpIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        js("window.__onResume && __onResume()")
    }

    /** Notification tap carries OPEN_NOW_PLAYING → jump the UI to now-playing.
     *  Deferred until the WebView page is ready (cold start from the notification). */
    private fun handleOpenNpIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(PlaybackService.EXTRA_OPEN_NP, false) == true) {
            intent.removeExtra(PlaybackService.EXTRA_OPEN_NP)
            if (pageReady) js("window.__openNowPlaying && __openNowPlaying()") else pendingOpenNp = true
        }
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    private fun jsStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // ---- softkeys --------------------------------------------------------
    // Real Flip 2 hardware sends KEYCODE_SOFT_LEFT/RIGHT; emulators send MENU
    // for the left softkey. Identical forwarding scheme to MapsActivity.
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val name = when (e.keyCode) {
            KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_MENU -> "SoftLeft"
            KeyEvent.KEYCODE_SOFT_RIGHT -> "SoftRight"
            else -> null
        }
        if (name != null) {
            when (e.action) {
                KeyEvent.ACTION_DOWN ->
                    js("window.${if (e.repeatCount > 0) "__keyRepeat" else "__key"}('$name')")
                KeyEvent.ACTION_UP ->
                    js("window.__keyUp && __keyUp('$name')")
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        js("window.__key && __key('Back')")
        // JS decides: pop a screen, or call Bridge.exitApp()
    }

    // ---- JS bridge -------------------------------------------------------
    inner class Bridge {
        @JavascriptInterface fun exitApp() = runOnUiThread { finish() }   // back to the launcher

        @JavascriptInterface
        fun play(url: String, episodeId: String, title: String,
                 podcast: String, artworkUrl: String, startMs: Int) = runOnUiThread {
            startService(Intent(this@PodcastActivity, PlaybackService::class.java))
            svc?.play(url, episodeId, title, podcast, artworkUrl, startMs)
        }

        @JavascriptInterface fun togglePause() = runOnUiThread { svc?.togglePause() }
        @JavascriptInterface fun pause() = runOnUiThread { svc?.pause() }
        @JavascriptInterface fun resume() = runOnUiThread { svc?.resume() }
        @JavascriptInterface fun seekTo(ms: Int) = runOnUiThread { svc?.seekTo(ms) }
        @JavascriptInterface fun skip(deltaMs: Int) = runOnUiThread { svc?.skip(deltaMs) }
        @JavascriptInterface fun stop() = runOnUiThread { svc?.stop() }

        /** Synchronous snapshot for when JS (re)opens the now-playing screen. */
        @JavascriptInterface fun playbackState(): String = svc?.stateJson() ?: "{}"

        // downloads
        @JavascriptInterface fun download(url: String, episodeId: String, title: String) =
            downloads.enqueue(url, episodeId, title)
        @JavascriptInterface fun downloadStatus(episodeId: String): String =
            downloads.statusJson(episodeId)
        @JavascriptInterface fun localPath(episodeId: String): String =
            downloads.localPath(episodeId)
        @JavascriptInterface fun deleteDownload(episodeId: String) =
            downloads.delete(episodeId)
    }
}
