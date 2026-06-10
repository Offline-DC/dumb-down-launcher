package com.offlineinc.dumbdownlauncher.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.offlineinc.dumbdownlauncher.registration.DeviceRegistrar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Zips the on-device diag/ tree and uploads it to S3 via the backend's
 * presigned-URL flow. Triggered by the "submit logs" row in
 * [DiagnosticsActivity] (long-press "quack" in All Apps).
 *
 * Both diagnostics modules write into the same `<filesDir>/diag/` root
 * (see [DiagPaths] / [DiagnosticsPaths]), so one zip captures everything:
 *
 *   diag/
 *     manifest.json / samples.jsonl / events.jsonl / dumpsys/   ← battery analysis
 *     rolling-logcat/segment-*.log.gz + current.log             ← rolling adb logs
 *
 * Upload is two-step on purpose. Bundles are routinely 25–45 MB and the
 * backend lives on Heroku, whose router kills any request over 30 s —
 * a flip phone on LTE can't push that through the dyno. So:
 *
 *   1. POST /api/v1/diag-logs/upload-url { deviceId, sizeBytes }
 *        → { uploadUrl, key }
 *   2. PUT <zip> straight to S3 at uploadUrl (Content-Type: application/zip)
 *
 * Synchronous; callers run it on Dispatchers.IO. The temp zip lives in
 * cacheDir and is deleted in a finally block either way.
 */
internal object DiagLogUploader {

    private const val TAG = "DiagLogUploader"

    // Mirrors DeviceRegistrar.API_BASE.
    private const val API_BASE =
        "https://offline-dc-backend-ba4815b2bcc8.herokuapp.com/api/v1"

    private val JSON_TYPE = "application/json".toMediaType()
    private val ZIP_TYPE = "application/zip".toMediaType()

    /**
     * Generous ceilings tuned for a big zip over slow LTE. The presigned
     * URL itself is valid for 30 min server-side; callTimeout matches.
     * writeTimeout is per-socket-write, not per-call, so 60 s only trips
     * when the connection has actually stalled.
     */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.MINUTES)
        .build()

    /** Uploaded bundle's S3 key on success. */
    data class UploadResult(val key: String, val sizeBytes: Long)

    /**
     * Zip + upload. Returns the S3 key, or throws [IOException] with a
     * human-readable message the caller can surface in a Toast.
     */
    fun submit(context: Context): UploadResult {
        val diagRoot = DiagPaths.privateDiagDir(context)
        val zipFile = File(
            context.cacheDir,
            "diag-upload-${System.currentTimeMillis()}.zip",
        )
        try {
            val entryCount = zipDirectory(diagRoot, zipFile)
            if (entryCount == 0) {
                throw IOException("no logs collected yet")
            }
            val sizeBytes = zipFile.length()
            Log.i(TAG, "Bundle ready: $entryCount files, $sizeBytes bytes")

            val deviceId = resolveDeviceId(context)
            val (uploadUrl, key) = requestUploadUrl(deviceId, sizeBytes)
            putToS3(zipFile, uploadUrl)

            Log.i(TAG, "Uploaded $key ($sizeBytes bytes)")
            return UploadResult(key, sizeBytes)
        } finally {
            zipFile.delete()
        }
    }

    /**
     * IMEI when registration has cached one; ANDROID_ID otherwise (fresh
     * device, no SIM, …). Never null — the bundle must land somewhere
     * findable even if identity is degraded.
     */
    @SuppressLint("HardwareIds")
    private fun resolveDeviceId(context: Context): String {
        DeviceRegistrar.getCachedImei(context)?.let { return it }
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        )
        return if (androidId.isNullOrBlank()) "unknown" else "aid-$androidId"
    }

    /**
     * Streams every file under [root] into [dest] with paths relative to
     * the diag root (e.g. `rolling-logcat/segment-….log.gz`). Files that
     * vanish or fail mid-read (current.log is being actively written) are
     * skipped rather than failing the whole bundle.
     *
     * @return number of entries written.
     */
    private fun zipDirectory(root: File, dest: File): Int {
        var count = 0
        ZipOutputStream(FileOutputStream(dest).buffered()).use { zip ->
            root.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relPath = file.relativeTo(root).path
                    try {
                        zip.putNextEntry(ZipEntry(relPath))
                        FileInputStream(file).use { it.copyTo(zip) }
                        zip.closeEntry()
                        count++
                    } catch (t: Throwable) {
                        Log.w(TAG, "Skipping $relPath", t)
                    }
                }
        }
        return count
    }

    /** Step 1: ask the backend for a presigned PUT URL. */
    private fun requestUploadUrl(deviceId: String, sizeBytes: Long): Pair<String, String> {
        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("sizeBytes", sizeBytes)
        val request = Request.Builder()
            .url("$API_BASE/diag-logs/upload-url")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                Log.e(TAG, "upload-url failed: ${response.code} — $bodyStr")
                throw IOException("server refused upload (${response.code})")
            }
            val json = JSONObject(bodyStr)
            val uploadUrl = json.optString("uploadUrl")
            val key = json.optString("key")
            if (uploadUrl.isBlank() || key.isBlank()) {
                throw IOException("malformed upload-url response")
            }
            return uploadUrl to key
        }
    }

    /**
     * Step 2: PUT the zip straight to S3. Content-Type must match what
     * the URL was signed with (application/zip) or S3 rejects it.
     */
    private fun putToS3(zipFile: File, uploadUrl: String) {
        val request = Request.Builder()
            .url(uploadUrl)
            .put(zipFile.asRequestBody(ZIP_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "S3 PUT failed: ${response.code} — ${response.body?.string()?.take(500)}")
                throw IOException("upload failed (${response.code})")
            }
        }
    }
}
