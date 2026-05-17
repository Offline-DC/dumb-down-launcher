# Storage-recovery plan for dumb-down-launcher

Phones (TCL Flip 2) ship with 8 GB internal. The launcher already runs three WorkManager cleanups (call log nightly at 2 AM, OpenBubbles attachments weekly at 2 AM, WhatsApp media nightly at 2 AM with a 7-day rolling window) and applies auto-download-off SharedPreferences for OpenBubbles and WhatsApp. This plan tightens those, adds a few more, deletes the 256 MB swap allocation, and exposes a Storage screen the user can open from the launcher.

Decisions locked in: cleanups run **nightly at 4 AM**, Storage UI is a **summary screen with per-app "clear" buttons**, swap is **removed for everyone on next update**, **no changes** to `dumb-phone-configuration`.

## 1. Move all cleanup workers to nightly 4 AM, more aggressive

The current 2 AM schedule and the once-a-week OpenBubbles cadence both go away. Single unified cleanup pass at 4 AM.

**Edits in `DumbDownApp.kt` worker-scheduling block (~lines 78–126):**

- `CallLogCleanupWorker`: reschedule to 4 AM. Keep "newest 50" rule.
- `WhatsAppAttachmentCleanupWorker`: reschedule to 4 AM. Also change retention from `+7 days` to `+0 days` — i.e. wipe every image, video, and `.Links` file every night. Keep the `.nomedia` exclusion. Keep voice notes / docs / GIFs / audio preserved (they're small and high-signal).
- `OpenBubblesAttachmentCleanupWorker`: change from `PeriodicWorkRequest` (weekly) to nightly at 4 AM. Already wipes attachments completely (`find … -mindepth 1 -delete`), just runs more often.

**Use a single helper** `nextRunAt(hour: Int, minute: Int): Long` so all three (and the new ones below) share one scheduling source of truth. Today each worker computes its own delay — easy to drift.

**New worker: `AppCacheCleanupWorker`** — nightly at 4 AM, runs `pm trim-caches 999999999` (Android's built-in "free up to N bytes of cache"). This drains `/data/data/*/cache` and `/data/data/*/code_cache` for every app without needing per-app knowledge. Single `su` shell call, very fast, very safe — Android invented this hook specifically for low-storage devices.

**New worker: `AntennaPodCleanupWorker`** — nightly at 4 AM. Episodes by default live under either `/data/data/de.danoeh.antennapod/files/media/` or `/sdcard/Android/data/de.danoeh.antennapod/files/media/`. Wipe both with `find … -type f -mtime +0 -delete` and also poke AntennaPod's prefs to disable auto-download. Episodes the user listens to today survive (mtime resets on download); anything older is gone. Confirm exact paths from the audit script output before committing.

**New worker: `SpotifyAppleMusicCleanupWorker`** — nightly at 4 AM. Clears each package's cache via `pm trim-caches` is already covered by the generic worker, but for Spotify/Apple Music the *offline downloads* live in `files/` not `cache/`, so add explicit `find /data/data/<pkg>/files/<offline-dir> -type f -delete`. Audit script will confirm the exact dirnames per phone — don't hardcode until we see real output.

**Receivers:** add an exported `BroadcastReceiver` for each new worker so they can be triggered manually via `adb shell am broadcast` (matches the existing `WhatsAppAttachmentCleanupTriggerReceiver` pattern). These also back the "Clean now" buttons in the UI.

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

## 3. In-launcher Storage screen

A new Compose screen registered like the other in-launcher mini-apps (Snake, Weather, Quack, etc.). Reachable from the AllApps grid with its own tile.

**Layout, top to bottom:**

1. **Free / total bar** at the top (e.g. "1.4 GB free of 8.0 GB"). Read via `StorageStatsManager.getFreeBytes(UUID_DEFAULT)` / `getTotalBytes(UUID_DEFAULT)` — instant, no walk. Color goes amber under 15% free, red under 5%.
2. **"Last auto-clean" line** — timestamp + bytes freed, read from a small `SharedPreferences` "last_cleanup_result" record that each worker writes when it finishes.
3. **Per-category rows.** Each row shows category name, current size, and a "Clean now" button:
   - WhatsApp media — `du` scoped to `/sdcard/Android/media/com.whatsapp/WhatsApp/Media`. Subdir-level breakdown (Images / Video / Links) is its own walk, but each is a single directory so it's cheap.
   - OpenBubbles attachments — `StorageStats.dataBytes` for the package, then `du` of `app_flutter/attachments` to isolate the attachment slice.
   - AntennaPod episodes — `du` of the episode dir surfaced by the audit script.
   - Spotify offline downloads — `du` of `files/spotifycache/` (or whatever the audit pins down).
   - Apple Music offline downloads — `du` of the per-version offline dir.
   - App caches (system-wide) — sum of `StorageStats.cacheBytes` over all installed packages, no `du`.
   - Call log — row count from `ContentResolver`, not bytes.

   For total app data per category we use `StorageStatsManager.queryStatsForPackage(UUID_DEFAULT, pkg, Process.myUserHandle())`, which returns instantly with `appBytes` / `dataBytes` / `cacheBytes` from the kernel quota tracker. The launcher self-grants `PACKAGE_USAGE_STATS` via `su appops set com.offlineinc.dumbdownlauncher GET_USAGE_STATS allow` on first run, alongside the other self-granted permissions.

4. **"Clean everything now" button** at the bottom, fires every receiver in sequence.

**What each "Clean now" button actually does** (and what the user loses):

| Button | Underlying op | Logs out? | What it costs the user |
|---|---|---|---|
| App caches | `pm trim-caches 99999999999` | no | UI thumbnails, web-link previews, shader caches — auto-rebuilt on next use |
| WhatsApp media | `find /sdcard/Android/media/com.whatsapp/WhatsApp/Media/{Images,Video,.Links} -type f ! -name .nomedia -delete` | no | redownload on-demand from WhatsApp servers (within retention window). Voice notes, docs, GIFs, audio preserved. |
| OpenBubbles attachments | `find /data/data/.../app_flutter/attachments -mindepth 1 -delete` | no | iMessage re-delivers on demand |
| Spotify offline | `find /data/data/com.spotify.music/files/spotifycache -type f -delete` | no | offline-downloaded tracks gone — needs Wi-Fi re-download to be offline again |
| Apple Music offline | `find /data/data/com.apple.android.music/files/<offline dir> -type f -delete` | no | same as Spotify |
| AntennaPod episodes | `find <episode dir> -type f -delete` | no | listened episodes already gone via the per-episode auto-delete (if user has it on); downloaded-but-unlistened ones need re-download |
| Clean everything | fires all of the above in sequence | no | union of the above |

**No "Clean now" button can log a user out.** `pm trim-caches` only touches `cache/`+`code_cache/`+external-cache. Login tokens, account state, chat history all live in `files/`, `databases/`, and `shared_prefs/` — none of which the cache trim touches. The per-app workers above delete specific *data* directories (attachments, offline music, episodes) but not auth state.

**Confirmation dialogs** only on Spotify offline + Apple Music offline + "Clean everything" — these force re-download cost on Wi-Fi. Cache/thumbnail/attachment cleanups fire immediately, no dialog.

**Implementation details:**

- `StorageRepository` caches results for 60 s and exposes a `refresh()` for pull-to-refresh.
- `StorageStatsManager` queries are batched in one coroutine, `du` queries (the few we still need) run in parallel via `Dispatchers.IO`.
- All "Clean now" buttons dispatch the existing broadcasts the WorkManager workers use. UI never deletes files directly — keeps deletion logic in one place.
- Size helpers survive missing packages (Spotify uninstalled, AntennaPod missing, etc.) — show "—" instead of crashing.

**Files to add:**

```
app/src/main/java/com/offlineinc/dumbdownlauncher/storage/
    StorageScreen.kt           # Compose UI
    StorageRepository.kt       # size queries, caching, refresh
    StorageViewModel.kt        # state + clean-now dispatch
    AppCacheCleanupWorker.kt
    AntennaPodCleanupWorker.kt
    SpotifyAppleMusicCleanupWorker.kt
    triggers/                  # broadcast receivers
```

Register the new screen in the launcher's app list / navigation graph (look at how `Quack` is wired — same pattern).

## 4. Why no changes to `dumb-phone-configuration`

The pipeline runs once at provisioning; the storage problem is a *runtime* problem. Putting Spotify cache caps or AntennaPod toggles in `automated_configuration.sh` would only help fresh provisions, while the launcher migration approach helps every device on next update. Keeping all storage logic in the launcher also means one APK ships the fix — no need to re-flash devices.

## 5. Before writing any code: run the audit script

I dropped `scripts/storage_audit.sh` in the repo. Run it on a real TCL Flip 2 (instructions at the top of the file). The output will pin down:

- The exact AntennaPod episode directory on this Android version (could be private or external).
- The exact Spotify / Apple Music offline-cache subdirs (these vary by app version).
- Which `/data/data/*/cache` apps are actually big (so we know which `pm trim-caches` targets matter).
- Whether `/data/dalvik-cache` is bloated (sometimes 500 MB+ — there are mitigations).
- Whether anything is squatting in `/sdcard/Download` or `/sdcard/DCIM` that we haven't thought of.

**Paste the output back to me and I'll** (a) finalize the exact paths in the new workers, (b) flag any surprise storage hogs the plan doesn't cover, and (c) start writing the code.

## 6. Things you can do today (no code change required)

- Long-press → uninstall any app that the audit shows >200 MB and isn't in the core list (WhatsApp, OpenBubbles, Spotify, Apple Music, AntennaPod, Chrome, Maps Lite, Uber Lite, Authenticator, the launcher itself).
- In WhatsApp: Settings → Storage and data → Manage storage → wipe "Forwarded many times" and any chat over 50 MB. The new nightly worker will keep it clean afterward.
- In AntennaPod: Settings → Network → Automatic Download → off. The new worker will enforce this but doing it manually now buys time.
- `adb shell pm trim-caches 99999999999` if you have a device on hand — instantly clears everyone's cache; ships as a worker but you can do it ad-hoc right now.
