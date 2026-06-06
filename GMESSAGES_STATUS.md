# Google Messages integration — status & next steps

Goal: "smart txt" on the launcher opens an in-app Google Messages client,
paired to the user's primary Android phone via QR (like the Signal link
flow), using Google's "Messages for web" relay protocol. Protocol reference
throughout: mautrix-gmessages (`pkg/libgm/`).

## Repo layout (required)

    ~/repos/dumb-down-launcher          this repo (:app + :gmessages)
    ~/repos/matrix-app/dpad-messenger   shared chat UI lib (composite build)
    ~/repos/matrix-app/dpad-messenger-backend  signal/matrix backends

Launcher's settings.gradle.kts does `includeBuild("../matrix-app/dpad-messenger")`.

## What's done (all in :gmessages unless noted)

1. **Dropped libsignal** (broke D8 dexing). X25519 is now in-repo
   (`X25519.kt`, TweetNaCl port, RFC 7748 test vectors in `X25519Test`).
   All desugaring/dexing workarounds reverted (build.gradle.kts ×2,
   gradle.properties).
2. **Pairing protocol implemented** — real, not stubbed:
   - `Protobuf.kt` — minimal protobuf wire reader/writer (no codegen).
   - `GMPairingProto.kt` — RegisterPhoneRelay request/response, URLData (QR
     payload), PairedData decode. Byte-identical to protoc (tests).
   - `PbLite.kt` — Google's pblite (JSON-array protobuf): ReceiveMessages
     request builder, streaming `[[ payload, … ]]` splitter, pair-event
     extractor. Unit-tested incl. against a real captured frame shape.
   - `GoogleMessagesPairingClient.kt` — ECDSA P-256 identity key + AES/HMAC
     QR keys → RegisterPhoneRelay → scannable QR → ReceiveMessages long-poll
     (retry loop, heavy logcat under tag `GMPairing`) → decode PairedData →
     persist → state `Paired`.
   - `GoogleMessagesAccountStore.kt` — EncryptedSharedPreferences: tachyon
     token, browser/mobile source IDs, ECDSA priv (PKCS#8), AES/HMAC keys.
   - `GoogleMessagesPairing.kt` — process-scoped singleton so the long-poll
     survives backgrounding/Activity recreation while user scans.
3. **UI** — `ui/GoogleMessagesApp.kt` gates: unpaired → `GoogleMessagesLinkScreen`
   (QR via `ui/QrCode.kt`, ZXing); paired → `DpadMessengerApp` (currently
   backed by the mock `InMemoryMessageRepository`). `MessengerActivity` (:app)
   is a thin shell around `GoogleMessagesApp()`.

## Debug history (why the code looks how it does)

- Long-poll 400 → ReceiveMessages needs pblite, not binary proto.
- 200 + silence → AuthMessage.network must be EMPTY for the receive call
  (only RegisterPhoneRelay sends "Bugle").
- Pair event arrived but never decoded → stream is wrapped in DOUBLE
  brackets `[[ … ]]`; splitter now consumes the wrapper. **This fix is
  built but was not yet re-tested on device.**

## Current step — verify pairing completes

```
./gradlew :app:installDebug
adb logcat -c && adb logcat -s GMPairing
```
Open smart txt, scan with primary phone. Expect: `long-poll element #N …`
then `paired ✔ mobile=… browser=…`, screen swaps to chat UI (mock data).
If decode fails, the element is logged in full — fix offsets in
`PbLite.extractPairedResult` / `GMPairingProto.parsePairedData`.

To re-test pairing from scratch: unpair on the phone (Settings → Device
pairing) and `adb shell pm clear com.offlineinc.dumbdownlauncher` (wipes the
account store).

## Real messages — IMPLEMENTED (needs on-device validation)

`GoogleMessagesRepository.create()` now returns a real
`GoogleMessagesMessageRepository` (backed by `GoogleMessagesSessionClient`)
whenever a current-schema pairing exists. The mock is only a fallback. The
conversation list starts **empty** and fills from the phone.

New files (all in :gmessages):
- `GMSessionProto.kt` — session wire formats: OutgoingRPCMessage / OutgoingRPCData
  / AckMessageRequest / RegisterRefreshRequest envelopes (pblite), plus binary
  parsers for RPCMessageData, UpdateEvents, Conversation, Message, Participant,
  List{Conversations,Messages}Response, SendMessageResponse. Field numbers from
  mautrix `rpc/client/conversations/events.proto`.
- `GoogleMessagesSessionClient.kt` — the live session. Authenticated
  ReceiveMessages long-poll (reconnect loop) → decrypt → emit
  `SessionEvent.{ConversationsUpdated,MessagesUpdated,AuthExpired}`. Sends
  session RPCs over `Messaging/SendMessage`, batches acks every 5s
  (`Messaging/AckMessages`), refreshes the tachyon token via
  `Registration/RegisterRefresh` (ECDSA-signed, ~1h before expiry).
- `GoogleMessagesMessageRepository.kt` — maps GM protos → dpad-messenger UI
  models (Conversation→Room, Message→Message µs→ms, Participant→User).
  Optimistic outgoing sends; de-dups the delivered echo by tmpID/messageID.

Crypto: `GMCrypto.encryptPayload` / `decryptPayload` —
`ciphertext || IV(16) || HMAC-SHA256(32)`, HMAC over ciphertext+IV
(mautrix `crypto/aesctr.go`). Verified by unit tests.

Account store bumped to **schema v2** (now persists full Device identities +
token TTL, needed for session RPCs). `load()` returns null for v1 pairings, so
**anyone paired before this change must re-pair** (the UI will show the QR
again automatically).

### What to test on device
```
./gradlew :gmessages:testDebugUnitTest   # 30 tests: protos + crypto + pblite
./gradlew :app:installDebug
adb logcat -c && adb logcat -s GMSession:* GMRepo:* GMPairing:*
```
Open smart txt (already paired). Expect:
- `session long-poll #1 open`, then conversations populate the (initially
  empty) list as the phone pushes state / responds to LIST_CONVERSATIONS.
- Open a thread → recent messages appear; send a reply → optimistic bubble
  flips SENDING→SENT and the phone's echo replaces it.
- Incoming texts arrive live while the screen is open.

If conversations don't appear: check the decrypt path — log the first
`DataEvent` element; a decrypt failure means the AES/HMAC keys or the
`ciphertext||IV||HMAC` layout is off. If sends 400, inspect the
`OutgoingRPCMessage` envelope (most likely the Device/auth indices).

### Also implemented since
- **Replies** end-to-end (SendMessageRequest.reply outbound, replyMessage
  field 21 inbound → quote headers).
- **Reactions** end-to-end: SEND_REACTION (action 38) with the mautrix
  emoji↔EmojiType mapping; Message.reactions (field 19) parsed inbound;
  optimistic toggle in the repository ("me" = conversation
  defaultOutgoingID).
- **Notifications**: incoming texts post MessagingStyle notifications
  (suppressed for backfill + the on-screen thread).
- **Foreground service** (`GoogleMessagesForegroundService`, :gmessages
  manifest): the session/repository is a process singleton shared by UI +
  service; the service pins the process with a MIN-importance "Connected"
  notification so texts notify with the UI fully closed. Started from
  DumbDownApp.onCreate (covers boot) and on messenger open/pair.

### Not yet done
Edits, deletes, read receipts beyond mark-read, media send/download, and
LIST_MESSAGES cursor pagination (`loadOlder` fires a fixed-count refetch,
returns no-more). Tombstone/system rows are skipped.

## Test commands

```
./gradlew :gmessages:testDebugUnitTest   # X25519 + protobuf + pblite + session + crypto
```
