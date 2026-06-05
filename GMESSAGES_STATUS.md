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

## Next phase — real messages (not started)

Replace `InMemoryMessageRepository` in `GoogleMessagesRepository.create()`
with a real repo speaking the authenticated session:
1. Long-poll with the PAIRED token (from account store); handle ack counts.
2. Session RPCs over `Messaging/SendMessage` (pblite `OutgoingRPCMessage`):
   LIST_CONVERSATIONS / LIST_MESSAGES / SEND_MESSAGE / MESSAGE_UPDATES.
   Payloads encrypted AES-256-CTR + HMAC-SHA256 with the stored QR keys —
   exact format in `GMCrypto` docs + mautrix `crypto/aesctr.go`
   (ciphertext || IV(16) || HMAC(32), HMAC over ciphertext+IV).
3. Token refresh via `Registration/RegisterRefresh` (needs ECDSA signature —
   key is in the account store; see mautrix `client.go refreshAuthToken`).
4. Map conversations/messages protos (`conversations.proto` in
   mautrix-gmessages) into `MessageRepository`.

## Test commands

```
./gradlew :gmessages:testDebugUnitTest   # X25519 + protobuf + pblite vectors
```
