# :gmessages

In-app Google Messages backend for `dumb-down-launcher`. Pairs the Flip 2 as
a linked device against the user's primary Android phone (the one running
the actual Google Messages app), then sends and receives RCS/SMS through it.

Same architectural pattern as the Signal direct-mode backend in
`dpad-messenger-backend/signal` — see that module's source for what a
finished port looks like.

## Status

- [x] **Phase A — plumbing.** Gradle module wired into the launcher;
      `MessengerActivity` hosts `DpadMessengerApp` from the UI lib; mock
      backend so the chat screens render with demo data.
- [ ] **Phase B — pairing.** Port `mautrix-gmessages/pkg/libgm/pairing.go`
      to Kotlin. QR code, ECDH with primary, persisted device pair.
- [ ] **Phase C — receive + send + sync.** Port the relay WebSocket, the
      RPC layer for outbound, contact + read-receipt sync.

## Why the architecture this way

The launcher only ever needs *one* messenger backend — Google Messages —
so unlike `dpad-messenger-backend` we don't carry the `BackendConfig` /
`BackendFactory` indirection. `GoogleMessagesRepository.create(context)`
returns a `MessageRepository` directly and `MessengerActivity` plugs it
into `DpadMessengerApp`. Less code, no runtime backend switching, easier
to follow.

The UI library is consumed via Gradle composite build from
`../dpad-messenger`. Wiring lives in `dumb-down-launcher/settings.gradle.kts`.
Any change to the UI is reflected immediately in both this app and the
standalone `dpad-messenger-backend` app.

## Phase B — pairing port

mautrix-gmessages reference (Go): https://github.com/mautrix/gmessages

Key files to port:

| Go file | Kotlin target |
|---|---|
| `pkg/libgm/pairing.go` | `GoogleMessagesPairingClient.kt` |
| `pkg/libgm/types/pairing.go` | (inline data classes inside above) |
| `pkg/libgm/crypto/aes_ctr.go` | `GMCrypto.kt` (AES-CTR helpers) |
| `pkg/libgm/util/proto.go` | (drop; use protobuf-javalite directly) |
| protos in `pkg/libgm/binary/` | drop alongside our SignalService.proto |

Pairing flow at a glance:

1. Generate a fresh ECDH keypair (Curve25519, like Signal).
2. Open WebSocket to `instantmessaging-pa.googleapis.com` (a pubsub relay).
3. Generate a QR URL containing the public key + a random session-id.
4. User scans QR with their primary phone's Google Messages app
   (Settings → Device pairing).
5. Primary sends back a `PairingResponse` over the pubsub channel containing:
   - their device identity proto (browser_id, user_agent, etc.)
   - their own ECDH public key
   - a refresh token bound to this pair
6. We derive a shared secret via X25519, persist
   `(tachyon_refresh_token, browser_id, user_agent, primary_pubkey)` in
   `EncryptedSharedPreferences`.

Implementation order:
1. **Protos first** — copy the `.proto` files out of mautrix-gmessages,
   register them in `gmessages/build.gradle.kts` the same way
   `dpad-messenger-backend/signal/build.gradle.kts` registers SignalService.proto.
2. **`GMAuthInterceptor`** that adds the Tachyon bearer token to outgoing
   HTTPS calls.
3. **`GoogleMessagesPairingClient`** — mirrors `SignalProvisioningClient`:
   - `start()` opens the WS and returns a flow of states
     (`Connecting → WaitingForScan(qrUrl) → Linked(account) | Failed(...)`)
   - `cancel()` tears down the WS
4. **`GoogleMessagesAccountStore`** — mirrors `SignalAccountStore`:
   `EncryptedSharedPreferences`-backed save/load of the pair info.

Time estimate: 3–5 focused days.

## Phase C — receive + send + sync

| Go file | Kotlin target |
|---|---|
| `pkg/libgm/client.go` | `GMRelayWebSocket.kt` (long-poll receive) |
| `pkg/libgm/sending.go` | `GMSender.kt` |
| `pkg/libgm/messages.go` | wire into `GMMessageRepository.kt` |
| `pkg/libgm/contacts.go` | contact resolution (call sites in the repo) |

Key things mautrix already solved that we'll need:

- **Token refresh** — the Tachyon bearer expires; `pkg/libgm/auth.go`
  refreshes it via a sibling-RPC. Mirror as `GMSender.refreshTokenIfNeeded`.
- **Server-side ack** — every received message needs an HTTP-200-style ack
  back through the pubsub channel or the server retries. Same shape as
  `SignalChatWebSocket.sendOk`.
- **Per-conversation read state** — Google tracks per-thread read cursors
  rather than per-message receipts.

Time estimate: 1–2 focused weeks.

## Local dev pointers

Build just this module:
```
./gradlew :gmessages:assembleDebug
```

Build the launcher with the messenger wired in:
```
./gradlew :app:assembleDebug
```

The composite build pulls in `../dpad-messenger` automatically — make sure
that sibling repo exists at the expected path before building.

If you bump the Kotlin version in `gradle/libs.versions.toml`, also bump
`dpad-messenger/library/build.gradle.kts` to match (composite builds
require the consumer's Kotlin ≥ producer's).
