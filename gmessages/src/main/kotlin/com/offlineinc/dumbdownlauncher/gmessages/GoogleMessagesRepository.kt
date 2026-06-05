package com.offlineinc.dumbdownlauncher.gmessages

import android.content.Context
import com.offline.dpadmessenger.data.InMemoryMessageRepository
import com.offline.dpadmessenger.data.MessageRepository

/**
 * MessageRepository backed by Google Messages (RCS via the user's primary
 * Android phone, paired through Google's "Messages for web" protocol).
 *
 * **Implementation status: Phase A — mock only.**
 *
 * Phase A delivers the launcher → in-app messenger wiring with the existing
 * [InMemoryMessageRepository] standing in for the real backend, so the user
 * can see the chat UI when they tap "smart txt" on the launcher.
 *
 * Phase B will add [GoogleMessagesPairingClient] — QR-code-based pairing
 * with the primary phone, mirroring the shape of
 * `dpad-messenger-backend/signal/SignalProvisioningClient`. mautrix-signal
 * reference: `pkg/libgm/pairing.go`.
 *
 * Phase C will add the receive WebSocket (Google's instantmessaging-pa
 * pubsub relay) and the RPC layer for sending, mirroring
 * `SignalChatWebSocket` + `SignalSender`. mautrix reference:
 * `pkg/libgm/client.go` + `pkg/libgm/sending.go`.
 *
 * Until then [create] returns the in-memory mock so the rest of the
 * plumbing (Activity, intent wiring, focus handling) can be tested
 * end-to-end without any network dependency.
 */
object GoogleMessagesRepository {

    /**
     * Build a [MessageRepository] for use by [MessengerActivity].
     *
     * Phase A: returns [InMemoryMessageRepository.fake] — a tiny synthetic
     * repo (You + Alice + Bob, three demo messages, no asset file required).
     * Lets us verify the launcher → MessengerActivity hand-off works on real
     * hardware before we tackle the protocol port.
     *
     * Phase B+: replace with a real [GoogleMessagesMessageRepository] backed
     * by [GoogleMessagesAccountStore] / pairing tokens.
     */
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): MessageRepository {
        // TODO(gmessages-phase-b): if (GoogleMessagesAccountStore(context).isPaired())
        //   return RealGoogleMessagesMessageRepository(context, store.load()!!)
        return InMemoryMessageRepository.fake()
    }
}
