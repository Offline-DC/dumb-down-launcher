package com.offlineinc.dumbdownlauncher.contactsync.sync

import android.content.Context
import android.util.Log
import com.offlineinc.dumbdownlauncher.BuildConfig
import com.offlineinc.dumbdownlauncher.contactsync.icloud.AndroidContactsReader
import com.offlineinc.dumbdownlauncher.contactsync.icloud.AndroidContactsUpserter
import com.offlineinc.dumbdownlauncher.contactsync.icloud.VCardMini
import com.offlineinc.dumbdownlauncher.contactsync.icloud.VCardMiniParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.WebSocket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ContactSyncRepository(
    private val context: Context,
    private val apiClient: ContactSyncApiClient,
    private val store: ContactSyncStore
) {

    companion object {
        private const val TAG = "ContactSyncRepo"
    }

    // Shared state between connect and sync phases
    private var connectedWs: WebSocket? = null
    /** WebSocket that has been opened but hasn't received both_ready yet.
     *  Tracked so disconnectWebSocket() can close it — without this,
     *  navigating away before both_ready leaks a zombie WebSocket. */
    private var pendingWs: WebSocket? = null
    private var peerCompleteLatch: CompletableDeferred<Unit>? = null

    /** True if a WebSocket is currently connected and ready for sync. */
    val isWebSocketConnected: Boolean get() = connectedWs != null

    /**
     * Phase 1: Connect WebSocket and wait for both_ready (or peer_sync_complete).
     * Call this first, then syncWithConnectedWebSocket() after.
     *
     * If the peer disconnects after ready, the connection is kept alive because
     * upload/download are HTTP — sync can proceed without a live peer WebSocket.
     */
    suspend fun connectAndWaitForReady() {
        val phoneNumber = store.flipPhoneNumber ?: throw IllegalStateException("Not paired")
        Log.i(TAG, "[ContactSync] connectAndWaitForReady: phone=$phoneNumber, timeout=10min")

        val latch = CompletableDeferred<Unit>()
        peerCompleteLatch = latch

        val ws = withTimeout(10 * 60 * 1000L) {
            suspendCancellableCoroutine { cont ->
                var wsRef: WebSocket? = null
                pendingWs = null  // clear any stale ref
                wsRef = apiClient.connectSyncWebSocket(
                    flipPhoneNumber = phoneNumber,
                    onBothReady = {
                        Log.i(TAG, "[ContactSync] WS callback: both_ready received")
                        if (cont.isActive) cont.resume(wsRef!!)
                    },
                    onPeerComplete = {
                        Log.i(TAG, "[ContactSync] WS callback: peer_sync_complete received — unblocking download")
                        latch.complete(Unit)
                        // If we haven't received both_ready yet, peer_sync_complete
                        // implies both were connected (peer can't sync without connecting).
                        // Resume the coroutine so we don't wait forever for a both_ready
                        // that the server won't re-send on a reconnection.
                        if (cont.isActive) {
                            Log.i(TAG, "[ContactSync] WS callback: peer_sync_complete arrived before both_ready — treating as implicit ready")
                            cont.resume(wsRef!!)
                        }
                    },
                    onPeerDisconnected = {
                        Log.e(TAG, "[ContactSync] WS callback: peer_disconnected — smart phone left")
                        if (cont.isActive) {
                            cont.resumeWithException(Exception("Smart phone disconnected"))
                        } else {
                            // both_ready already fired — peer left after connection was established.
                            // DON'T reset to connecting: the peer's data is on the server and
                            // upload/download are HTTP calls, so sync can still proceed.
                            // Just complete the peer latch so step 3 doesn't wait 30s.
                            Log.w(TAG, "[ContactSync] peer disconnected after both_ready — keeping connection (sync still possible)")
                            peerCompleteLatch?.complete(Unit)
                        }
                    },
                    onError = { msg ->
                        Log.e(TAG, "[ContactSync] WS callback: error — $msg")
                        if (cont.isActive) cont.resumeWithException(Exception(msg))
                    }
                )
                pendingWs = wsRef  // track so disconnectWebSocket() can close it pre-both_ready
                cont.invokeOnCancellation {
                    Log.w(TAG, "[ContactSync] WS: cancelled (timeout?)")
                    wsRef?.close(1000, "cancelled")
                    pendingWs = null
                }
            }
        }

        connectedWs = ws
        pendingWs = null  // promoted to connectedWs — no longer pending
        Log.i(TAG, "[ContactSync] connectAndWaitForReady: both devices connected")
    }

    /**
     * Phase 2: Run actual sync using the already-connected WebSocket.
     * Must call connectAndWaitForReady() first.
     */
    suspend fun syncWithConnectedWebSocket(onProgress: (Int) -> Unit, onCanClose: (() -> Unit)? = null) {
        val ws = connectedWs ?: throw IllegalStateException("WebSocket not connected — call connectAndWaitForReady first")
        val phoneNumber = store.flipPhoneNumber ?: throw IllegalStateException("Not paired")
        val secret = store.sharedSecret ?: throw IllegalStateException("Not paired")
        val latch = peerCompleteLatch ?: CompletableDeferred()

        Log.i(TAG, "[ContactSync] syncWithConnectedWebSocket: starting for phone=$phoneNumber")

        try {
            // Step 1: Upload our contacts
            Log.i(TAG, "[ContactSync] sync: step 1 — uploading flip contacts")
            uploadFlipContacts(phoneNumber, secret)
            Log.i(TAG, "[ContactSync] sync: upload complete")

            // Step 2: Signal peer that we've finished uploading
            Log.i(TAG, "[ContactSync] sync: step 2 — sending sync_complete")
            val completeMsg = org.json.JSONObject().put("type", "sync_complete")
            ws.send(completeMsg.toString())

            // Step 3: Wait for peer to finish uploading (30s timeout)
            Log.i(TAG, "[ContactSync] sync: step 3 — waiting for peer to finish uploading")
            val peerDone = withTimeoutOrNull(30_000) { latch.await() }
            if (peerDone == null) {
                Log.w(TAG, "[ContactSync] sync: peer signal timed out — proceeding anyway")
            } else {
                Log.i(TAG, "[ContactSync] sync: peer signal received — proceeding to download")
            }

            // Step 4: Download smart contacts — onCanClose fires inside once
            // contacts have been received and are being written to the phone
            Log.i(TAG, "[ContactSync] sync: step 4 — downloading smart phone contacts")
            downloadAndImportSmartContacts(phoneNumber, secret, onProgress, onCanClose)
            Log.i(TAG, "[ContactSync] sync: download complete")

            store.lastSyncMillis = System.currentTimeMillis()
            Log.i(TAG, "[ContactSync] sync: SUCCESS — all steps complete")
        } catch (e: Exception) {
            Log.e(TAG, "[ContactSync] sync: FAILED", e)
            throw e
        } finally {
            ws.close(1000, "done")
            connectedWs = null
            peerCompleteLatch = null
            Log.i(TAG, "[ContactSync] sync: WebSocket closed")
        }
    }

    fun disconnectWebSocket() {
        pendingWs?.close(1000, "cancelled")
        pendingWs = null
        connectedWs?.close(1000, "cancelled")
        connectedWs = null
        peerCompleteLatch = null
    }

    private fun uploadFlipContacts(phoneNumber: String, secret: String) {
        Log.i(TAG, "[ContactSync] uploadFlipContacts: reading native contacts")
        val contacts = AndroidContactsReader.readNativeContacts(context)
        Log.i(TAG, "[ContactSync] uploadFlipContacts: read ${contacts.size} native contacts")

        val vcf = VcfNormalizer.normalize(contacts)
        val vcfSize = vcf.toByteArray().size
        val contentHash = CryptoUtil.sha256Hex(vcf.toByteArray())
        Log.i(TAG, "[ContactSync] uploadFlipContacts: VCF size=$vcfSize bytes, hash=${contentHash.take(12)}...")

        val version = BuildConfig.VERSION_NAME

        if (contentHash == store.lastFlipHash) {
            Log.i(TAG, "[ContactSync] uploadFlipContacts: hash unchanged — uploading metadata only")
            apiClient.upload(phoneNumber, "flip", "", "", contentHash, contacts.size, secret, version)
            store.flipContactCount = contacts.size
            return
        }

        Log.i(TAG, "[ContactSync] uploadFlipContacts: hash changed — encrypting + uploading full VCF")
        val (ciphertext, iv) = CryptoUtil.encryptAesGcm(vcf.toByteArray(), secret)
        Log.i(TAG, "[ContactSync] uploadFlipContacts: encrypted — ciphertext=${ciphertext.size} bytes, iv=${iv.size} bytes")

        val result = apiClient.upload(
            phoneNumber, "flip",
            CryptoUtil.toBase64(ciphertext),
            CryptoUtil.toBase64(iv),
            contentHash,
            contacts.size,
            secret,
            version
        )

        store.lastFlipHash = contentHash
        store.flipContactCount = contacts.size
        Log.i(TAG, "[ContactSync] uploadFlipContacts: success — hashChanged=${result.optBoolean("hashChanged")}, count=${contacts.size}")
    }

    private fun downloadAndImportSmartContacts(phoneNumber: String, secret: String, onProgress: (Int) -> Unit, onCanClose: (() -> Unit)? = null) {
        val hashToSend = if (store.lastSmartHash == null) null else store.lastSmartHash
        Log.i(TAG, "[ContactSync] downloadSmartContacts: lastSmartHash=${store.lastSmartHash?.take(12) ?: "nil"}, sending=${hashToSend?.take(12) ?: "nil"}")
        val result = apiClient.download(phoneNumber, "flip", hashToSend, secret)

        if (!result.optBoolean("hasChanges", false)) {
            Log.i(TAG, "[ContactSync] downloadSmartContacts: no changes from smart phone")
            // No changes needed — safe to signal canClose since download check is done
            onCanClose?.invoke()
            return
        }

        val encryptedVcf = result.optString("encryptedVcf", "")
        val iv = result.optString("iv", "")
        val contentHash = result.optString("contentHash", "")
        val contactCount = result.optInt("contactCount", 0)
        Log.i(TAG, "[ContactSync] downloadSmartContacts: has changes — hash=${contentHash.take(12)}..., contacts=$contactCount, vcfLen=${encryptedVcf.length}")

        if (encryptedVcf.isBlank()) {
            Log.w(TAG, "[ContactSync] downloadSmartContacts: encryptedVcf is blank — skipping import")
            onCanClose?.invoke()
            return
        }

        Log.i(TAG, "[ContactSync] downloadSmartContacts: decrypting VCF")
        val vcfBytes = CryptoUtil.decryptAesGcm(
            CryptoUtil.fromBase64(encryptedVcf),
            CryptoUtil.fromBase64(iv),
            secret
        )
        val vcfString = String(vcfBytes)
        Log.i(TAG, "[ContactSync] downloadSmartContacts: decrypted — ${vcfBytes.size} bytes plaintext")

        val vcards = parseMultipleVCards(vcfString)
        Log.i(TAG, "[ContactSync] downloadSmartContacts: parsed ${vcards.size} VCards")

        if (contentHash.isNotBlank() && contentHash == store.lastSmartHash) {
            Log.i(TAG, "[ContactSync] downloadSmartContacts: contentHash unchanged client-side — skipping")
            onCanClose?.invoke()
            return
        }

        // Contacts have been downloaded and decrypted — now writing to phone.
        // Signal canClose: contacts are physically being added to the flip phone.
        Log.i(TAG, "[ContactSync] downloadSmartContacts: starting import — signalling canClose")
        onCanClose?.invoke()

        // Build stable source IDs based on name+first-phone rather than random UIDs
        val keepSourceIds = mutableSetOf<String>()
        var skipped = 0
        var written = 0
        var adopted = 0
        for ((index, card) in vcards.withIndex()) {
            val stableKey = buildStableSourceId(card) ?: continue
            val sourceId = "smart:$stableKey"
            keepSourceIds.add(sourceId)

            val newHash = contactHash(card)
            if (store.getContactHash(sourceId) == newHash) {
                skipped++
            } else {
                val existingBySourceId = AndroidContactsUpserter.findRawContactIdBySourceIdPublic(context, sourceId)
                if (existingBySourceId != null) {
                    AndroidContactsUpserter.upsertByRawId(context, existingBySourceId, card)
                    store.setContactHash(sourceId, newHash)
                } else {
                    val existingNativeId = AndroidContactsUpserter.findByNameAndPhone(
                        context, card.fn ?: "", card.phones
                    )
                    if (existingNativeId != null) {
                        AndroidContactsUpserter.setSourceId(context, existingNativeId, sourceId)
                        AndroidContactsUpserter.upsertByRawId(context, existingNativeId, card)
                        store.setContactHash(sourceId, newHash)
                        adopted++
                    } else {
                        AndroidContactsUpserter.upsert(context, sourceId, card)
                        store.setContactHash(sourceId, newHash)
                    }
                }
                written++
            }

            if ((index + 1) % 10 == 0 || index == vcards.lastIndex) {
                onProgress(index + 1)
            }
        }
        Log.i(TAG, "[ContactSync] downloadSmartContacts: written=$written (adopted=$adopted) skipped=$skipped (unchanged) total=${keepSourceIds.size}")

        AndroidContactsUpserter.deleteMissing(context, keepSourceIds)
        store.clearContactHashesNotIn(keepSourceIds)
        Log.i(TAG, "[ContactSync] downloadSmartContacts: deleteMissing called — keeping ${keepSourceIds.size} source IDs")

        store.lastSmartHash = contentHash
        store.smartContactCount = contactCount
        Log.i(TAG, "[ContactSync] downloadSmartContacts: stored hash + count=$contactCount")
    }

    private fun buildStableSourceId(card: VCardMini): String? {
        val name = card.fn?.trim()?.lowercase() ?: ""
        val phone = card.phones.firstOrNull()?.filter { it.isDigit() }?.takeLast(10) ?: ""
        val email = if (phone.isEmpty()) card.emails.firstOrNull()?.trim()?.lowercase() ?: "" else ""
        if (name.isEmpty() && phone.isEmpty() && email.isEmpty()) return null
        return if (phone.isNotEmpty()) "$name|$phone" else "$name|e:$email"
    }

    private fun contactHash(card: VCardMini): String =
        (card.fn.orEmpty() + card.phones.sorted().joinToString() + card.emails.sorted().joinToString())
            .hashCode().toString()

    private fun parseMultipleVCards(vcf: String): List<VCardMini> {
        val cards = mutableListOf<VCardMini>()
        val regex = Regex("BEGIN:VCARD.*?END:VCARD", RegexOption.DOT_MATCHES_ALL)
        for (match in regex.findAll(vcf)) {
            val card = VCardMiniParser.parse(match.value)
            if (card.fn != null || card.phones.isNotEmpty() || card.emails.isNotEmpty()) {
                cards.add(card)
            }
        }
        return cards
    }
}
