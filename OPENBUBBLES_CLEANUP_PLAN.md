# OpenBubbles cleanup plan (message DB + notification coalescing)

Companion to `STORAGE_PLAN.md`. That document is about disk pressure on the TCL Flip 2; this one is about the **RAM + first-open glitch** symptom: users who don't touch the phone for a few days, come back to dozens of stacked OpenBubbles notifications, tap Bubbles, and watch it grind for several seconds on a 916 MB-RAM device before the chat list draws.

Decisions locked in (per chat 21 May 2026):

- Retention rule: **delete OpenBubbles messages older than 7 days, except keep the most recent 5 in each thread/chat** so no conversation goes empty in the chat list. NOTE post-diagnostic: see §0.2 — the 21 May second diagnostic confirmed the engine is ObjectBox (not SQLite) and the message store is only 7.7 MB after 5 weeks of real use, so DB pruning is **deferred to Tier C** in the revised priority order. The 7-day / floor-of-5 rule still describes what the worker *should* do if and when we build it, but is no longer load-bearing for the first-open glitch.
- Schedule: **nightly at 4 AM, chained immediately after** `OpenBubblesAttachmentCleanupWorker`. Same WorkManager continuation, same shared `stopQuietly()` so OB is only killed once for the whole pair of jobs.
- Notification coalescing: **when OB has more than 10 active notifications**, collapse them into a single summary notification from the launcher, so the system notification shade isn't doing per-conversation render work after a quiet stretch.
- No upstream OpenBubbles changes (yet). Everything lives in the launcher, same way `OpenBubblesAttachmentCleanupWorker` already operates on OB's private data from the outside via root. If the Tier C DB pruning ever gets built, that calculus may change because the cleanest path through ObjectBox is upstream cooperation.

## 0. What the 21 May diagnostics told us

Run on the audit TCL Flip 2 (model 4058R, fresh install — not the high-volume case yet). Notable signals:

- `MemFree: 15 MB`, `MemAvailable: 294 MB`, **zram swap 425 MB used of 671 MB**. Real memory pressure, exactly as `STORAGE_PLAN.md §0` warned.
- PSI `some avg10=0.31` — a process is stalled on memory ~31% of any given 10-second window even at idle.
- Load average 18 on a quad-core. The phone is consistently busy.
- OpenBubbles is up: 140 MB PSS + 24 MB swapped, **three foreground services** (`APNService`, `NotificationListener`, `GeolocatorLocationService`). That's the steady-state RAM cost before any message work begins.
- **22 OB notifications already posted on a clean phone.** Each on its own per-conversation channel (`com.bluebubbles.new_messages.<UUID>`). The 10-notification coalescing threshold is hit on day-one usage, not just after a long absence.
- No `lowmemorykiller` / OOM hits in `dmesg` yet. The kernel is keeping things alive at the cost of swap churn (2.2 M pages swapped out over 15 h uptime).
- **Could not locate the OB message database in the first diagnostic pass.** Only DBs `dumpsys meminfo` reports are `com.google.android.datatransport.events` (Firebase) and `androidx.work.workdb` (WorkManager). The find scanned `*.db` / `*.sqlite` / `*.sqlite3` and got nothing. Likely cause: BlueBubbles upstream uses ObjectBox, which stores LMDB files as `data.mdb` / `lock.mdb` under `app_flutter/objectbox/` with no `.db` extension. Confirmed by the second diagnostic — see §0.2.

### 0.2 Second diagnostic on a 5-day-uptime, ~5-weeks-used phone (TCL 4058G)

Run on a phone that has been receiving iMessages for ~5 weeks (`/data/data/com.openbubbles.messaging/app_flutter/objectbox/data.mdb` mtime 2026-04-13 → 2026-05-21). This is the load-realistic device, and it changed the priority order in §1.

**Engine confirmed: ObjectBox.**

- `app_flutter/objectbox/data.mdb` exists, **7.7 MB**.
- `app_flutter/objectbox/lock.mdb` exists, 8 KB (LMDB lock file).
- APK ships `libobjectbox-jni.so` (1.5 MB) and no `libsqlite*.so` for messages.
- No SQLite tables anywhere relevant — the only SQLite DBs OB owns are the same Firebase + WorkManager + Mixpanel analytics ones from the fresh device.

**The message DB is small.** 7.7 MB after 5 weeks of real iMessage use is *not* where the cold-start glitch is coming from. This invalidates the original framing where DB pruning was Tier A — it's now Tier C in the revised priority order (§1.0).

**The real cold-start cost is paged-out working set, not DB read.** OB's `dumpsys meminfo` on this device:

- TOTAL PSS: **106 MB**
- TOTAL SWAP PSS: **78 MB** (~74 MB of OB's working set is currently in zram swap)
- Process state: `S (sleeping)`, `lastActivity=-14h14m` — fully dormant
- `MemFree: 16 MB`, **PSI `some avg10=41.97`** — processes are blocked on memory ~42% of the last 10 s

When the user taps Bubbles at 9 AM after the phone idles overnight, the kernel has to fault ~74 MB back in from zram while the system has 16 MB of free RAM and is already evicting other things. The disk-side DB read is comparatively trivial. **A nightly OB restart that forces a fresh working set is the single highest-leverage intervention** — it lands in Tier A.

**Notification count: 60.** Up from 22 on the fresh phone. All on per-conversation channels (`com.bluebubbles.new_messages.<UUID>`) under `groupKey=NOTIFICATION_GROUP_NEW_MESSAGES`. OB already groups within a conversation; what we need is cross-conversation collapsing (§2). The 60-notification number is at-rest steady state, not after-absence — so the symptom never goes away on its own without intervention.

**New cleanup target: OB log files.** The second diagnostic surfaced ~20 MB of write-only debug logs in two directories OB never reads back at runtime:

- `files/logs/rs_rCURRENT.log` — 5.1 MB (rust-side push relay current log)
- `files/logs/rs_r00049.log` — 5.2 MB (rust-side rotated)
- `app_flutter/logs/bluebubbles-2026-05-07-*.log` — 3.8 MB
- `app_flutter/logs/bluebubbles-2026-05-14-*.log` — 3.2 MB
- `app_flutter/logs/bluebubbles-2026-05-02-*.log` — 1.0 MB
- `app_flutter/logs/bluebubbles-2026-05-06-*.log` — 1.2 MB
- `app_flutter/logs/bluebubbles-latest.log` — 731 KB

OB rotates these on its own but never deletes the rotated ones. Same shape as attachments: write-only churn the user doesn't benefit from. New Tier B worker section — see §1.6.

**Swap file still present.** `/swapfile` (262 MB) is still on `/proc/swaps` on this device — confirms the `remove_swap_256m_v1` migration from `STORAGE_PLAN.md §2` hasn't shipped to it yet. Not in scope for this plan but worth noting that the swap-removal migration is a partial mitigation for the working-set thrash described above too (less swap target = kernel must keep more pages in RAM or kill processes, which is at least a more visible failure mode than slow first-open).

### 0.3 Third diagnostic on a ~2-month-used phone (TCL 4058G, M7FU9D5PUOUKKBLJ)

Run on the longest-lived phone we've sampled (`objectbox/` dir created 2026-03-17, 2+ months ago). Boot reason `2sec_reboot` — the user hold-the-power-button hard-reset, which is itself a soft signal that this phone has been frustrating someone recently. Three things changed the plan:

**The message DB does grow with use.** `data.mdb` was 7.7 MB at 5 weeks on device 2, and is now **43 MB at ~2 months** on this device. Linear extrapolation puts the file at ~80-100 MB by month 4 and 150+ MB by month 6 of heavy iMessage use. The Tier C deferral remains correct *for now* but the timeline for revisiting is months, not years. The 100 MB-of-data.mdb trigger in §3 is a reasonable threshold — about half a year out from a clean install.

**Cold-start swap-out is chronic.** This device had only 1h36m of uptime since the `2sec_reboot`, and OB already has **81 MB SWAP PSS**. So the dormant-working-set-paged-out problem isn't a multi-day-idle phenomenon — it kicks in within hours. The nightly OB restart in §1.5 is more durable than originally framed because it addresses a problem that starts re-accumulating immediately after the reset.

**Avatar/poster cache is the biggest reclaimable target — bigger than the message DB.** The 21 May `openbubbles_find_db.sh` section 1 inventory on this device shows `app_flutter` at 137 MB total, with a sizeable fraction in `app_flutter/avatars/`. The breakdown reveals iOS 17+ Contact Posters cached at every variant OB needs:

| Path | Content | Typical size |
|---|---|---|
| `avatars/you/poster-NNNNNN.jpg` | Primary poster image (top-level) | 1-4 MB each |
| `avatars/you/poster-NNNNNN/<sha256>` | HEIC original (no extension) | 100-700 KB each |
| `avatars/you/poster-NNNNNN/<sha256>.png` | PNG rendered at one of several sizes | 1-5 MB each |
| `avatars/<contact-uuid>/avatar-NNNNNN.jpg` | Per-contact-id traditional avatar | 100 KB-1 MB each |

A single Contact Poster can occupy 15-25 MB across its variants. With 8 posters in the `you/` subdir (`poster-1195410`, `poster-1264034`, `poster-4463096`, `poster-7087822`, `poster-7991874`, `poster-743967`, `poster-8921970`, `poster-9438853`), the total avatar/poster footprint is **~60-80 MB on this device alone** — comfortably bigger than the 43 MB ObjectBox `data.mdb`. The hash-named files are content-addressed and safe to delete; OB re-renders them from the source poster on next display. Promoted to Tier B as §1.6.2 below.

## 1. Implementation — priority order revised after 21 May diagnostics

### 1.0 Tier A / B / C ordering

The original framing put DB pruning at the center. The second diagnostic (§0.2) said: DB is 7.7 MB after 5 weeks, OB is sitting on 78 MB of swap PSS, 60 notifications are already posted at steady state, and 20 MB of unread logs are accumulating. So the priority order is:

- **Tier A — low risk, high impact, ship first.** §1.5 (nightly OB restart at 4 AM) and §2 (notification coalescing). Neither touches OB's private data files. Both attack the symptoms the diagnostic shows are actually dominant. Expected combined win: probably 60–70% of the perceived first-open glitch.
- **Tier B — low risk, medium impact, ship second.** §1.6.1 (OB log file cleanup, ~20 MB) and §1.6.2 (OB avatar/poster cache pruning, ~60-80 MB on a 2-month device — see §0.3). Both are pure file-delete workers, neither touches OB's databases, neither has any risk to message history. The avatar prune is the single biggest reclaimable byte target identified so far in either plan.
- **Tier C — medium risk, low impact at current DB sizes, defer.** §1.1–§1.4 (message DB pruning). Engine is ObjectBox; the safe path is an upstream OB PR or JNI integration, both of which are weeks of work. At 7.7 MB after 5 weeks the storage cost doesn't justify either yet. Revisit when a phone-in-the-field reports a `data.mdb` over ~100 MB. The 7-day / floor-of-5 rule from the chat is still the right semantics for that worker when it gets built; the design below stays accurate for that future.

### 1.1 Engine-conditional (resolved: ObjectBox)

This whole section was originally written to be engine-conditional. The second diagnostic resolved it: OpenBubbles uses **ObjectBox**, confirmed by `libobjectbox-jni.so` in the APK and `data.mdb` / `lock.mdb` files in `app_flutter/objectbox/`. The SQLite branch below is preserved for historical/contrast purposes; only the ObjectBox branch is actionable.

- **SQLite path (sqflite / Drift / Floor):** straightforward. We open the DB file from native code, run a parameterized DELETE with the schema we fingerprinted, VACUUM, close. The §1.3 SQL idiom applies.
- **ObjectBox path (most likely, based on the upstream BlueBubbles dependency):** much harder. ObjectBox is an LMDB-backed key-value store with a Dart/Java runtime that owns the schema. You cannot reliably write to an ObjectBox file from outside the app — touching the bytes without the runtime risks corrupting the store. Three options if this is what we have, in order of preference:
  1. Land an upstream OpenBubbles PR that exposes a "retention worker" or a maintenance broadcast we can trigger from the launcher. This is the right long-term fix because it lets the OB process do the delete using its own ObjectBox runtime.
  2. Ship the ObjectBox JNI library in the launcher and operate on the OB store with the engine's official API. Cost: ~5 MB binary bloat in the launcher, plus version-coupling pain when OB upgrades ObjectBox.
  3. Last-resort all-or-nothing wipe: stop OB, delete `app_flutter/objectbox/`, restart OB. OpenBubbles will resync history from the iMessage relay. Doable but heavy, and only worth it on devices where the DB is genuinely huge — i.e., the same shape as the Spotify size-gated wipe in `STORAGE_PLAN.md §1.1`.

Until §3 confirms the engine, **assume ObjectBox** for risk-planning and treat any SQLite-shaped code below as the happy path.

### 1.2 Worker structure (the parts that don't depend on the engine)

New worker class `OpenBubblesMessageCleanupWorker` next to the existing `OpenBubblesAttachmentCleanupWorker`. Follows the same conventions as that worker and `SpotifyOfflineCleanupWorker`:

- `CoroutineWorker` with an exported `BroadcastReceiver` so adb / the Free Up Space UI can fire it manually.
- Writes its own `last_run_at_ms` + `bytes_freed` + `rows_deleted` entries to the `storage_cleanup` SharedPreferences file, with a `Target.OPENBUBBLES_MESSAGES` enum value added to `StorageCleanupOps`.
- Reuses `OpenBubblesOps.stopQuietly()` to kill OB before touching its data. Same focused-app deferral semantics — if OB is focused, throw and let the worker skip this run.

The nightly chain becomes:

```
4:00 AM  →  CallLogCleanupWorker
            (independent — uses ContentResolver, no OB interaction)

4:00 AM  →  WhatsAppAttachmentCleanupWorker
            (independent — different package)

4:00 AM  →  OpenBubblesAttachmentCleanupWorker
            ├─ OpenBubblesOps.applyAutoDownloadOff()   (existing)
            ├─ OpenBubblesOps.stopQuietly()             (existing)
            ├─ wipe app_flutter/attachments              (existing)
            └─ enqueue OpenBubblesMessageCleanupWorker  (NEW chain link)
                       │
                       ├─ snapshot DB file              (NEW)
                       ├─ fingerprint schema            (NEW)
                       ├─ delete-7d-keep-5              (NEW)
                       ├─ VACUUM / compact              (NEW)
                       └─ launch OB foreground service  (NEW — see §1.5)
```

Why chain instead of two independent periodic jobs: OB has to be killed for both, and killing it twice is twice the chance of looking like a crash to the user. Doing them back-to-back inside one OB-down window also halves the time the iMessage relay's foreground service is absent.

### 1.3 The SQL idiom (assumes SQLite — replace with engine-equivalent if ObjectBox)

The "delete older than 7 days but always keep the most recent N per thread" rule needs a window function. SQLite 3.25+ has these and Android 11 ships SQLite 3.32+, so we're fine.

```sql
-- $DATE_COL_CUTOFF is "device now minus 7 days" expressed in whatever unit
-- the column uses (s, ms, or Apple-epoch ns — fingerprinted at runtime, see §1.4).
DELETE FROM <messages_table>
WHERE rowid IN (
  SELECT m.rowid
  FROM <messages_table> m
  WHERE m.<date_col> < $DATE_COL_CUTOFF
    AND m.rowid NOT IN (
      SELECT rowid FROM (
        SELECT rowid,
               ROW_NUMBER() OVER (
                 PARTITION BY <thread_col>
                 ORDER BY <date_col> DESC
               ) AS rn
        FROM <messages_table>
      ) WHERE rn <= 5
    )
);
```

The inner subquery computes a per-thread rank by recency; rows with rank ≤ 5 are exempt. The outer `WHERE` adds the 7-day cutoff. Bound to the existing `prepareStatement` / `bindLong` pattern in Kotlin native SQLite (`android.database.sqlite.SQLiteDatabase`).

Followed by:

```sql
PRAGMA wal_checkpoint(TRUNCATE);
VACUUM;
```

Without the VACUUM the file size on disk doesn't shrink, only the free pages inside it — which means the storage win is invisible and the next big batch of incoming messages just refills the freed pages. `VACUUM` is slow (rewrites the whole file) but acceptable at 4 AM with OB stopped.

### 1.4 Safety properties (the "not too brittle" part)

The user's exact concern is brittleness. Five defenses, layered:

1. **Snapshot first.** Before opening the DB for writes, `cp` the file (and `-wal` / `-shm` siblings) to `/data/local/tmp/ob_msg_snapshots/<timestamp>.db`. Keep the last 3 snapshots, rotate. If something corrupts the DB, the user can be unbricked from a snapshot less than 72 hours old. No worker should be one bug away from "lost everyone's iMessage history."
2. **Schema fingerprint, refuse to run if it changed.** First run records (table names, the chosen date column name + inferred unit, the chosen thread column name) to `shared_prefs`. Every subsequent run re-derives those from `sqlite_master` + `pragma table_info(...)` and aborts cleanly with a logged "schema drift detected, not deleting" if anything moved. The `sqlite_master` checksum is the boring-but-correct version of this — store its sha256 in prefs and refuse to run if it changes until a human reviews. Equivalent for ObjectBox is the schema id from the entity definitions, also stable across runs unless OB ships a model migration.
3. **Date-column unit detection, with sanity range.** OpenBubbles is iMessage-shaped, and iMessage timestamps are notoriously **nanoseconds from 2001-01-01** (Apple Cocoa epoch). The runtime detection looks at `MAX(date_col)` and picks among `{ unix seconds, unix ms, Apple-epoch ns }`. The chosen unit must place `MAX(date_col)` within the last 7 days of wall time — if not, refuse to run. This catches the case where upstream changes the unit and our fixed magnitude check picks wrong.
4. **Read flag exemption.** Never delete an unread message regardless of age. The notification posted in the system shade points at the message row; deleting it under the notification gives the user a notification that taps into nothing. Predicate becomes `WHERE <date_col> < cutoff AND <unread_col> = 0`. If we can't fingerprint a reliable read flag, fall back to deleting only messages > 14 days old (a soft buffer) and log loudly.
5. **Dry-run on first deployment.** Ship the worker with a `OB_MSG_CLEANUP_DRY_RUN` remote flag (the launcher already reads remote config; see `DumbDownApp.kt`). First two weeks the worker runs the SELECT but skips the DELETE, logging `would delete N rows`. Compare logged counts against expected on a couple of staging phones, then flip the flag.

### 1.5 Bringing OB back up cleanly

After the delete + VACUUM, we don't want to leave OB stopped — the iMessage push relay is exactly the foreground service that needs to be alive at 4 AM to receive messages while the user sleeps. Two options:

- **Do nothing.** OB's `APNService` is `START_STICKY`; the system will respawn it within 10-30 s on its own. Cheapest, but leaves a window where the user's phone isn't connected to iMessage.
- **Explicit restart.** Issue `am start-foreground-service --user 0 com.openbubbles.messaging/com.bluebubbles.messaging.services.rustpush.APNService`. Foreground service intent matches what `dumpsys activity services` showed in the 21 May diagnostic, so the service component name is correct. Wait 5 s, verify with `pidof com.openbubbles.messaging`.

Prefer the explicit restart — it also doubles as the "cold-start warmup" mentioned in chat, so the user's 8 AM tap is hitting an already-warm OB process whose working set is mostly resident again.

### 1.6 OB write-only file cleanup (Tier B — ship second)

Two sibling workers, both nightly at 4 AM in the same chain, both pure file-delete with no DB involvement. Neither requires `OpenBubblesOps.stopQuietly()` because neither touches files OB holds open. They run sequentially after the attachment cleanup (which already stopped OB) and before the deferred message cleanup, in the order log → avatars → (message).

#### 1.6.1 OB log file cleanup (`OpenBubblesLogCleanupWorker`)

Wipes OB's own debug log files. The 21 May diagnostic found ~20 MB of these on the 5-week-used device; OpenBubbles rotates them on its own but never deletes the rotated ones, so the volume only grows.

**Target paths (confirmed by §0.2):**

- `/data/data/com.openbubbles.messaging/files/logs/` — the rust-side push relay logs. The "current" file (`rs_rCURRENT.log`, 5 MB) is being actively appended to; the rotated `rs_rNNNNN.log` files are dead.
- `/data/data/com.openbubbles.messaging/app_flutter/logs/` — the Flutter-side logs. Same pattern: a `bluebubbles-latest.log` is current, the dated `bluebubbles-YYYY-MM-DD-*.log` files are rotated.

**Behavior:**

- For both dirs, delete every file *except* the currently-active one (`rs_rCURRENT.log` and `bluebubbles-latest.log`). Identifying the current one is done by name match — those two filenames are stable across rotations.
- No DB involvement, no `stopQuietly()` required. The rotated log files aren't held open by OB; only the active ones are. So this worker can run with OB live, unlike the attachment / message workers.
- Defense: if the worker is unsure which file is current (e.g., the filename pattern doesn't match expectations after an OB update), it skips that directory entirely and logs `log cleanup: skipped <dir> — could not identify active log`. Same fingerprint-or-skip pattern as the message worker.

**Safety properties:**

- These files are exposed only to OB itself (`u0_a112` ownership). The only consumer is the OpenBubbles developer who has access to the device for debugging. Wiping them does not affect runtime behavior in any way — OB will recreate them on next write.
- The same `cp` snapshot pattern from §1.4 is overkill here; just delete.
- Storage win on the audit device: ~20 MB. Recurring win — log volume grows ~3-4 MB/week, so this worker reclaims that per week per device.

#### 1.6.2 OB avatar/poster cache pruning (`OpenBubblesAvatarCleanupWorker`)

This is the biggest reclaimable target identified by any diagnostic so far — see §0.3. On a 2-month-used phone, the avatar/poster cache is ~60-80 MB, larger than the message DB itself.

**Target paths:**

- `/data/data/com.openbubbles.messaging/app_flutter/avatars/you/poster-*/` — per-poster subdirectories holding `<sha256>` (HEIC original) + `<sha256>.png` (rendered) files. **The cleanup target.**
- `/data/data/com.openbubbles.messaging/app_flutter/avatars/you/poster-*.jpg` — primary poster image at the parent level. **NOT a cleanup target.** Keep these; they're small (1-4 MB total across all posters), they're the canonical source OB uses, and deleting them might force OB to re-fetch from the iMessage relay.
- `/data/data/com.openbubbles.messaging/app_flutter/avatars/<UUID>/avatar-NNNNN.jpg` — traditional per-contact avatars. **Borderline:** small (100 KB-1 MB each), and there's no derived/rendered layer to prune. Skip for now; revisit if §1.6.2 by itself doesn't deliver enough.

**Behavior:**

Two viable strategies, pick the conservative one for v1 and tighten later:

- **v1 (recommended): age-bounded.** For each `avatars/you/poster-*/` subdir, delete files whose `mtime` is older than 14 days. Keeps recently-displayed posters warm; trims long-tail. Easy to revert per-file if a bug surfaces — every file is regeneratable.
- **v2 (more aggressive, only if v1 isn't enough): blanket prune.** Empty every `avatars/you/poster-*/` subdir unconditionally, keeping only the parent `poster-*.jpg`. Reclaims maximum bytes. Risk: brief grey-placeholder flash the first time the user opens a chat with that poster after the worker runs, while OB re-renders.

v1 wins because the user is on a 1 GB phone and any worker that introduces a visible UI hiccup is going to feel worse than the symptom it's supposedly fixing.

**Safety properties:**

- Same `find ... -mtime +N -delete` pattern as the existing attachment cleanup, scoped per-subdir.
- Never deletes the parent `poster-*.jpg`. The find predicate uses `-mindepth 2` from `avatars/you/` so it can't possibly hit a parent-level file.
- Logs file count + bytes freed, same as the other workers, to the `storage_cleanup` SharedPreferences.
- The hash-named files are content-addressed by definition (sha256 of original poster). They have no semantic value as "data" — they're a derived render cache. Worst case if we delete one OB still wants: OB re-renders from the parent `.jpg`, costs ~50 ms of CPU and a brief placeholder.

**Storage win:**

Per-device estimate based on §0.3:

- Device 1 (fresh): nothing to clean, dir doesn't have any large posters yet.
- Device 2 (5 weeks): a few posters present, maybe ~10-15 MB cleanable.
- Device 3 (2 months, 8 posters): ~40-60 MB cleanable at 14-day mtime cutoff, ~60-80 MB at blanket prune.

The recurring win depends on user behavior. A user who texts the same 5 contacts every day will keep their posters fresh (mtimes touched) and we won't trim them — which is correct. A user with 20 once-a-month contacts will see steady ~50 MB reclaim per worker run after the first.

**Where the two workers slot into the chain:**

```
OpenBubblesAttachmentCleanupWorker
    └─ enqueue OpenBubblesLogCleanupWorker          ← Tier B (§1.6.1, ~20 MB)
            └─ enqueue OpenBubblesAvatarCleanupWorker   ← Tier B (§1.6.2, ~60 MB)
                    └─ (Tier C, deferred) OpenBubblesMessageCleanupWorker
                            └─ launch OB foreground service
```

Both Tier B workers run after attachment cleanup so OB is already stopped — cleaner because OB holds the current rust log open with a write handle and may have an open file descriptor for any poster it last rendered. Neither *requires* OB to be down, but doing it inside the existing OB-down window is free defense-in-depth. They run before the (deferred) message cleanup because they're the cheap easy wins — even if the message cleanup defers or fails, the log + avatar wins are banked.

### 1.7 What the message worker does NOT touch

- The conversation/thread rows themselves. We delete *messages within* a thread; we never delete a thread. Empty-looking threads are still useful UI affordances ("contact X — no recent messages").
- Attachments are already handled by `OpenBubblesAttachmentCleanupWorker` immediately before. If a deleted message had an attachment row pointing into the attachments dir, that row becomes orphaned — fine, OB ignores orphaned attachment refs (we tested this in the storage_audit phase: wiping attachments doesn't break message rendering).
- The contacts cache (`handles` / `participants` table or equivalent). Leaving these intact means a thread with 0 visible messages still has a recognizable name.
- Auth tokens, push credentials, the rust-side iMessage state. All in `shared_prefs` and the rust side's own store — completely separate from the message DB.

## 2. Notification coalescing — design

### 2.1 The trigger

"More than 10 OB notifications active in the system shade" → collapse. The diagnostic showed 22 already on a clean phone, so the threshold gets hit immediately, not just after a multi-day absence. That's fine: the design is meant to run continuously, not only after absence.

### 2.2 Where it lives

A new `OpenBubblesNotificationCoalescer` extending `NotificationListenerService`, registered in the launcher's manifest with `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` and granted at provisioning via the existing `cmd notification allow_listener com.offlineinc.dumbdownlauncher/...` pattern used for the other launcher listeners.

Note the collision: OpenBubbles itself runs a `NotificationListener` (per `dumpsys activity services`, that's the `com.bluebubbles.messaging.services.notifications.NotificationListener`). Two NLS instances can co-exist — Android delivers events to all bound listeners — so the launcher's listener observing OB's posted notifications is independent of OB observing other apps' notifications.

### 2.3 The behavior

The listener observes `onNotificationPosted(StatusBarNotification sbn)` and `onNotificationRemoved(...)`. It maintains an in-memory count keyed by `sbn.packageName == "com.openbubbles.messaging"`. When the count crosses 10:

1. Cancel each individual OB notification by key (`cancelNotification(key)`).
2. Post a single new notification from the launcher's own package, on a launcher-owned channel `ob_summary`, with text `"{N} new iMessages — tap to open Bubbles"`. Tap intent: launch OB's main activity. The notification is `setOnlyAlertOnce(true)` so it doesn't re-buzz the user as new messages arrive.
3. Add an in-memory "we are in summary mode for OB" flag. While in summary mode, every new OB notification observed is immediately cancelled and the summary count is incremented.
4. Exit summary mode when the user taps the summary (cleared by `setAutoCancel(true)`), or when OB is launched into the foreground (observed via `onNotificationRemoved` for OB's foreground-service notification when the user opens OB → ActivityManager broadcasts focus).

### 2.4 Why we're not just relying on OB's own grouping

OpenBubbles already uses `setGroup(...)` per conversation channel; the system collapses *within a conversation* in the shade. But it does **not** collapse *across conversations* — 22 conversations means 22 separate top-level entries in the notification shade, each of which the system has to render. On a 1 GB phone with the shade pulled down, that's measurable jank. Cross-conversation collapsing is what this listener does.

### 2.5 What this listener does NOT touch

- The actual messages, the OB DB, OB's push channel registrations. All untouched.
- WhatsApp / Spotify / other apps' notifications. Filter is strict on `packageName == "com.openbubbles.messaging"`.
- OB's foreground service notification (the persistent low-importance "OpenBubbles is running" thing). That has `flags & Notification.FLAG_FOREGROUND_SERVICE != 0` and we skip it explicitly — cancelling it would kill the iMessage relay.

## 3. Open work / what to build next

The engine gating question is resolved (ObjectBox, see §0.2). Order of work:

1. **Tier A, ship first.** Build §1.5 (nightly OB restart at 4 AM, chained after attachment cleanup) and §2 (notification coalescing >10). Neither requires any new file-touching work; both are straightforward additions to existing patterns (`OpenBubblesOps.stopQuietly()` + `am start-foreground-service` for the restart; new `NotificationListenerService` for the coalescer). Validate by re-running `scripts/openbubbles_diagnose.sh` on a phone after a multi-day idle window — `1b` PSS should not show the 78 MB swap-out, `2a` notification count should stay near 1.
2. **Tier B, ship second.** Build §1.6 (`OpenBubblesLogCleanupWorker`). Simplest of the three — pure file delete with a stable filename allowlist.
3. **Tier C, defer — but not indefinitely.** Build the §1.1–§1.4 ObjectBox message worker when phones in the field show `data.mdb` over ~100 MB. Per §0.3 the growth trajectory is roughly 20 MB/month on a heavy user, so the 100 MB trigger will land around month 4-5 from a clean install — i.e., we have a runway of one or two release cycles to get Tiers A and B shipped and gathering field data first. When that happens, the right first step is **not** the JNI integration — it's a PR to OpenBubbles upstream exposing a `BroadcastReceiver` that triggers a `MessagePruneService` running the 7d / floor-of-5 query through ObjectBox's own Dart runtime. The launcher then fires that receiver from its nightly chain. This is the only path that's both correct (ObjectBox's runtime owns the data layout) and durable (survives OB schema changes for free). The JNI and nuclear-wipe options stay in §1.1 as fallbacks for if upstream rejects the PR.

## 4. Estimated wins

Hard to commit until §3 is done, but informed guesses:

- **Cold-start time on day-N return:** if the message DB is the size we think it might be after weeks of use (say, 10–50 MB), VACUUMing weekly and bounding it at 7d + 5-per-thread should keep first-open render under 2 s on the 1 GB phone instead of the 5-10 s users currently see.
- **Notification shade draw time:** collapsing from 22-300 entries to 1 should make swipe-down from the lock screen instant rather than visibly chunky.
- **RAM:** very small direct win — DB size is a disk metric, not a RAM metric. The indirect win is that OB doesn't have to chew through a huge backlog on first open, so its peak PSS during cold start is lower.

If after the dry-run period the actual `rows_deleted` is small (under a few hundred per night on real devices), the message-cleanup half of this plan can be deprioritized in favor of just shipping the notification coalescer and the nightly OB-restart-at-4-AM behavior. Those two together likely buy most of the perceived improvement at a fraction of the risk.
