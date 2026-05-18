# Storage-recovery plan for dumb-down-launcher

Phones (TCL Flip 2) ship with 8 GB internal. The launcher already runs three WorkManager cleanups (call log nightly at 2 AM, OpenBubbles attachments weekly at 2 AM, WhatsApp media nightly at 2 AM with a 7-day rolling window) and applies auto-download-off SharedPreferences for OpenBubbles and WhatsApp. This plan tightens those, adds a few more, deletes the 256 MB swap allocation, and exposes a Storage screen the user can open from the launcher.

Decisions locked in: cleanups run **nightly at 4 AM**, Storage UI is a **summary screen with per-app "clear" buttons**, swap is **removed for everyone on next update**, **no changes** to `dumb-phone-configuration`.

## 0. What the audit revealed (TCL Flip 2 / model 4058R)

Run on a real device, 17 May 2026. `/data` is **4.5 GB total, 3.6 GB used, 922 MB free — 80% full**.

### 0.1 The elephant: `/data/app` (most of the partition)

The biggest chunk of `/data` is **installed APKs**, not user data. On the audit device `/data/app` reports 2.23 GB total, but that figure includes ~400 MB of Signal + Molly which are test-only installs and will not ship to real phones. Production APKs only:

| APK | Size on disk | Notes |
|---|---|---|
| OpenBubbles | **695 MB** | `lib/arm` alone is 222 MB |
| WhatsApp | 223 MB | |
| Apple Music | 213 MB | |
| Trichrome / WebView | 185 MB | system, can't uninstall |
| Spotify | 133 MB | |
| Azure Authenticator | 125 MB | |

That's ~1.6 GB of production binaries before any user runs the apps. No nightly worker can free this — installed APKs only shrink via uninstall, ABI-stripping at install time, or AAB-based installs.

**This plan doesn't try to fix APK size**, but the obvious follow-up for a separate ticket is **ABI-filtered installs**: the TCL Flip 2 is arm32 only (`ro.product.cpu.abi=armeabi-v7a`). Any APK that bundles arm64 + x86 libs is wasting tens to hundreds of MB. OpenBubbles' 695 MB is the most likely candidate — `lib/arm` is 222 MB and there's almost certainly an `lib/arm64` and `lib/x86` we're paying for too. Switching the pipeline to `pm install --abi armeabi-v7a` (universal APKs) or bundletool-installed AABs would claw back a sizeable chunk.

### 0.2 What workers *can* free (recurring per night)

| Target | Exact path (confirmed) | Size on this device | Worker |
|---|---|---|---|
| Spotify offline cache | `/data/user_de/0/com.spotify.music/Android/data/com.spotify.music/files/spotifycache` | **109.5 MB** (1.3 GB observed in the wild) | new nightly `SpotifyOfflineCleanupWorker` + Storage-UI button |
| AntennaPod episodes | `/data/data/de.danoeh.antennapod/cache/` | **105.4 MB** | covered by `pm trim-caches` + dedicated worker as Storage-UI target |
| OpenBubbles attachments | `/data/data/com.openbubbles.messaging/app_flutter/attachments` | **78 MB** (39 files) | existing worker, moving from weekly → nightly 4 AM |
| App-wide cache trim | every `/data/data/*/cache`+`code_cache` | ~246 MB sum | new `AppCacheCleanupWorker` (`pm trim-caches`) |
| WhatsApp on-disk media | `/sdcard/Android/media/com.whatsapp/WhatsApp/Media/{Images,Video,.Links}` | 10 MB (already small) | existing worker, retention 7d → 0d |
| Apple Music | `/data/data/com.apple.android.music/` | 49 MB total, ~7 MB easily clearable cache | new worker; bulk likely in cache-evictable subdirs |
| Chromium Chrome | `/data/data/org.chromium.chrome/` | **134 MB** (separate from `com.android.chrome`!) | `pm trim-caches` covers the cache portion |
| `/data/swapfile` (one-time) | `/data/swapfile` | **256 MB** | new `remove_swap_256m_v1` migration |
| zram | `/dev/block/zram0` (kernel) | 671 MB virtual, 654 MB used | **stays.** Kernel-managed compressed-RAM swap. Independent of disk swap. The device is under real RAM pressure; we are only removing the disk swap. |
| `/sdcard/Download`, `DCIM`, `Pictures`, etc. | — | all <8 MB combined | ignore |
| `/data/anr`, `/data/tombstones`, `/data/system/dropbox` | — | <4 MB combined | ignore |
| Call log | `content://call_log/calls` | (rows, not bytes) | existing worker, **retention 50 → 25 rows** (per request, change already applied) |

### 0.3 Three corrections from earlier reads of the audit

- **Spotify was not "barely used".** Earlier I read this user as not downloading offline because `/data/data/com.spotify.music` was only 8.4 MB. The actual offline cache is on Device-Encrypted storage at `/data/user_de/0/com.spotify.music/...spotifycache/` — 109.5 MB. The dedicated Spotify worker is high-value, and the worker target is locked in.
- **AntennaPod episodes live in `cache/`, not `files/`.** That means `pm trim-caches` is the right hammer — episodes are evictable by design when an app stores them in `cache/`. The dedicated AntennaPod worker becomes more about giving the user an explicit "Clear podcasts" button in the Storage UI than about doing something `pm trim-caches` doesn't already do.
- **Spotify Android does not honor the macOS `storage.size=` prefs trick, and the on-disk layout makes streaming cache and user-marked offline downloads inseparable externally.** Two related findings, taken together:
  - *The cap trick.* The desktop client (and a longstanding community-forum recipe) lets you cap the cache by adding `storage.size=N` to `~/Library/Application Support/Spotify/prefs`. Tested on Android against both candidate prefs files (`/data/data/com.spotify.music/files/settings/prefs` — the flat key=value file with `core.clock_delta` / `autologin.*` — and the per-user file under `files/settings/Users/<id>/prefs`). With `storage.size=50` set, the cache exceeded the cap within one listening session, reaching 229 MB. `grep -ri "cache\|storage"` across `shared_prefs/*.xml` returned only the read-only `cache_location_v4` key, no size knob. The Android client either silently strips unknown keys on rewrite or ignores them — either way, no in-Spotify config path bounds the cache.
  - *The on-disk layout.* `spotifycache/Storage/` is a 256-shard hex-prefix content-addressable store. Streaming-cache audio chunks and user-marked offline downloads both land in the same `Storage/<hex>/` shards as hash-named files, distinguished only by Spotify's internal LevelDB (`public.ldb`, `Users/<id>/primary.ldb`). The `offline.bnk` bookkeeping file is a fixed 7901 bytes regardless of whether the user has downloads — confirmed by marking and unmarking an album on the audit device, the file's size and mtime didn't move. So there's no cheap external signal of "does this user have any downloads?", and an external `find -delete` on `Storage/` can't preserve downloads while clearing cache.
  - *Why this matters for the design.* External wipe is the only available knob, but it's an all-or-nothing knob. The auto-nightly worker uses a **500 MB size gate** as the compromise (see §1.1): if the cache is under the threshold the worker skips, preserving downloads; if it's over, the worker wipes everything — most likely streaming-cache bloat, but at the unavoidable cost of nuking any user downloads in the same dir. The manual "Clear Spotify offline" button stays unconditional for users who explicitly want the full nuke.

### 0.4 This user's mix is not every user's mix

This device has heavy AntennaPod + Spotify use, light WhatsApp, light Apple Music. Different users will lean differently — someone living in WhatsApp will keep filling `/sdcard/Android/media/com.whatsapp/`, and someone using Apple Music heavily will fill its offline dirs. The plan keeps a worker for every category so the launcher behaves the same on any device, even where that category is small on the audit phone.

### 0.5 Migration state

`shared_prefs/migrations.xml` confirms: `delete_type_sync_channel`, `disable_wifi_scan_throttle`, `remove_openbubbles_doze_whitelist`, `whatsapp_setup_v1`, `create_swap_256m`, `uninstall_snake_apk`, `disable_tcl_fota`, `openbubbles_setup_v1`, `grant_openbubbles_contact_perms` — all true. The new `remove_swap_256m_v1` flag slots in next to these.

### 0.6 Estimated one-time + nightly reclaim on this device

- **One-time at next-update boot:** ~256 MB from swap removal.
- **First nightly run (auto only):** ~78 MB OpenBubbles attachments + small change from WhatsApp. Spotify *won't* fire on the audit device because its cache is currently well under the 500 MB threshold; on a device with a heavy listener and a 1+ GB cache, Spotify adds ~1 GB to the auto recovery the night it crosses the threshold.
- **If the user also taps every "Clear" button in the Free Up Space screen on day one:** another ~110 MB Spotify offline + ~105 MB AntennaPod episodes + ~50–150 MB from `pm trim-caches` (whatever portion of the 246 MB cache total is actually evictable) + small change from Apple Music.
- **Total recovered within 24 hours: ~600–700 MB** (auto + manual combined) on a heavy-Spotify device. A 922-MB-free phone becomes a ~1.5–1.6-GB-free phone.

Recurring win for heavy Spotify users: the auto tier is bursty by design — nothing happens night-to-night while the cache is under 500 MB, then a single ~500 MB+ wipe when it crosses. Light listeners and users with only downloads stay below the threshold indefinitely and see no auto-clearing of Spotify at all. Either way, the cache is bounded — it can't reach the 1.3 GB figure observed in the wild without triggering a wipe within 24 hours.

## 1. Two tiers: nightly auto (low-surprise) vs. manual-only (user-curated content)

The guiding principle: anything the user *chose* to download for offline use (podcasts, offline music) is **manual-only** — cleared from the Storage UI, never on a schedule. Anything that's just app overhead or trivially redelivered from a cloud (cache files, message attachments) is **auto, nightly**.

**Spotify is the explicit exception to that principle**, but with a size-gated twist. The dir is mostly streaming cache, not user-curated downloads — most users don't mark anything offline, so the 1.3 GB observed in the wild is almost entirely just-listened tracks Spotify decided to keep. The on-disk reality (see §0.3) is that cache and downloads share one content-addressable store and can't be separated externally, so the nightly worker uses a **500 MB cache-size gate**: under-threshold runs skip the wipe (preserving any downloads a light user has accumulated), over-threshold runs wipe everything. The cost — a Wi-Fi re-download on the next play — is only paid by users who simultaneously have a lot of cache *and* a lot of downloads. The MANUAL "Clear Spotify offline" button stays unconditional for users who actively want to nuke everything; its existing confirm dialog warns about the Wi-Fi re-download. Apple Music and AntennaPod stay fully manual because their footprints are more bounded and the user-curated-vs-cache split is cleaner.

### 1.1 Auto, nightly at 4 AM

All run via `PeriodicWorkRequest`, all share one `nextRunAt(hour=4, minute=0)` helper so schedules can't drift.

- **`CallLogCleanupWorker`** (existing) — reschedule to 4 AM. Retention changed from 50 → **25 entries** (already shipped). Database, not bytes; runs against `content://call_log/calls`.
- **`OpenBubblesAttachmentCleanupWorker`** (existing) — change from `PeriodicWorkRequest` weekly → nightly. Two-step run:
  1. **Re-apply** `flutter.autoDownload=false` in `/data/data/com.openbubbles.messaging/shared_prefs/FlutterSharedPreferences.xml`. Today this is only set at launcher boot (via `DumbDownApp.applyOpenBubblesPerfSettings`); Flutter rewrites prefs frequently and an app update can revert it. Re-asserting it nightly means a flip-back can only cause one day of auto-downloads at worst.
  2. **Wipe** `/data/data/com.openbubbles.messaging/app_flutter/attachments` with `find … -mindepth 1 -delete`. Won't log the user out (auth is in `databases`/`shared_prefs`), and iMessage redelivers attachments on demand.
- **`WhatsAppAttachmentCleanupWorker`** (existing) — reschedule to 4 AM. Two-step run:
  1. **Re-apply** `autodownload_cellular_mask=0`, `autodownload_wifi_mask=0`, `autodownload_roaming_mask=0` in `/data/data/com.whatsapp/shared_prefs/com.whatsapp_preferences_light.xml`. Same defense-in-depth reasoning — today these only get set at launcher boot via `DumbDownApp.applyWhatsAppMediaSettings`, and WhatsApp absolutely rewrites this file on its own schedule.
  2. **Wipe** Images / Video / `.Links` under `/sdcard/Android/media/com.whatsapp/WhatsApp/Media/`. Retention 7d → **0d** — wipe every file every night. Keep the `.nomedia` exclusion. Keep voice notes / docs / GIFs / audio preserved. Messages re-download from WhatsApp servers on demand.
- **`SpotifyOfflineCleanupWorker`** (new) — nightly at 4 AM. Single-step: call `StorageCleanupOps.clearSpotifyOfflineIfOverThreshold`, which size-gates against `SPOTIFY_AUTO_CLEAR_THRESHOLD_BYTES` (500 MB). Under-threshold runs log "skipping wipe" and return without touching the dir or the `last_run_at_ms` record — the UI's "last cleared" subtitle stays correct (nothing happened, nothing reported). Over-threshold runs delegate to the same unconditional `clearSpotifyOffline` op that backs the manual button and the adb trigger receiver, so all three paths share one deletion implementation and one record-update site. No prefs-assertion step — Spotify exposes no relevant knob (see §0.3).

The pref re-apply step in the WA / OB workers should be **idempotent and quiet** — if the XML already has the right values, no rewrite, no log entry. The launcher already has helpers for this (the upsert-or-insert pattern in `applyOpenBubblesPerfSettings` / `applyWhatsAppMediaSettings`); these workers call the same helpers.

That's it for auto. AntennaPod, Apple Music, and `pm trim-caches` stay manual.

### 1.2 Manual-only (fired from the Storage UI, no `PeriodicWorkRequest`)

These workers exist as `CoroutineWorker`s with exported `BroadcastReceiver`s, but they have no scheduler attached. They fire only when the user taps "Clear now" in the Storage screen (or when triggered via `adb shell am broadcast` for testing).

- **`AntennaPodCleanupWorker`** — wipes `/data/data/de.danoeh.antennapod/cache/` (confirmed 105 MB on the audit device). Episodes live in cache/ by AntennaPod's own design but the user *did* download them and we shouldn't decide for them when to nuke. Bound to "Clear podcasts" button.
- **`AppleMusicOfflineCleanupWorker`** — wipes Apple Music's offline-download dirs. The audit pinned down 49 MB total for `com.apple.android.music` with only ~7 MB localized into obvious cache subdirs — the other ~42 MB is in subtrees we didn't surface (likely under `files/` somewhere). Worker uses a heuristic pattern: `find /data/data/com.apple.android.music/files -type f \( -name '*.m4a' -o -name '*.aac' -o -name '*.mp4' -o -name '*.movpkg' -o -name '*.fp4' \) -delete`, plus any subdir named `Downloads`/`Subscription`/`MediaCache`/`media_library`. Belt-and-braces because Apple Music's storage layout has changed across versions; the heuristic survives that. Bound to "Clear Apple Music offline" button. First run will log which files matched so we can tighten the pattern later if needed.
- **`AppCacheTrimWorker`** — runs `pm trim-caches 99999999999` (the OS's built-in "free up to N bytes of cache"). Drains every app's `cache/`+`code_cache/`. **Including AntennaPod's episode cache.** This is why it's manual — the button label will read "Clear all app caches (clears podcast episodes too)" with a confirm dialog. Reclaims a sizable chunk of the ~246 MB total app-cache footprint we measured.

Each manual worker also writes a `last_run_at` + `bytes_freed` record to a `storage_cleanup` SharedPreferences file so the Storage UI can show "Last cleared 3 days ago, freed 87 MB" next to each button.

### 1.3 Receivers

Every worker (auto and manual) gets an exported `BroadcastReceiver` so it can be triggered manually for testing, matching the existing `WhatsAppAttachmentCleanupTriggerReceiver` pattern. The UI fires these same receivers — no parallel deletion path.

## 2. Swap-file removal (one-time migration, ships to everyone)

**New migration** in `DumbDownApp.kt` migration framework (~lines 862–1198):

```kotlin
private const val MIGRATION_REMOVE_SWAP_KEY = "remove_swap_256m_v1"

private fun migrationRemoveSwap(prefs: SharedPreferences) {
    if (prefs.getBoolean(MIGRATION_REMOVE_SWAP_KEY, false)) return
    val ok = runAsRoot("swapoff /data/swapfile 2>/dev/null; rm -f /data/swapfile")
    if (ok) prefs.edit().putBoolean(MIGRATION_REMOVE_SWAP_KEY, true).apply()
}
```

Wire it into `runOneTimeMigrations()`. The earlier `migrationCreateSwap` (lines 916–1012) and the `MIGRATION_SWAP_KEY = "create_swap_256m"` constant get **deleted**, as does the boot-time call to `enableSwapIfPresent()` (line 253) and the function itself. Net: 256 MB reclaimed on every existing device the moment they get the next launcher build, and zero new swap on fresh provisions.

The old `create_swap_256m` flag is left untouched in prefs — it's now meaningless but removing it has no value and adds a code path. The new `remove_swap_256m_v1` flag is the source of truth going forward.

If we ever want to back this out, the `v1` suffix lets us ship `remove_swap_256m_v2` later without re-running the same logic on already-cleaned devices.

## 3. In-launcher Storage screen — "Free up space" suggestions

This isn't a storage manager. It's a short list of **things the launcher suggests you could clear**. A user opens the tile, sees three or four rows, taps the one they don't mind losing, and is done. No comprehensive breakdown of every byte on the device, no informational rows for stuff that's already auto-cleaned (the user can't do anything about those anyway).

The framing matters because it sets expectations: a user who opens "Storage" and sees `/data/swapfile`, `/data/dalvik-cache`, system app sizes, etc. will assume they're supposed to do something about all of it. A user who opens "Free up space" sees a focused list of actions and understands that what's not shown is being handled.

Compose screen reachable from the AllApps grid as a **"Free up space"** tile (matches the framing better than "Storage"; the underlying screen / route can still be called `StorageScreen`).

**Layout, top to bottom:**

1. **Free / total summary.** Single line: "1.4 GB free of 8.0 GB". `StorageStatsManager.getFreeBytes(UUID_DEFAULT)` / `getTotalBytes(UUID_DEFAULT)`. Color-coded — amber under 15% free, red under 5%. No bar chart, no breakdown.
2. **Suggestion rows.** Each row: icon, title, size, last-cleared subtext, "Clear" button. **One row per actionable thing**, nothing else:
   - **Podcasts** — "Clear downloaded episodes (105 MB)". Confirm dialog: "Episodes will need to re-download next time you play them. Continue?"
   - **Spotify offline music** — "Clear offline tracks (109 MB)". Confirm dialog warns about Wi-Fi re-download.
   - **Apple Music offline music** — same pattern. Size estimated via the same heuristic `find` the worker uses (matching audio file extensions under `files/`), since the audit didn't fully localize Apple Music's offline dir.
   - **App caches** — "Clear app caches (~XX MB)". Confirm dialog: "Includes downloaded podcasts. Continue?" Runs `pm trim-caches 99999999999`.

   Rows hide themselves when the size is under a threshold (say 10 MB) — no point suggesting the user clear 2 MB. If everything is under threshold the screen shows a single "You're all set — nothing worth clearing right now" message.
3. **Footer, small grey text.** "Media files in messages are cleared automatically every night. Your messages are kept." That's the only acknowledgement that the auto tier exists. If a user is curious there's nothing to click — just a sentence so they know what's happening and (importantly) what's *not* happening: message history stays.

**No "Clear everything" button**, no auto-tier row breakdown, no last-auto-clean timestamp shown to users (the launcher logs it internally for debugging, but it's noise on the user-facing screen).

**Size queries (lazy, only what's shown):**

Per-package totals from `StorageStatsManager.queryStatsForPackage(UUID_DEFAULT, pkg, Process.myUserHandle())` — instant, kernel-cached, same API Settings → Apps uses. The launcher self-grants `PACKAGE_USAGE_STATS` via `su appops set com.offlineinc.dumbdownlauncher GET_USAGE_STATS allow` on first run.

| Row | Source |
|---|---|
| Podcasts | scoped `du` of `/data/data/de.danoeh.antennapod/cache/` |
| Spotify offline | scoped `du` of `/data/user_de/0/com.spotify.music/Android/data/com.spotify.music/files/spotifycache/` (Device-Encrypted storage, root only — we have it) |
| Apple Music offline | size from heuristic `find` matching audio extensions under `/data/data/com.apple.android.music/files/` (audit didn't fully localize the offline dir) |
| App caches | sum of `StorageStats.cacheBytes` across installed packages — no `du` |

**Button behavior:**

| Button | Underlying op | Logs out? | Cost to user |
|---|---|---|---|
| Clear podcasts | `find /data/data/de.danoeh.antennapod/cache -type f -delete` | no | episodes re-download on play |
| Clear Spotify offline | `find /data/user_de/0/com.spotify.music/.../spotifycache -type f -delete` | no | Wi-Fi re-download |
| Clear Apple Music offline | `find … -delete` on Apple Music offline dir | no | Wi-Fi re-download |
| Clear app caches | `pm trim-caches 99999999999` | no | UI thumbnails + AntennaPod episodes (warned in dialog) |

No button (auto or manual) can log a user out. `pm trim-caches` only touches `cache/`+`code_cache/`+external-cache. Login tokens, accounts, chat history all live in `files/`, `databases/`, `shared_prefs/` — none touched by any of this.

**Implementation details:**

- `StorageRepository` caches results for 60 s, exposes `refresh()` for pull-to-refresh.
- `StorageStatsManager` queries batch in one coroutine; the few scoped `du` queries run in parallel on `Dispatchers.IO`.
- "Clear" buttons dispatch broadcasts to the manual workers — UI never deletes files directly, deletion logic stays in one place per category.
- Rows survive missing packages (Spotify uninstalled, AntennaPod missing) — row hides if size is 0 or package isn't installed, no crash.

**Files to add:**

```
app/src/main/java/com/offlineinc/dumbdownlauncher/storage/
    StorageScreen.kt           # Compose UI
    StorageRepository.kt       # size queries, caching, refresh
    StorageViewModel.kt        # state + clean-now dispatch
    AppCacheCleanupWorker.kt
    AntennaPodCleanupWorker.kt
    SpotifyOfflineCleanupWorker.kt   # auto + manual
    triggers/                  # broadcast receivers
```

Register the new screen in the launcher's app list / navigation graph (look at how `Quack` is wired — same pattern).

## 4. Why no changes to `dumb-phone-configuration`

The pipeline runs once at provisioning; the storage problem is a *runtime* problem. Putting Spotify cache caps or AntennaPod toggles in `automated_configuration.sh` would only help fresh provisions, while the launcher migration approach helps every device on next update. Keeping all storage logic in the launcher also means one APK ships the fix — no need to re-flash devices.

## 5. Audit status (run on a TCL Flip 2 / 4058R on 17 May 2026)

The audit script (`scripts/storage_audit.sh`) was run; findings are folded into Section 0. The implementation-relevant pieces:

**Confirmed paths, locked into the workers:**

- AntennaPod episodes → `/data/data/de.danoeh.antennapod/cache/` (105 MB observed)
- Spotify offline → `/data/user_de/0/com.spotify.music/Android/data/com.spotify.music/files/spotifycache/` (109 MB observed; note the Device-Encrypted location, needs root)
- OpenBubbles attachments → `/data/data/com.openbubbles.messaging/app_flutter/attachments/` (78 MB observed; matches existing worker target)
- WhatsApp media → `/sdcard/Android/media/com.whatsapp/WhatsApp/Media/{Images,Video,.Links}` (10 MB observed; existing worker is doing its job)
- Swap → `/data/swapfile` (256 MB; migration to remove confirmed)
- Total app cache → ~246 MB across `/data/data/*/cache` + `/data/data/*/code_cache`
- Dalvik / ART cache → 12 MB (small, no mitigation needed — flagging it because earlier we'd guessed it might be 500 MB+, it isn't)
- `/data/dropbox`, `/data/anr`, `/data/tombstones` → <4 MB combined, ignored
- `/sdcard/Download`, `DCIM`, `Pictures`, `Music`, `Podcasts` → all <8 MB combined, ignored

**Remaining gap:**

- **Apple Music**: total package is 49 MB, but the audit only localized ~7 MB into named cache subdirs — the other ~42 MB is somewhere under `files/`. The `AppleMusicOfflineCleanupWorker` uses a heuristic extension-based `find` (see Section 1.2) and logs which files matched on first run, so we can tighten the pattern from a real device's debug log once the worker ships.

**Bigger surprise the audit surfaced** (not the kind of thing a worker can fix):

- `/data/app` is **2.23 GB** — half the partition is installed APKs, dominated by OpenBubbles' 695 MB. Detailed in Section 0.1. No worker addresses this; the ABI-filter follow-up is parked for a separate ticket.

## 6. Things you can do today on existing devices (no new code required)

The launcher update will ship the workers and the "Free up space" screen, but for any phone in the field right now:

- `adb shell su -c 'swapoff /data/swapfile && rm -f /data/swapfile'` — instantly frees 256 MB. The new `remove_swap_256m_v1` migration will do this automatically once the next launcher build ships; this is the manual version.
- `adb shell pm trim-caches 99999999999` — instantly clears every app's cache. Same op the "Clear app caches" button will run.
- `adb shell su -c 'find /data/data/com.openbubbles.messaging/app_flutter/attachments -mindepth 1 -delete'` — manual equivalent of the OpenBubbles attachment cleanup (frees ~78 MB on the audit device).
- `adb shell su -c 'find /data/user_de/0/com.spotify.music/Android/data/com.spotify.music/files/spotifycache -type f -delete'` — manual Spotify offline wipe (~109 MB).
- `adb shell su -c 'find /data/data/de.danoeh.antennapod/cache -type f -delete'` — manual AntennaPod episode wipe (~105 MB).
- In WhatsApp on-device: Settings → Storage and data → Manage storage → wipe "Forwarded many times" and any chat over 50 MB. (Cosmetic on the audit device since `/sdcard/...WhatsApp/Media` is already only 10 MB, but useful on phones with heavy WhatsApp users.)
- In AntennaPod on-device: Settings → Network → Automatic Download → off. Not strictly needed once the nightly cache trim is exposed in the UI, but reduces background data.
