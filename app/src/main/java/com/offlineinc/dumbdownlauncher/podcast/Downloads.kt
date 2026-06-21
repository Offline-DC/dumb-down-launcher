package com.offlineinc.dumbdownlauncher.podcast

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

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

    private fun dir(): File {
        val d = File(ctx.getExternalFilesDir(null), "podcast/episodes")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun safe(episodeId: String): String =
        episodeId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(120)

    private fun file(episodeId: String): File = File(dir(), safe(episodeId) + ".audio")

    /** Absolute path if a finished download exists, else "". */
    fun localPath(episodeId: String): String {
        val f = file(episodeId)
        return if (f.exists() && f.length() > 0) f.absolutePath else ""
    }

    fun enqueue(url: String, episodeId: String, title: String) {
        if (localPath(episodeId).isNotEmpty()) return            // already have it
        if (prefs.contains("id_$episodeId")) return              // already downloading
        val dest = Uri.fromFile(file(episodeId))
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(if (title.isNotEmpty()) title else "Episode")
            .setDescription("Podcast download")
            .setDestinationUri(dest)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        val id = dm.enqueue(req)
        prefs.edit().putLong("id_$episodeId", id).putString("eid_$id", episodeId).apply()
    }

    /** episodeId for a finished/failed downloadId (used by the completion receiver). */
    fun episodeForDownload(downloadId: Long): String? =
        prefs.getString("eid_$downloadId", null)

    /** JSON: {"state":"none|running|done|failed","percent":N} */
    fun statusJson(episodeId: String): String {
        if (localPath(episodeId).isNotEmpty()) return """{"state":"done","percent":100}"""
        val id = prefs.getLong("id_$episodeId", -1L)
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
                    DownloadManager.STATUS_SUCCESSFUL -> """{"state":"done","percent":100}"""
                    DownloadManager.STATUS_FAILED -> {
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
}
