# Google Messages GAIA / cookie-auth port

## Why

Google removed QR-code pairing for Messages-for-web. The QR relay token our
current backend uses (mautrix `libgm` "Bugle" path) now dies after ~1–2h with
`gm-logged-out-401-polling` / `BAD_CREDENTIALS`. The only durable login is the
**Google-account / cookie** method ("GAIA pairing" — sign in + match an emoji),
where session longevity comes from `RegisterRefresh` being authenticated by live
Google cookies. This doc captures the protocol so the port targets the real
thing, and tracks the phased build.

## Protocol spec (from mautrix-gmessages `pkg/libgm`, main)

### Constants
- API key (`x-goog-api-key`): `AIzaSyCA4RsOZUFrm9whhtGosPlJLmVPnfSHKz8` (unchanged).
- SAPISIDHASH origin: `https://messages.google.com`.
- Network value: **`GDitto`** (QR path used `Bugle`).
- Hosts: GAIA/cookie mode uses **`instantmessaging-pa.clients6.google.com`**
  (QR used `…googleapis.com`). `SignInGaia` + `RegisterRefresh` exist ONLY on the
  clients6 host, under the `…v1.Registration` service.
- Config: `https://messages.google.com/web/config` (GET) → device UUID.
- ConfigVersion currently `{2026, 3, 18, V1=4, V2=6}`.

### Cookies required
`SID, HSID, OSID, SSID, APISID, SAPISID` (all required); `__Secure-1PSIDTS`
optional but harvested. Domains: most on `.google.com`, `OSID` on
`messages.google.com`. Harvest from a logged-in session at
`accounts.google.com/AccountChooser?continue=https://messages.google.com/web/config`
(the `continue=/web/config` keeps it an account login, not a browser pair).
Re-absorb any `Set-Cookie` from every relay response (keeps `__Secure-1PSIDTS`
fresh). Persist all cookies as a flat map.

### SAPISIDHASH Authorization header (on EVERY relay request)
```
ts   = unix seconds
hash = sha1_hex(ts + " " + SAPISID + " " + "https://messages.google.com")
header Authorization: "SAPISIDHASH " + ts + "_" + hash
```
Single hash, SHA-1, lowercase hex. Plus send the cookies in the Cookie header.

### Flow
1. **FetchConfig**: GET `/web/config` (with cookies+auth) → device UUID → seeds
   `SessionID`; `deviceID` for SignInGaia = `"messages-web-<hex(SessionID)>"`.
2. **SignInGaia**: POST `…/Registration/SignInGaia` (pblite). Body
   `SignInGaiaRequest{ authMessage{requestID, network="GDitto", configVersion},
   inner{ deviceID{1:3, 2:"messages-web-…"}, data{3: x509 PKIX DER of a locally
   generated ECDSA P-256 RefreshKey pubkey} }, network="GDitto" }`. Response
   `SignInGaiaResponse` → **tachyon auth token** (tokenData.tachyonAuthToken/TTL,
   obtained BEFORE the emoji match) + device list. Pick the device with
   `unknownInt4==1` (primary); its UUID → `DestRegID` (presence flips client to
   Google-account mode).
3. **Long-poll** ReceiveMessages opened BEFORE pairing; UKey2 responses arrive
   over it as `GaiaEvent` (route 7).
4. **UKey2 CLIENT_INIT** (`ActionType 44`, MessageType `GAIA_2=20`, DontEncrypt,
   TTL 300s): generate ECDSA P-256 pairing key; build `Ukey2ClientFinished`
   (kept as FinishPayload), `keyCommitment = SHA-512(FinishPayload)`,
   `Ukey2ClientInit{version=1, random[32], cipherCommitments=[{P256_SHA512,
   commitment}], nextProtocol="AES_256_CBC-HMAC_SHA256"}`. Wire envelope
   `GaiaPairingRequestContainer{1:pairingAttemptID, 2:browserDetails,
   3:startTs, 4:ukeyBytes, 5:proposedVerCodeVer=1, 6:proposedKeyDerivVer=1}`.
5. **SERVER_INIT** (over long-poll) → ECDH(pairingKey, serverKey) →
   `sharedSecret = SHA-256(dh)`; `ukeyV1Auth = HKDF-SHA256(sharedSecret,
   salt="UKEY2 v1 auth", info=InitPayload||serverInitData)`; `NextKey = HKDF(…,
   salt="UKEY2 v1 next", info=…)`; `authNumber = be_uint32(ukeyV1Auth[0:4])`;
   `emoji = emojiList[authNumber % len]` (V0/V1 lists by confirmed version).
6. **Emoji match**: user taps the matching emoji on their phone. Client sends
   **CLIENT_FINISHED** (`ActionType 45`, MessageType `BUGLE_MESSAGE=2`) = the
   FinishPayload, then blocks on the long-poll for the finish response
   (`GaiaPairingResponseContainer` finishErrorType/Code). 0 = success.
7. **Session keys** from `NextKey` (version 0: AES=clientKey, HMAC=serverKey;
   version 1: SHA-256 over ordered keys then HKDF with "Ditto salt 1/2"). Used
   for AES-CTR+HMAC of message payloads (same scheme as QR, different keys).

### After pairing — messaging RPCs
Identical envelopes/encryption to the QR path, with 4 mode differences when
`IsGoogleAccount()`:
1. Host = the `…clients6.google.com` ("…Google") URLs.
2. `AuthMessage.network` = `"GDitto"`.
3. Every `OutgoingRPCMessage` carries `destRegistrationIDs=[DestRegID]` (field 9).
4. Cookies + `SAPISIDHASH` Authorization on every request.

### Longevity (the actual fix)
Same tachyon token type, but `RegisterRefresh` (clients6 host, ECDSA-signed with
RefreshKey) is sent WITH cookies + SAPISIDHASH under `GDitto`, so Google keeps
re-issuing tokens while the cookies are valid. Refresh ~1h before expiry.

> **CORRECTION (June 2026, from a field logcat):** "cookies self-refresh from
> `Set-Cookie` on responses" is only true for the short-lived `SIDCC` /
> `__Secure-*PSIDCC` cookies. The Messaging relay endpoints **never** re-issue
> the rotating session cookie `__Secure-1PSIDTS` via `Set-Cookie`, so it goes
> stale ~30 min after another holder (the user's browser) rotates it, and
> `RegisterRefresh` then 401s with `SESSION_COOKIE_INVALID` (observed at 2h04m).
> The real durable fix is to rotate `__Secure-1PSIDTS` **on-device** via
> `accounts.google.com/RotateCookies` (`GMCookieRotator` in `:gmessages`); see
> `GMESSAGES_STATUS.md` → "Cookie longevity — on-device `__Secure-1PSIDTS`
> rotation". Cookie durability still requires the phone to be the **sole** holder
> of the login (incognito-and-close).

## Phased build plan

- **Phase 1 — cookie acquisition + auth primitive. DONE.**
  `GMCookieAuth` (SAPISIDHASH), cookie persistence in the account store.
  **Cookie acquisition is via the COMPANION phone over the Type Sync relay**
  (the easy, reliable path — Google login works in the smartphone's browser):
  - Companion (`dumb-down-android`): `ui/gmessagessignin/GoogleMessagesSignInScreen`
    signs into Google in a WebView, harvests the cookies, and `TypeSyncService
    .sendGmessagesCookies()` AES-256-GCM-encrypts them with the device-link shared
    secret and sends a `gmessages_cookies` relay message.
  - Backend (`offline-dc-twilio` `keyboardWs.js`): forwards `gmessages_cookies`
    (companion→phone) + `gmessages_cookies_ack` (phone→companion). E2E encrypted;
    relay never sees plaintext.
  - Flip phone (`dumb-down-launcher`): the cookies are received on the **single,
    already-live Type Sync relay** owned by `MouseAccessibilityService` (log tag
    `TypeSyncRelay`) — the same socket text sync uses. Its `onMessage` handles
    `gmessages_cookies` inline (`handleRelayGmessagesCookies`): decrypt with the
    device-link secret → `GoogleMessagesAccountStore.saveCookies()` → ack → run
    `GMGaiaClient` pairing. `GoogleCookieReceiveActivity` is the DPAD UI; it
    registers `GmessagesCookieCallbacks` with `MouseAccessibilityService` and
    calls `startRelay`, then shows waiting → "signing in" → the emoji → result.
    Reached from the link screen's "Sign in with Google account"
    (`GoogleMessagesApp.onCompanionSignIn`).
    See **"Cookie reception reliability"** below for why this replaced the old
    separate-socket `GmessagesCookieRelayClient` (now deleted).
  - Fallback (kept): the in-flip-phone WebView `GoogleAccountLoginScreen` is still
    wired when there's no host companion handler (standalone/demo).
- **Phase 2 — FetchConfig + SignInGaia. DONE + validated on-device.**
  SignInGaia returns HTTP 200 with a 104-byte tachyon token and the device list;
  the primary phone (device with `unknownInt4==1`) is extracted as the DestRegID.
  `/web/config` 403s but is non-fatal (SignInGaia accepts a generated
  `messages-web-<hex>` device id + random session UUID). See `GMGaiaClient`.
- **Phase 3 — UKey2 handshake + emoji match. DONE + validated on-device.**
  (CLIENT_INIT → SERVER_INIT → emoji → user taps on phone → CLIENT_FINISHED →
  `pairing CONFIRMED by phone` → session keys derived + account saved.)
  `GMUkey2.kt` (P-256 ECDH, SHA-512 commitment, HKDF, the V0/V1
  emoji lists — V0 copied byte-for-byte from mautrix, 305 entries) + `GMGaiaPairing.kt`
  (GAIA ReceiveMessages long-poll + CLIENT_INIT/CLIENT_FINISHED over the clients6
  host, `destRegistrationIDs`, `GDitto`, cookies+SAPISIDHASH). The emoji is shown
  on the flip phone (`GoogleCookieReceiveActivity`); the user taps the matching
  one in Google Messages on their phone. On success the AES/HMAC session keys +
  account are persisted (`saveGaiaSession`). Heavily logged (tag `GMGaiaPair`).
- **Phase 4 — GAIA session mode. DONE.**
  `GoogleMessagesSessionClient` is now GAIA-aware (`store.isGaiaMode()`): clients6
  Messaging URLs (`*_URL_GOOGLE`), `GDitto` network on ReceiveMessages/Ack/
  RegisterRefresh, `destRegistrationIDs=[primary phone]` on every SendMessage,
  and cookies + a fresh SAPISIDHASH on every request (`applyRelayHeaders`).
  RegisterRefresh signs with the persisted ECDSA RefreshKey (threaded from
  SignInGaia through `GMGaiaPairing`). QR pairing retired: `GoogleMessagesPairing`,
  `GoogleMessagesPairingClient`, `GoogleMessagesLinkScreen`, `QrCode` deleted; the
  unpaired flow goes straight to the companion sign-in. (`X25519` + the QR-only
  `GMCrypto`/`GMPairingProto` primitives remain, unused — harmless, removable.)
  Known follow-ups: media up/download headers don't yet add cookies+SAPISIDHASH
  for GAIA; `__Secure-1PSIDTS` cookie isn't refreshed from response Set-Cookie.

### Phase 3 exact constants (from mautrix `pair_google.go` / gmproto, captured)
- UKey2: `Ukey2Message{message_type=1, message_data=2}` types CLIENT_INIT=2,
  SERVER_INIT=3, CLIENT_FINISH=4; cipher `P256_SHA512=100`;
  `Ukey2ClientInit{version=1, random=2, cipher_commitments=3[{handshake_cipher=1,
  commitment=2}], next_protocol=4="AES_256_CBC-HMAC_SHA256"}`;
  `Ukey2ServerInit{version=1, random=2, handshake_cipher=3, public_key=4}`;
  `Ukey2ClientFinished{public_key=1}`; `GenericPublicKey{type=1(EC_P256=1),
  ec_p256_public_key=2{x=1,y=2}}` (x/y are 33 bytes: 0x00 + 32).
- commitment = SHA-512(CLIENT_FINISH message); sharedSecret = SHA-256(P256 ECDH);
  authInfo = clientInitMsg || serverInitMsg; ukeyV1Auth = HKDF(salt="UKEY2 v1
  auth"); NextKey = HKDF(salt="UKEY2 v1 next"); authNumber = BE uint32(auth[0:4]);
  emoji = list[authNumber % len] (V0 len 305, V1 len 308).
- session keys: clientKey/serverKey = HKDF(NextKey, salt=ENCRYPTION_KEY_INFO,
  info="client"/"server"); keyDerivVer 0 → AES=client, HMAC=server; ver 1 → order
  the two keys by Java byteHash, prefix ENCRYPTION_KEY_INFO, SHA-256, then
  HKDF(salt="Ditto salt 1/2", info="Ditto info 1/2").
- RPC: `OutgoingRPCMessage{mobile=1, data=2, auth=3, TTL=5,
  destRegistrationIDs=9}`, `data{requestID=1, bugleRoute=2,
  messageData=12, messageTypeData=23{messageType=2}}`,
  `OutgoingRPCData{requestID=1, action=2, unencryptedProtoData=3, sessionID=6}`.
  Actions CLIENT_INIT=44, CLIENT_FINISHED=45; msgType GAIA_2=20 (init) /
  BUGLE_MESSAGE=2 (finish); pairing TTL = 300s.

### Two gotchas that cost the most on-device iteration (now fixed)
1. **Outgoing `bugleRoute` is `DataEvent` (19), NOT `GaiaEvent` (7).** `GaiaEvent`
   (7) is only the route on the *incoming* SERVER_INIT/finish responses. Sending
   CLIENT_INIT with route 7 → HTTP 200 but the server never emits SERVER_INIT.
2. **`pairingAttemptID` (+ `startTimestamp`) must be the SAME for CLIENT_INIT and
   CLIENT_FINISHED** (one per handshake; the per-RPC requestID is separate). Using
   a fresh id per message → finish rejected instantly as `NOT_LATEST_ATTEMPT`
   (errCode 10).
   Also: the GAIA `ReceiveMessages` body must include the trailing `Unknown` field
   `[null,[]]` (reuse `PbLite.receiveMessagesRequest`) or events won't route to the
   long-poll; the pairing HTTP send needs a finite timeout (separate from the
   long-poll's infinite one) so a stalled send can't hang the handshake.
  `GaiaPairingRequestContainer{pairingAttemptID=1, browserDetails=2,
  startTimestamp=3, data=4, proposedVerificationCodeVersion=5,
  proposedKeyDerivationVersion=6}` (proposed=1 only on INIT).
  `GaiaPairingResponseContainer{finishErrorType=1, finishErrorCode=2, data=5,
  confirmedVerificationCodeVersion=6, confirmedKeyDerivationVersion=7}`.
  URLs: `…clients6.google.com/$rpc/….Messaging/{ReceiveMessages,SendMessage}`.

Status: **Phase 1 DONE + validated on-device** (cookies harvested on the
companion, transferred over the relay, saved on the flip phone:
`saved 15 cookies … names=[APISID, HSID, NID, OSID, SAPISID, SID, SIDCC, SSID,
__Secure-1P/3P…]`, companion got `ack ok=true`). The transfer mechanism works.

### Phase 2–4: DONE + validated on-device
The whole flow works end-to-end against Google (sign in on companion → cookies
to flip phone → SignInGaia → UKey2 emoji match → GDitto session). The phased
notes below are kept for reference. These run ON THE FLIP PHONE using the saved
cookies:

- **Phase 2 — FetchConfig + SignInGaia.** GET `/web/config` (cookies +
  SAPISIDHASH) for the device UUID; POST `…/Registration/SignInGaia` (pblite)
  for the tachyon token + device list; pick the primary device → `DestRegID`.
  Checkpoint: token returned + phone found. NOTE: needs the exact `/web/config`
  device-id location and the SignInGaia pblite array shape nailed down from logs.
- **Phase 3 — UKey2 handshake + emoji match.** ECDSA P-256 pairing key, UKey2
  CLIENT_INIT → SERVER_INIT (over the long-poll), ECDH + HKDF → emoji; user taps
  it on their phone; CLIENT_FINISHED → derive the AES/HMAC session keys. The
  biggest piece (the emoji lists + key derivation).
- **Phase 4 — GDitto session.** Switch host→clients6, network→GDitto, add
  destRegistrationIDs, cookies+SAPISIDHASH on every call + RegisterRefresh.
  Retire the QR path.

`GMCookieAuth.sapisidHash()` (Phase 1) is the auth header every Phase 2–4 request
uses.

## Cookie reception reliability (June 2026 rework)

The cookie transfer was unreliable in the field — the flip phone would sit on
"waiting for ur smart phone…" forever while the companion resent the cookies,
unacked. Root cause + fixes:

- **One relay, not two.** The relay backend allows only ONE `role:"phone"`
  socket per number; a second connecting evicts the first with close reason
  `replaced`. The launcher already keeps a live `phone` socket for **text sync**
  inside `MouseAccessibilityService` (tag `TypeSyncRelay`). The old
  `GmessagesCookieRelayClient` opened a SECOND `phone` socket for cookies, so the
  two evicted each other; the churn left the slot empty/mid-handshake exactly
  when the companion sent the login, and it was dropped. **Fix:** delete
  `GmessagesCookieRelayClient` and handle `gmessages_cookies` on the single live
  relay (`handleRelayGmessagesCookies`). One stable `phone` socket, login lands
  first try.
- **The decisive log.** `D/TypeSyncRelay: ignoring type=gmessages_cookies` — the
  cookies WERE arriving, on the live relay, which had no case for them. The
  handler had mistakenly been added to `TypeSyncService` (a second/legacy relay
  class that isn't the connected socket on-device, so its logs were silent).
  Lesson: the launcher has two relay clients; the cookie handler must live in the
  active one, `MouseAccessibilityService`.
- **Re-pair secret rotation.** A re-pair rotates the device-link shared secret
  and the backend force-closes both sides with reason `re-paired`. Decrypt with a
  stale cached secret silently fails. `MouseAccessibilityService`'s relay already
  re-reads the pairing on each reconnect (`DeviceLinkReader`), so it recovers.
- **`auth_failed — no active pairing`.** Backend has no pairing row for the
  number → the relay rejects auth (code 1008). Not a cookie bug: the device-link
  pairing must exist (and text sync must work) before cookies can flow.
- **Don't auto-reconnect on clean closes.** Reconnecting on `onClosed` (vs only
  `onFailure`) turned an `auth_failed` close into a reconnect/re-auth hammer
  loop. Reconnect only from `onFailure`.

### Pairing wait — indefinite (matrix-app `:gmessages`)
`GMGaiaPairing` CLIENT_FINISHED now waits **indefinitely** for the user to tap
the matching emoji (was a 120s timeout), polling in 15s chunks. `GMGaiaPairing`
/ `GMGaiaClient` expose `cancel()` to end the wait.

> **Cross-repo caveat:** `cancel()` + the `WAIT_FOREVER` change live in
> `matrix-app/dpad-messenger-backend/gmessages` (a separate repo from the
> launcher). The launcher deliberately does **not** call `GMGaiaClient.cancel()`
> yet, so it builds against whatever `:gmessages` version the build pulls. Until
> those matrix-app changes are committed to the build, cancel-on-screen-leave is
> inactive (the wait still runs until the phone confirms). Re-add the launcher's
> `cancel()` call once the two repos are in sync.
