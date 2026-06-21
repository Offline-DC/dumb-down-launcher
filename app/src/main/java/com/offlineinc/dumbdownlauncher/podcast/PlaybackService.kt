package com.offlineinc.dumbdownlauncher.podcast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import com.offlineinc.dumbdownlauncher.R
import java.net.URL

/**
 * Foreground media service for the dumb podcast app. Owns the MediaPlayer, a
 * MediaSessionCompat, and the AntennaPod-style MediaStyle notification (episode
 * title + podcast name, tap to open, transport controls in the shade / on the
 * lock screen).
 *
 * The WebView UI drives it through the Bridge in [PodcastActivity] (which binds
 * here); the notification + media buttons drive it through the MediaSession
 * callback. Both paths converge on the same private play/pause/seek methods, and
 * every state change is pushed back to the bound listener so the JS now-playing
 * screen stays in sync no matter where the command came from.
 */
class PlaybackService : android.app.Service() {

    /** What the UI hears back. PodcastActivity registers one of these. */
    interface Listener {
        fun onProgress(episodeId: String, positionMs: Long, durationMs: Long, playing: Boolean)
        fun onState(episodeId: String, state: String) // "playing" | "paused" | "ended" | "stopped" | "buffering" | "error"
    }

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    private val binder = LocalBinder()
    private var listener: Listener? = null

    private var player: MediaPlayer? = null
    private lateinit var session: MediaSessionCompat
    private val handler = Handler(Looper.getMainLooper())

    // current item
    private var episodeId = ""
    private var title = ""
    private var podcast = ""
    private var artworkUrl = ""
    private var artwork: Bitmap? = null
    private var prepared = false
    private var pendingSeekMs = 0

    companion object {
        private const val CHANNEL = "podcast_playback"
        private const val NOTIF_ID = 4343
        const val ACTION_SKIP_FWD = "com.offlineinc.dumbdownlauncher.podcast.SKIP_FWD"
        const val ACTION_SKIP_BACK = "com.offlineinc.dumbdownlauncher.podcast.SKIP_BACK"
        const val EXTRA_OPEN_NP = "com.offlineinc.dumbdownlauncher.podcast.OPEN_NOW_PLAYING"
        const val SKIP_FWD_MS = 30_000
        const val SKIP_BACK_MS = 15_000
    }

    // ---- lifecycle -------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, "PodcastSession").apply {
            setCallback(sessionCallback)
            isActive = true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SKIP_FWD -> skip(SKIP_FWD_MS)
            ACTION_SKIP_BACK -> skip(-SKIP_BACK_MS)
            else -> MediaButtonReceiver.handleIntent(session, intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopProgress()
        player?.release()
        player = null
        session.release()
        super.onDestroy()
    }

    fun setListener(l: Listener?) { listener = l }

    // ---- public control surface (called from the Bridge) -----------------

    fun play(
        url: String, episodeId: String, title: String,
        podcast: String, artworkUrl: String, startMs: Int
    ) {
        this.episodeId = episodeId
        this.title = title
        this.podcast = podcast
        this.pendingSeekMs = startMs
        if (artworkUrl != this.artworkUrl) { this.artwork = null }
        this.artworkUrl = artworkUrl

        prepared = false
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            setOnPreparedListener {
                prepared = true
                if (pendingSeekMs > 0) seekTo(pendingSeekMs)
                start()
                onPlaybackChanged("playing")
                startProgress()
            }
            setOnCompletionListener {
                onPlaybackChanged("ended")
                stopProgress()
                emitProgress() // final position
            }
            setOnErrorListener { _, _, _ ->
                onPlaybackChanged("error")
                true
            }
            setDataSource(url)
            onPlaybackChanged("buffering")
            prepareAsync()
        }
        loadArtworkAsync()
    }

    fun togglePause() {
        val p = player ?: return
        if (p.isPlaying) pause() else resume()
    }

    fun pause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            onPlaybackChanged("paused")
            stopProgress()
            emitProgress()
        }
    }

    fun resume() {
        val p = player ?: return
        if (prepared && !p.isPlaying) {
            p.start()
            onPlaybackChanged("playing")
            startProgress()
        }
    }

    fun seekTo(ms: Int) {
        val p = player ?: return
        if (prepared) { p.seekTo(ms.coerceAtLeast(0)); emitProgress() }
        else pendingSeekMs = ms
    }

    fun skip(deltaMs: Int) {
        val p = player ?: return
        if (!prepared) return
        val target = (p.currentPosition + deltaMs).coerceIn(0, p.duration.coerceAtLeast(0))
        p.seekTo(target)
        emitProgress()
    }

    fun stop() {
        stopProgress()
        player?.release()
        player = null
        prepared = false
        onState("stopped")
        session.isActive = false
        stopForeground(true)
        stopSelf()
    }

    fun stateJson(): String {
        val p = player
        val playing = p?.isPlaying == true
        val pos = if (prepared && p != null) p.currentPosition else pendingSeekMs
        val dur = if (prepared && p != null) p.duration else 0
        return """{"episodeId":${jsStr(episodeId)},"playing":$playing,"positionMs":$pos,"durationMs":$dur,"prepared":$prepared}"""
    }

    // ---- progress ticker -------------------------------------------------

    private val ticker = object : Runnable {
        override fun run() {
            emitProgress()
            handler.postDelayed(this, 1000)
        }
    }
    private fun startProgress() { handler.removeCallbacks(ticker); handler.post(ticker) }
    private fun stopProgress() { handler.removeCallbacks(ticker) }

    private fun emitProgress() {
        val p = player ?: return
        val dur = if (prepared) p.duration.toLong() else 0L
        val pos = if (prepared) p.currentPosition.toLong() else pendingSeekMs.toLong()
        listener?.onProgress(episodeId, pos, dur, p.isPlaying)
    }

    private fun onState(state: String) { listener?.onState(episodeId, state) }

    private fun onPlaybackChanged(state: String) {
        onState(state)
        updateSession(state)
        updateNotification(state)
    }

    // ---- MediaSession ----------------------------------------------------

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() { resume() }
        override fun onPause() { pause() }
        override fun onStop() { stop() }
        override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
        override fun onFastForward() { skip(SKIP_FWD_MS) }
        override fun onRewind() { skip(-SKIP_BACK_MS) }
        override fun onSkipToNext() { skip(SKIP_FWD_MS) }
        override fun onSkipToPrevious() { skip(-SKIP_BACK_MS) }
    }

    private fun updateSession(state: String) {
        val p = player
        val playing = state == "playing"
        val pos = if (prepared && p != null) p.currentPosition.toLong() else pendingSeekMs.toLong()
        val pbState = when (state) {
            "playing" -> PlaybackStateCompat.STATE_PLAYING
            "paused" -> PlaybackStateCompat.STATE_PAUSED
            "buffering" -> PlaybackStateCompat.STATE_BUFFERING
            "ended", "stopped" -> PlaybackStateCompat.STATE_STOPPED
            else -> PlaybackStateCompat.STATE_NONE
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or
                        PlaybackStateCompat.ACTION_REWIND or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(pbState, pos, if (playing) 1f else 0f)
                .build()
        )
        val dur = if (prepared && p != null) p.duration.toLong() else 0L
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, podcast)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, podcast)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur)
                .apply { artwork?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
    }

    // ---- notification ----------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL, "Podcast playback", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            ch.setSound(null, null)
            nm.createNotificationChannel(ch)
        }
    }

    private fun pendingActivity(): PendingIntent {
        val open = Intent(this, PodcastActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_NP, true)   // land on the now-playing screen
        return PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun servicePending(action: String, req: Int): PendingIntent {
        val i = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this, req, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification(state: String) {
        val playing = state == "playing" || state == "buffering"

        val backAction = NotificationCompat.Action(
            android.R.drawable.ic_media_rew, "Back 15s",
            servicePending(ACTION_SKIP_BACK, 1)
        )
        val playPause = NotificationCompat.Action(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (playing) "Pause" else "Play",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                this, PlaybackStateCompat.ACTION_PLAY_PAUSE
            )
        )
        val fwdAction = NotificationCompat.Action(
            android.R.drawable.ic_media_ff, "Skip 30s",
            servicePending(ACTION_SKIP_FWD, 2)
        )

        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_podcast)
            .setContentTitle(if (title.isNotEmpty()) title else "Podcast")
            .setContentText(podcast)
            .setSubText(podcast)
            .setLargeIcon(artwork)
            .setContentIntent(pendingActivity())   // tap → open now-playing for this episode
            .setDeleteIntent(
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_STOP
                )
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(backAction)
            .addAction(playPause)
            .addAction(fwdAction)
            .setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()

        if (playing) {
            startForeground(NOTIF_ID, n)
        } else {
            // keep the notification visible (and swipeable) while paused
            stopForeground(false)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (state == "stopped" || state == "ended") nm.cancel(NOTIF_ID) else nm.notify(NOTIF_ID, n)
        }
    }

    // ---- artwork ---------------------------------------------------------

    private fun loadArtworkAsync() {
        val url = artworkUrl
        if (url.isBlank() || artwork != null) return
        Thread {
            try {
                val bmp = URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    artwork = bmp
                    handler.post {
                        if (player != null) {
                            updateSession(if (player?.isPlaying == true) "playing" else "paused")
                            updateNotification(if (player?.isPlaying == true) "playing" else "paused")
                        }
                    }
                }
            } catch (_: Exception) { /* artwork is best-effort */ }
        }.start()
    }

    private fun jsStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
