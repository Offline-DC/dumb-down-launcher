package com.offlineinc.dumbdownlauncher.podcast

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Per-episode "download for offline" backed by the system DownloadManager.
 * Files land in the app's private external dir (no storage permission needed on
 * API 24+). A SharedPreferences map remembers episodeId -> downloadId so we can
 * report progress and clean up later. Streaming never touches this class — the
 * UI simply offers "download" alongside "stream", AntennaPod-style.
 */
class Downloads(private val ctx: Context) {

    private val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = ctx.getSharedPreferences("podcast_downloads", Context.MODE_PRIVATE)
    private val http by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)            // allow http<->https redirect hops
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun dir(): File {
        val d = File(ctx.getExternalFilesDir(null), "podcast/episodes")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun safe(episodeId: String): String =
        episodeId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(120)

    private fun file(episodeId: String): File = File(dir(), safe(episodeId) + ".audio")
    /** In-progress download target. DownloadManager writes partial bytes straight
     *  to its destination, so we download to ".part" and only rename to the real
     *  file on success — that way a half-finished download never looks complete
     *  (or plays as a corrupt local file). */
    private fun partFile(episodeId: String): File = File(dir(), safe(episodeId) + ".audio.part")

    /** Absolute path if a FINISHED download exists, else "". A still-downloading
     *  episode lives in [partFile] and intentionally returns "" here. */
    fun localPath(episodeId: String): String {
        val f = file(episodeId)
        return if (f.exists() && f.length() > 0) f.absolutePath else ""
    }

    /** Rename the completed .part to its final name. Idempotent. */
    private fun promote(episodeId: String): Boolean {
        val part = partFile(episodeId); val fin = file(episodeId)
        return when {
            fin.exists() && fin.length() > 0 -> { if (part.exists()) part.delete(); true }
            part.exists() -> part.renameTo(fin)
            else -> false
        }
    }

    fun enqueue(url: String, episodeId: String, title: String) {
        if (localPath(episodeId).isNotEmpty()) return            // already have it
        if (prefs.contains("id_$episodeId")) return              // already downloading/resolving
        // Claim the slot immediately so a double-tap doesn't enqueue twice while
        // we resolve redirects on the background thread. statusJson reports this
        // as "running" (see RESOLVING handling).
        prefs.edit().putLong("id_$episodeId", RESOLVING).apply()

        Thread {
            // DownloadManager won't follow podtrac/simplecast-style multi-hop or
            // cross-protocol (http<->https) redirects — it dies with
            // ERROR_TOO_MANY_REDIRECTS. Resolve the final media URL ourselves and
            // hand DownloadManager a direct link.
            val finalUrl = resolveFinalUrl(url) ?: url
            partFile(episodeId).delete()                 // clear any stale .part
            val dest = Uri.fromFile(partFile(episodeId))
            val req = DownloadManager.Request(Uri.parse(finalUrl))
                .setTitle(if (title.isNotEmpty()) title else "Episode")
                .setDescription("Podcast download")
                .setDestinationUri(dest)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            try {
                val id = dm.enqueue(req)
                prefs.edit().putLong("id_$episodeId", id).putString("eid_$id", episodeId).apply()
                Log.i(TAG, "enqueue id=$id eid=$episodeId final=$finalUrl orig=$url")
            } catch (e: Exception) {
                prefs.edit().remove("id_$episodeId").apply()   // release the slot
                Log.w(TAG, "enqueue failed eid=$episodeId url=$finalUrl", e)
            }
        }.start()
    }

    /** Follow redirects to the final media URL. Tries a cheap HEAD first, then a
     *  1-byte ranged GET for servers that reject HEAD. Returns null on failure
     *  (caller falls back to the original URL). */
    private fun resolveFinalUrl(url: String): String? {
        fun attempt(method: String): String? = try {
            val b = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (podcast)")
            if (method == "HEAD") b.head() else b.get().header("Range", "bytes=0-0")
            http.newCall(b.build()).execute().use { resp ->
                if (resp.code in 200..399) resp.request.url.toString() else null
            }
        } catch (e: Exception) { Log.w(TAG, "resolve ($method) failed for $url", e); null }
        return attempt("HEAD") ?: attempt("GET")
    }

    /** episodeId for a finished/failed downloadId (used by the completion receiver). */
    fun episodeForDownload(downloadId: Long): String? =
        prefs.getString("eid_$downloadId", null)

    /** JSON: {"state":"none|running|done|failed","percent":N} */
    fun statusJson(episodeId: String): String {
        if (localPath(episodeId).isNotEmpty()) return """{"state":"done","percent":100}"""
        val id = prefs.getLong("id_$episodeId", -1L)
        if (id == RESOLVING) return """{"state":"running","percent":0}"""   // resolving redirects
        if (id < 0) return """{"state":"none","percent":0}"""
        val q = DownloadManager.Query().setFilterById(id)
        val c = dm.query(q) ?: return """{"state":"none","percent":0}"""
        c.use {
            if (it.moveToFirst()) {
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val soFar = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val pct = if (total > 0) ((soFar * 100) / total).toInt() else 0
                return when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        if (!promote(episodeId)) Log.w(TAG, "promote failed eid=$episodeId")
                        """{"state":"done","percent":100}"""
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        Log.w(TAG, "download FAILED eid=$episodeId id=$id " +
                            "reason=$reason (${reasonText(reason)}) bytes=$soFar/$total")
                        partFile(episodeId).delete()
                        clearRecord(episodeId, id); """{"state":"failed","percent":0}"""
                    }
                    else -> """{"state":"running","percent":$pct}"""
                }
            }
        }
        return """{"state":"none","percent":0}"""
    }

    fun delete(episodeId: String) {
        val id = prefs.getLong("id_$episodeId", -1L)
        if (id >= 0) { try { dm.remove(id) } catch (_: Exception) {} }
        try { file(episodeId).delete() } catch (_: Exception) {}
        try { partFile(episodeId).delete() } catch (_: Exception) {}
        clearRecord(episodeId, id)
    }

    /** Drop the id mapping once a download finishes; the file is now the record. */
    fun finalize(episodeId: String) {
        val id = prefs.getLong("id_$episodeId", -1L)
        prefs.edit().remove("id_$episodeId").remove("eid_$id").apply()
    }

    private fun clearRecord(episodeId: String, id: Long) {
        prefs.edit().remove("id_$episodeId").remove("eid_$id").apply()
    }

    /** Human-readable DownloadManager COLUMN_REASON. For ERROR_UNHANDLED_HTTP_CODE
     *  the reason is the raw HTTP status (e.g. 403); the 1000-range values are the
     *  DownloadManager.ERROR_* constants. */
    private fun reasonText(reason: Int): String = when (reason) {
        DownloadManager.ERROR_CANNOT_RESUME -> "CANNOT_RESUME"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "DEVICE_NOT_FOUND (no external storage)"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "FILE_ALREADY_EXISTS"
        DownloadManager.ERROR_FILE_ERROR -> "FILE_ERROR"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP_DATA_ERROR"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "INSUFFICIENT_SPACE"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "TOO_MANY_REDIRECTS"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "UNHANDLED_HTTP_CODE"
        DownloadManager.ERROR_UNKNOWN -> "UNKNOWN"
        in 400..599 -> "HTTP_$reason"
        else -> "code=$reason"
    }

    companion object {
        private const val TAG = "PodcastDL"
        /** Sentinel id stored while we resolve redirects, before the real
         *  DownloadManager id exists. */
        private const val RESOLVING = -2L
    }
}
