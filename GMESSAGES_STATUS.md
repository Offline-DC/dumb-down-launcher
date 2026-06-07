# Google Messages integration — status & next steps

> **CURRENT STATUS (working):** Pairing now uses **Google-account / cookie
> (GAIA) auth**, not QR. Google removed QR pairing for Messages-for-web, so the
> user signs into Google on the companion smartphone, the cookies are sent to
> the flip phone over the encrypted Type Sync relay, and the flip phone does the
> SignInGaia + UKey2 emoji-match pairing itself. **Validated end-to-end on
> device.** Full design + protocol details:
> [`GMESSAGES_GAIA_PORT.md`](./GMESSAGES_GAIA_PORT.md). The QR pairing path
> (`GoogleMessagesPairing*`, `GoogleMessagesLinkScreen`, `QrCode`) has been
> deleted; the `X25519` / QR-only `GMCrypto`/`GMPairingProto` primitives remain
> unused (harmless). The notes below describe the original QR implementation and
> the shared chat/session machinery (still in use).

Goal: "smart txt" on the launcher opens an in-app Google Messages client,
paired to the user's primary Android phone, using Google's "Messages for web"
relay protocol. (Pairing was originally QR-based — see the banner above for the
move to GAIA cookie auth.) Protocol reference throughout: mautrix-gmessages
(`pkg/libgm/`).

## Repo layout (required)

    ~/repos/dumb-down-launcher          this repo (:app only now)
    ~/repos/matrix-app/dpad-messenger-backend  signal/matrix + :gmessages backends
    ~/repos/matrix-app/dpad-messenger   shared chat UI lib (composite build)

The Google Messages backend **and its pairing/chat UI moved out of this repo**
into `matrix-app/dpad-messenger-backend` as the `:gmessages` module (package
`com.offline.dpadmessenger.backend.gmessages`), so the matrix/non-launcher
code is shared rather than launcher-only. The launcher now keeps just the
thin `MessengerActivity` shell and plugs into it.

Launcher's settings.gradle.kts does
`includeBuild("../matrix-app/dpad-messenger-backend")` and substitutes
`com.offline.dpadmessenger.backend:gmessages` → that build's `:gmessages`
project. The backend build in turn composite-includes `../dpad-messenger`
(the UI lib), so the launcher gets it transitively. The host app names its
messenger Activity to the backend via
`GoogleMessagesConfig.messengerActivityClassName` (set in `DumbDownApp`).

Section paths below say "in :gmessages" — that module is now in
`dpad-messenger-backend`, and all `./gradlew :gmessages:…` commands run from
that repo root (not this one).

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
Edits, deletes, read receipts beyond mark-read, and LIST_MESSAGES cursor
pagination (`loadOlder` fires a fixed-count refetch, returns no-more).
Tombstone/system rows are skipped. (Media **send + download** are now done —
see the UI/UX section below.)

## UI/UX + platform fixes (June 2026)

These all live in the shared UI lib (`matrix-app/dpad-messenger`) unless noted;
the launcher picks them up through the `:gmessages` composite build.

**Build / environment**
- The composite build (`launcher → dpad-messenger-backend → dpad-messenger`)
  needs an `sdk.dir` in **each** included build's `local.properties` — Gradle
  doesn't inherit it across `includeBuild`. A missing
  `dpad-messenger-backend/local.properties` surfaced as a misleading
  `D8BackportedMethodsGenerator … Could not isolate parameters` error; the real
  cause is the AGP plugin failing to find the SDK. Each repo's
  `local.properties` is gitignored (machine-specific).

**Reading long messages (DPAD)**
- A message bubble taller than the viewport can now be read top-to-bottom:
  while it's focused, DPAD Up/Down scrolls the conversation a chunk at a time to
  reveal the clipped top/bottom *before* focus moves to the next bubble. Geometry
  comes from each bubble's window bounds vs the list viewport; scrolling uses the
  bubble's `BringIntoViewRequester` (sign-correct under `reverseLayout`).
  `MessageBubble` + `ChatScreen.Timeline`.

**Sending media (DPAD picker)**
- The composer "+" no longer opens the system Photos picker (not DPAD-navigable
  on the Flip 2). `MediaPickerScreen` is a self-contained, DPAD-navigable grid of
  recent photos/videos from MediaStore; OK sends the picked `content://` uri via
  the existing `AttachmentSender`. Runtime `READ_MEDIA_IMAGES/VIDEO` (33+) /
  `READ_EXTERNAL_STORAGE` (≤32) declared in the lib manifest.
- **Upload 401 fix:** `startMediaUploadRequest` serialized the int64 Device
  `userId` with `int32(userId.toInt())`, truncating the (large) Google account id
  and getting HTTP 401 on upload-start. Now writes the full `varint(1, userId)`.
  This was the only binary-proto Device serialization; all RPC paths use pblite
  `deviceJson` (full Long) and were unaffected. `GMSessionProto`.

**HEIC + media placeholders**
- Shared `decodeDownscaled()` (`ui/util/ImageDecode.kt`) tries the platform
  `ImageDecoder` first (covers HEIF/HEIC, the format iPhones send) and falls back
  to `BitmapFactory`. Used by the in-bubble thumbnail and the fullscreen viewer.
- Decode failures now show "Can't preview this photo" instead of an endless
  spinner (covers devices whose codec can't handle a given HEIC).
- Copy: downloading shows "Loading media…", idle shows "Tap to view photo",
  the room-list preview shows a typed label ("📷 Photo" / "🎥 Video" /
  "📎 Attachment") for media-only messages, and the backend's bare "[media]"
  fallback became "📎 Attachment". A diagnostic log fires when a media message
  produces no attachment (parse problem) to distinguish it from a decode problem.

**Cover-display notifications**
- The flip phone's external/cover display reads the legacy `contentTitle` /
  `contentText` extras, which a `MessagingStyle`-only notification doesn't set —
  so our texts showed on the main display but not the cover, unlike the stock SMS
  app (which sets them). `GoogleMessagesNotifier` now also sets contentTitle/text,
  `setWhen`/`setShowWhen`, explicit `VISIBILITY_PUBLIC`, and `DEFAULT_ALL`.
- **Waking the display:** a high-importance notification only peeks when the
  screen is already on — it won't wake a sleeping cover display. `wakeScreen()`
  briefly acquires a `FULL_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` lock (auto-releases
  after 5s, no-op if already interactive) so a new text lights the display like
  the stock SMS app. Needs `WAKE_LOCK` (added to the :gmessages manifest).

**Unread badge**
- The unread count looked ~1px right of centre (Roboto digit side-bearing).
  Added `letterSpacing=0` + a tunable optical x-nudge (`OPTICAL_NUDGE`).

**Fullscreen viewer**
- Closing the photo/video viewer returns DPAD focus to the bubble whose media
  was opened (falls back to the composer if that bubble was recycled), instead
  of always jumping to the composer.

**Staying linked / re-link UX**
- Outgoing RPCs (text send, contacts, etc.) are all tunneled over
  `Messaging/SendMessage`; a `401 "missing required authentication credential"`
  there means the tachyon session token was rejected (send + the long-poll
  receive share identical transport headers, so it's the token, not a header).
- **Resilience:** the long-poll now does one forced `refreshToken()` + reconnect
  on a 401/403 before giving up — recovers a token that lapsed while the app was
  backgrounded so users stay linked across expiry. `refreshToken()` was split out
  of `refreshTokenIfNeeded()` and persists the new token.
- **Surfaced in-app:** if refresh can't recover it, `SessionEvent.AuthExpired`
  sets `GoogleMessagesMessageRepository.authExpired`, and `GoogleMessagesApp`
  swaps the chat for `GoogleMessagesReconnectScreen` (a DPAD "Re-link phone"
  button → teardown → QR). Failed sends now show a **red bold "!"**.
- Contacts already fall back to local device contacts; the GM contact RPC is
  enrichment that needs a live session.

## Group chat creation (DPAD)

- New `GroupConversationStarter` capability interface
  (`data/ConversationStarter.kt`). The new-message screen
  (`NewConversationScreen`) gains a DPAD-friendly multi-select "New group" mode:
  a top "New group" row enters group mode; OK on contact rows toggles selection
  (round check ↔ filled check); the top row becomes "Create group (N)" and fires
  when ≥2 are picked. Back exits group mode first. Wired through
  `MessengerNavigation` (cast repo to `GroupConversationStarter`).
- gmessages backend implements it: `getOrCreateConversationRequest` now writes
  repeated `numbers` + `createRCSGroup=true` (mautrix wire shape);
  `session.getOrCreateConversation(numbers, groupName)` and
  `repo.startGroupConversation(...)` build the RCS group.

## Signal launcher drop-in (gmessages parity)

Signal can now be hosted by the launcher exactly like Google Messages. New in
the `:signal` backend module (Compose enabled there; ZXing added):
- `object SignalRepository` — process-scoped repo+socket holder
  (`createIfPaired`/`create`/`shutdown`/`reset`), mirroring
  `GoogleMessagesRepository`.
- `object SignalPairing` — process-scoped provisioning-client holder
  (`getOrStart`/`reset`), so the QR + socket survive Activity recreation.
- `SignalAccountStore.isPaired()`, `object SignalConfig` (parity).
- `ui/SignalApp()` — the gate composable (linked → `DpadMessengerApp`; unlinked →
  `SignalLinkScreen` QR flow → saves the account on `Linked`). Plus
  `ui/SignalLinkScreen` (with "Try again") and `ui/QrCode`.

Launcher wiring: `settings.gradle.kts` substitutes `:signal`; `app/build.gradle`
depends on it; `SignalMessengerActivity` is the thin shell
(`setContent { DpadMessengerTheme { SignalApp() } }`), registered in the
manifest with the same `adjustResize` config. To open it:
`startActivity(Intent(this, SignalMessengerActivity::class.java))`.

**Parity caveat:** `SignalMessageRepository` currently implements only
`MessageRepository` (1:1 send/receive). So the Signal drop-in gives full
linking + chat, but new-conversation, contacts, media, groups, and the initial-
sync spinner stay dark until those capability interfaces (`ConversationStarter`,
`ContactsSource`, `MediaDownloader`/`AttachmentSender`, `GroupConversationStarter`,
`InitialSyncAware`) are implemented on the Signal repo — same set gmessages has.

## Hardening + perf pass (audit-driven)

A thorough security / performance / UI audit drove these changes:

**Security**
- Stripped logging that leaked the tachyon auth token + device IDs to logcat
  (`GoogleMessagesPairingClient` raw stream/element/body dumps) — these were
  also being persisted to disk by the host's rolling-logcat service. Redacted
  message IDs / mediaId / reaction logs too.
- **Message cache is now encrypted at rest** (`GoogleMessagesCache` →
  `EncryptedFile` under a Keystore master key). SMS bodies + contact numbers
  were previously plaintext JSON in filesDir. Old plaintext caches are migrated
  then deleted on first load.
- Bounds-hardened the hand-rolled parsers against malformed network input:
  `Protobuf` reader (length/varint bounds), `PbLite` JSON parser (recursion
  depth cap + index bounds + 8 MB stream cap), `GMGcm` (chunk-size shift bound).
  Previously a crafted frame could OOM / crash via huge allocations or
  StackOverflow.
- Resumable media upload now validates the server-returned upload URL host
  (must be a Google domain) before PUTting bytes there.
- (Crypto core — encrypt-then-MAC verified before decrypt, constant-time
  compare, SecureRandom, X25519 low-order rejection, GCM AAD binding — audited
  as correct and left untouched.)

**Performance (matters on the Flip 2)**
- Added a process-wide LRU bitmap cache (`ui/util/ImageDecode`) so thumbnails
  aren't re-decoded from disk every time they scroll back into view — the main
  source of scroll jank. Used by message thumbnails, the fullscreen viewer, and
  the picker grid (synchronous cache hit avoids the spinner flash).
- Fullscreen image decode now targets the actual display size (≤1080) instead
  of a fixed 1280 — cuts a ~5 MB ARGB bitmap to ~1.5 MB and the OOM risk.
- Cache load/save moved fully onto `Dispatchers.IO` (was on the constructor
  thread / CPU pool — a startup-jank/ANR risk); load is guarded so it can't
  clobber freshly-synced live data.
- Chat-open now shows a loading spinner until the room's history first loads
  (or a 2s grace period), so the "say hi" empty-state no longer flashes before
  messages arrive. (A `WhileSubscribed` timeline experiment was reverted to
  `Eagerly` — its saving was marginal since the ViewModel is per-open-chat, and
  it started the flow cold; the spinner is the real fix.)

**UI/UX**
- Failed sends now have a **"Retry send"** action in the context sheet
  (`resendMessage` added to the repo contract). A failed *outgoing media* opens
  the sheet instead of misrouting to a pointless download.
- Initial DPAD focus added to Settings (was a no-focus dead screen); New-message
  re-focuses the field on a start error (focus was lost); pairing "Failed" state
  and the fullscreen video viewer both got proper fallbacks ("Try again" /
  "Can't play this video").

### Still to confirm on device
- If the link keeps dying despite the refresh-on-401 path, capture the refresh
  logs: `adb logcat -s GMSession:*` should show `tachyon token refreshed`
  periodically; `token refresh: no token in response` / `long-poll fatal` means
  RegisterRefresh itself is failing (token too old, or a signing/endpoint issue)
  — that's the next thing to dig into.

## Test commands

```
./gradlew :gmessages:testDebugUnitTest   # X25519 + protobuf + pblite + session + crypto
```
