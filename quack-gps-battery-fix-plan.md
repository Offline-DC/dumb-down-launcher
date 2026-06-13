# Quack GPS Battery-Drain Fix Plan — TCL Flip 2 Launcher

## Context

Battery diagnostics from an affected user (TCL 4058G, Android 11, launcher
`v4.82.0`) show the launcher holding **GPS active ~7.8 hours over a ~10–15 h
capture** — the single largest battery consumer on the device, larger than the
diagnostics instrumentation itself. See `HOLLY_BATTERY_FINDINGS.md` for the full
investigation; the load-bearing evidence:

- `batterystats … 9,10107,l,sr,-10000,28050253,9` — UID 10107
  (`com.offlineinc.dumbdownlauncher`), sensor handle **-10000 = GPS**, on for
  **28,050,253 ms ≈ 7.8 h** across **9 sessions** (~52 min each).
- `GnssLocationProvider` partial wakelock acquired **28,289 times** — GPS pinned
  at ~1 Hz for long stretches.
- Estimated cost ≈ **350–470 mAh, ~20–25 % of the 1961 mAh battery**, for location
  alone. This is consistent with the product-wide "barely lasts a day" reports and
  is very likely the ~4 %/hr idle drain the diagnostics effort set out to find.

This is the launcher's own `quack` location feature, not a third-party app and not
(primarily) the diagnostics capture.

## Root cause

`quack` gets a fix through `QuackLocationHelper`, designed so BeaconDB (Wi-Fi/cell
network location) answers first and "GPS never runs." On affected devices BeaconDB
is failing, so every request falls through to the GPS path — and that path leaks
GPS-on time well past its intended caps. Four compounding defects:

1. **GPS runs at 1 Hz with no throttle.**
   `QuackLocationHelper.kt:47–48` — `MIN_TIME_MS = 0`, `MIN_DIST_M = 0f`, passed to
   `requestLocationUpdates(GPS_PROVIDER, 0, 0, …)` (lines 222–223). Worst-case
   power profile while waiting for a fix the chip often can't get indoors.

2. **`getCurrentLocation` is uncancellable, so `cleanup()` can't stop it.**
   `QuackLocationHelper.kt:237` calls
   `lm.getCurrentLocation(best, null, mainExecutor) { … }` with a **null
   `CancellationSignal`**. `cleanup()` (lines 304–309) only `removeUpdates()` the
   three `LocationListener`s — it has no handle to cancel the in-flight
   `getCurrentLocation`, so that request keeps the GPS engine warm until the
   platform's own (MediaTek-dependent, possibly long) internal timeout, even after
   the helper believes it has torn down.

3. **No single-flight guard across 5+ independent entry points.**
   Every caller constructs a fresh `QuackLocationHelper` with its own listeners and
   its own timeout. There is no dedup, so requests overlap and chain into long
   contiguous GPS-on windows (the observed 9 × ~52 min sessions):
   - `DumbDownApp.kt:228` — boot prewarm, **`PREWARM_TIMEOUT_MS` = 10 min**
   - `LocationConsent.kt:53` — consent grant, **10 min**
   - `QuackLocationRefreshWorker` — **every 1 h**, `WORKER_HARD_TIMEOUT_MS` = 2 min
   - `LocationProvider.forceLiveFix` (`:52`) — every quack/weather screen open, 30 s
   - `QuackFirstQuackWorker` — periodic
   - plus `WeatherViewModel` shares the same helper

4. **The 10-minute prewarm fires on every process start.**
   `DumbDownApp.onCreate` runs the 10-min prewarm once per process launch. On a
   ~1 GB-RAM flip the launcher process is killed and cold-started many times a day
   (made worse by the `START_STICKY` diagnostics service), so "once per boot"
   becomes many 10-min GPS sessions per day.

Net: a device where BeaconDB fails pays defects #1–#4 on every location request,
and the requests stack — turning an intended ~1–2 min/hr into hours/day of GPS.

## Goals

1. Cut launcher-attributed GPS-on time from ~7.8 h/day to **< ~15 min/day** on a
   device where BeaconDB is failing.
2. Never hold the GPS engine after a fix is delivered or a request is abandoned.
3. Keep quack/weather location *quality and latency* unchanged on healthy devices
   (BeaconDB still wins first; foreground UX still ~instant from cache).

## Non-goals

- The separate random-reboot / hardware power-loss issue (tracked in
  `HOLLY_BATTERY_FINDINGS.md`; needs an RMA, not code).
- Diagnostics-module self-limiting (separate change; see findings doc).
- Rewriting onto Play Services fused location — these are AOSP/MediaTek devices
  without Play Services; BeaconDB stays the primary path.

## The fix

### Phase 1 — Authoritative teardown + single-flight (biggest win, lowest risk)

- **Retain and cancel the `getCurrentLocation` request.** Hold a
  `CancellationSignal`, pass it instead of `null`, and call `.cancel()` inside
  `cleanup()`. This closes defect #2 directly — the one path that currently
  outlives teardown.
- **One in-flight request at a time.** Add a process-wide single-flight guard (a
  static `AtomicBoolean`/lock or a small singleton owning the active helper). New
  `request()` calls while one is active either no-op or attach their callback to
  the running request instead of starting a second GPS session. Closes defect #3.
- **Guaranteed watchdog teardown.** Ensure exactly one timeout always runs
  `cleanup()` for *every* started provider (listeners **and** the cancellation
  signal), even on the exception/early-return paths.

*Expected effect:* eliminates the overlapping/uncancellable sessions that produce
the multi-hour GPS-on windows.

### Phase 2 — Stop the 1 Hz continuous GPS

- Prefer the single-shot API: on API ≥ 30 (all target hardware) use
  `getCurrentLocation` **only**, with the retained `CancellationSignal` and a short
  timeout, and **drop the continuous `requestLocationUpdates(GPS, 0, 0, …)` path**.
  If a fallback `requestLocationUpdates` must stay, set `minTimeMs` to ≥ 30 s and a
  nonzero `minDistanceM`. Closes defect #1.

### Phase 3 — Bound and de-risk the prewarm / background cadence

- **Shorten and gate the boot prewarm** (`DumbDownApp.kt`): drop from 10 min to a
  short GPS cap (≤ 30–60 s), and skip GPS entirely if a persisted fix is still
  fresh. Only run the long prewarm **once per real device boot**, not once per
  process start (persist a boot-id / use `ACTION_BOOT_COMPLETED`), and ideally only
  while charging. Closes defect #4.
- **Back off when BeaconDB is failing.** Track consecutive GPS-fallback failures;
  after N misses, stop attempting live GPS in the background and serve last-known /
  persisted location until the user next foregrounds quack. A device that can't fix
  indoors should not retry GPS 24×/day.
- Consider relaxing `QuackLocationRefreshWorker` from 1 h back toward 6 h for the
  background cache-refresh (the comment notes it was recently tightened from 6 h).

### Phase 4 — Address the upstream cause: why BeaconDB fails

If network location worked, GPS would essentially never run (the code's own
premise), so this is the highest-leverage long-term fix.

**What we already know from Holly's capture (rules things out):**

- It is **not** a connectivity problem. `connectivity` dumpsys shows a *validated*
  LTE default network (`fast.t-mobile.com`, `INTERNET … VALIDATED`, `everValidated
  true`) with mobile data flowing in `netstats`. BeaconDB could reach
  `api.beacondb.net`.
- Wi-Fi was **enabled but disconnected** (`wifi` dumpsys: stuck in
  `DisconnectedState`). So `scanWifi()` likely returned few/stale APs and the query
  fell back to **cell-only**.
- So the failure is one of: (a) insufficient scan inputs (Wi-Fi disconnected → < 2
  APs, cell-only), or (b) BeaconDB returning no fix / an HTTP error for the
  cell-only query. **Distinguishing (a) vs (b) needs the app's own location logs,
  which were not in this bundle** (see below).

**Do we need to add logging? No — but we need to make it land in the bundle.**

The failure reason is *already logged* today:

- `BeaconDbClient.kt` logs `geolocate: HTTP <code> body=…`, `geolocate: failed —
  <exception>`, and `response had no usable location`.
- `NetworkLocationFetcher.kt` logs the Wi-Fi AP count, cell count, `insufficient
  signals (need ≥2 wifi or ≥1 cell)`, `Wi-Fi disabled — using cached`, and
  `startScan returned false (throttled)`.

The reason they're missing from Holly's submission: those lines are `Log.d`/`Log.w`
that only persist in the **continuous rolling-logcat ring**, and that's gated behind
the **separate "rolling logs" toggle** in `DiagnosticsActivity` (distinct from
"battery analysis"). Holly had battery analysis on but rolling logs **off**, so only
the sparse point-in-time `logcat -d -t 10000` snapshots were captured — and none
happened to coincide with a BeaconDB attempt. `DiagLogUploader` *does* package the
rolling ring when it exists, so simply having both toggles on would have captured it.

**Recommended (robust) instrumentation — small, always-on, reboot-safe:**

Rather than depend on the verbose-logcat toggle being on, emit one structured
breadcrumb per location request into the existing `events.jsonl` (captured by the
default "battery analysis" mode, survives reboots, ~zero battery cost):

```
quack_location_result: {
  outcome,            // delivered | error
  source,             // beacondb | gps | network | passive | persisted_cache | system_cache
  beacondb_http_code, // null if request never made
  wifi_ap_count,
  cell_count,
  fell_back_to_gps,   // bool
  elapsed_ms
}
```

That single event answers "why did BeaconDB fall through to GPS" directly and ties
the GPS-drain symptom to its cause in every future capture, without the verbose
logcat cost.

**Status: IMPLEMENTED.** A process-wide `DiagBreadcrumbs` sink
(`diagnostics/DiagBreadcrumbs.kt`) is registered by `DiagnosticsService` while it's
collecting (no-op otherwise), routing app-level events through the existing
`events.jsonl` writer. Three layered breadcrumbs now fire per location request — all
captured by the default "battery analysis" toggle, no rolling-logcat needed:

| event `type` | emitted by | key payload fields |
| --- | --- | --- |
| `quack_netloc_scan` | `NetworkLocationFetcher.fetch` | `outcome` (no_permission / insufficient_signals / querying_beacondb), `wifi_ap_count`, `cell_count` |
| `quack_beacondb` | `BeaconDbClient.geolocate` | `outcome` (fix / no_location / http_error / exception / insufficient_signals), `http_code`, `wifi_ap_count`, `cell_count`, `error` |
| `quack_location_result` | `QuackLocationHelper` (terminal, once per request) | `outcome` (delivered / error), `source` (beacondb / gps / network / passive / persisted_cache / system_cache / none), `fell_back_to_gps`, `elapsed_ms`, `timeout_ms` |

How to read a future capture: a `quack_location_result` with
`fell_back_to_gps=true` paired with the preceding `quack_beacondb` row tells you
*why* — e.g. `http_code=404`/`no_location` (BeaconDB coverage gap) vs.
`wifi_ap_count<2` + cell-only (disconnected Wi-Fi → weak inputs) vs. `exception`
(network). Cross-check against the `sr,-10000` GPS time to confirm the drain.

**Immediate path to the answer on Holly's unit:** have her also enable the "rolling
logs" toggle and re-submit one capture — the BeaconDB HTTP code / signal counts will
be in the zip. Then fix accordingly (e.g. cell-only BeaconDB coverage gap → cache
last good fix longer / widen acceptance; or trigger a Wi-Fi scan even when
disconnected).

## Verification

- **Re-pull battery diagnostics** after the change and confirm the GPS line
  `…,sr,-10000,<ms>,<count>` drops from ~28 M ms to a small value, and
  `GnssLocationProvider` wakelock count falls from ~28 k toward hundreds.
- **Local instrumented test:** force `NetworkLocationFetcher.fetch()` to return
  null (simulate BeaconDB failure), trigger all entry points (boot prewarm, hourly
  worker, repeated screen opens) and assert via `dumpsys batterystats` that total
  GPS-on time over an hour stays under a tight budget and that no GPS session
  outlives its timeout.
- **Unit/Robolectric:** assert `cleanup()` cancels the `CancellationSignal` and
  `removeUpdates` all listeners; assert the single-flight guard prevents a second
  concurrent GPS session.
- Add a lightweight counter (GPS start count + cumulative on-time) to the existing
  diagnostics so future captures surface this directly.

## Rollout & risk

- Ship behind the beta channel to the affected cohort first; compare overnight
  idle drain (samples.jsonl `%/hr`) before/after.
- Main UX risk: a too-aggressive GPS cap could degrade first-fix on a device with
  no cached location and no BeaconDB. Mitigate by keeping foreground
  `getCurrentLocation` at a usable timeout and always falling back to
  persisted/last-known rather than spinning GPS.

## Acceptance criteria

- Launcher-attributed GPS-on time **< ~15 min/day** with BeaconDB failing.
- No GPS session exceeds its configured timeout in batterystats.
- Overnight idle drain (diagnostics off) back to ≤ ~1 %/hr on an affected unit.
- Quack/weather first-fix latency unchanged on a healthy (BeaconDB-working) device.

## Part B — Make the battery diagnostics itself lighter (keep the signal, lose the drain)

The diagnostics capture is currently a meaningful drain in its own right — enough
that we email users to turn it off, and enough to muddy the very measurement it's
taking (observer effect). The goal here is to shrink its footprint by ~10× while
preserving the data that actually diagnosed this bug.

### Where the diagnostics drain comes from

1. **Continuous verbose, all-UID logcat.** `RollingLogcatTail.kt:138` runs
   `su -c "logcat -v threadtime *:V"` nonstop. Verbose-everything is the single
   biggest cost: a root subprocess + a blocking read loop + constant line writes +
   periodic gzip, all of which keep the CPU out of deep sleep 24/7.
2. **Dumpsys snapshot on every screen transition.** `DiagnosticsService.kt:228`
   fires `requestSnapshotAsync("screen_transition")` on *every* screen on/off, and
   each snapshot (`PrivilegedDumpsysScheduler.runSnapshot`) shells out ~20 dumpsys
   subcommands **plus a `logcat -d -t 10000`** (10k lines). On a flip that's opened
   dozens of times a day this is a lot of heavy, bursty CPU/IO — and the per-snapshot
   `logcat -d` is redundant with the rolling tail.
3. **Always-on lid `getevent` subprocess.** `LidSensorReader` keeps a second root
   subprocess (`su -c getevent -lt /dev/input/event3`) alive continuously, even on
   captures where the lid never chatters (Holly's had zero `lid_bounce`).
4. **60 s fixed-rate self-sampling.** `scheduleAtFixedRate(BATTERY_SAMPLE_INTERVAL_MS
   = 60_000)` ticks every minute regardless of system sleep, helping prevent long
   doze windows. (`suspend_stats` showed **287 failed suspends** during the capture.)
5. **Two foreground services** (`DiagnosticsService` + `RebootLoggingService`), each
   keeping the process alive and exempt from doze.

### Key insight: the data that solved this came from `dumpsys batterystats`, not the high-frequency stuff

The GPS root cause was found in periodic `dumpsys batterystats` (`sr` sensor line,
`pwi` power-use, kernel wakelocks) — system-maintained counters that accrue
**accurately regardless of how often we sample**. So we can cut the expensive,
high-frequency launcher-side collection dramatically and still keep the highest-value
signal, as long as we grab `batterystats` a few times per capture.

### Changes (`diagnostics/DiagnosticsConfig.kt`, `RebootLoggingConfig.kt`, service code)

1. **Filter the rolling logcat.** Replace `logcat -v threadtime *:V` with a level +
   allowlist filter, e.g. `*:W` plus the launcher's own tags at verbose
   (`QuackLocation:V DiagDumpsys:V … *:W`). Keeps crash/warn forensics and our own
   logs; drops the firehose of verbose system lines. Biggest single win for both CPU
   and disk.
2. **Stop snapshotting on every screen transition.** Debounce to at most once per
   15–30 min, or drop screen-transition snapshots entirely and keep only the hourly
   tick plus a few *event-driven* snapshots (doze change, `battery_low`). Remove the
   per-snapshot `logcat -d -t 10000` — the rolling tail already has it.
3. **Trim the dumpsys set / tier it.** Capture the high-value subset
   (`batterystats-checkin`, `power`, `deviceidle`, `wakeup_sources`, `suspend_stats`)
   on the frequent tick, and the full ~20-command set only every few hours.
4. **Sample via deferrable alarms, not a fixed tick.** Move battery sampling to
   `AlarmManager.setAndAllowWhileIdle` at a coarser interval (e.g. 2–5 min) so the OS
   batches wakeups and the device can actually suspend between them. Per-minute
   resolution isn't needed to characterize a multi-hour drain curve.
5. **Make the lid `getevent` reader opt-in.** Default it off; only enable when the
   lid-chatter hypothesis is being investigated. Otherwise derive `lid_state` lazily
   from screen/doze events. Removes one always-on root subprocess.
6. **Collapse to a single foreground service / notification.**
7. **Add battery guards + auto-expiry** (also in `HOLLY_BATTERY_FINDINGS.md`): stop
   the capture on `ACTION_BATTERY_LOW` / below ~15 %, and auto-disable after a capture
   window (e.g. 24–36 h) so it can't drain the battery it's measuring and users don't
   need the manual "turn it off" reminder.

### Two-tier model (recommended framing)

- **Light mode (default, can run for days):** deferrable battery samples (2–5 min) +
  event log + filtered rolling logcat + periodic `batterystats`. Low, bounded
  footprint; safe to leave on; still catches GPS/wakelock/idle-drain bugs.
- **Deep mode (engineer-triggered, time-boxed, auto-expiring):** per-minute sampling
  + full dumpsys set + verbose logcat + lid `getevent`. Use only when you need
  millisecond/edge detail, and let it expire automatically.

### Verification (diagnostics overhead)

- With **light mode** running and the device otherwise idle, confirm `suspend_stats`
  failed-suspend count drops sharply and idle drain (samples.jsonl `%/hr`) is within
  ~0.5 %/hr of a capture-off baseline — i.e. the instrument no longer dominates.
- Confirm the GPS root-cause signal is still present: `batterystats` `sr,-10000` and
  `GnssLocationProvider` wakelock counts still captured on the periodic tick.
- Record an explicit estimate of diagnostics' own UID CPU/wake cost per capture so
  future analysis can subtract it.

## Key files

- `app/src/main/java/com/offlineinc/dumbdownlauncher/quack/QuackLocationHelper.kt`
  (constants `:45–57`, providers `:126–166`, GPS fallback `:209–247`,
  `deliver`/`cleanup` `:286–309`)
- `app/src/main/java/com/offlineinc/dumbdownlauncher/quack/QuackLocationRefreshWorker.kt`
- `app/src/main/java/com/offlineinc/dumbdownlauncher/quack/LocationProvider.kt` (`:46–67`)
- `app/src/main/java/com/offlineinc/dumbdownlauncher/quack/NetworkLocationFetcher.kt` (BeaconDB)
- `app/src/main/java/com/offlineinc/dumbdownlauncher/DumbDownApp.kt` (`:219–232` prewarm)
- `app/src/main/java/com/offlineinc/dumbdownlauncher/quack/LocationConsent.kt` (`:48–57`)

Diagnostics (Part B):
- `app/src/main/java/com/offlineinc/dumbdownlauncher/diagnostics/DiagnosticsConfig.kt`
  (sampling/snapshot cadence, retention)
- `app/src/main/java/com/offlineinc/dumbdownlauncher/diagnostics/RebootLoggingConfig.kt`
- `app/src/main/java/com/offlineinc/dumbdownlauncher/diagnostics/RollingLogcatTail.kt` (`:138` logcat filter)
- `app/src/main/java/com/offlineinc/dumbdownlauncher/diagnostics/PrivilegedDumpsysScheduler.kt` (snapshot set + per-snapshot logcat)
- `app/src/main/java/com/offlineinc/dumbdownlauncher/diagnostics/DiagnosticsService.kt` (`:174` sampler, `:228` screen-transition snapshot, `:208` battery_low)
- `app/src/main/java/com/offlineinc/dumbdownlauncher/diagnostics/LidSensorReader.kt`
