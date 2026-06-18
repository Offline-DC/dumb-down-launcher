# Battery + Log-Bloat Analysis — capture `20260618-195441Z`

**Device:** TCL flip (Android 11), launcher `com.offlineinc.dumbdownlauncher` (UID 10107)
**Capture window:** 2026-06-18 00:00 → 19:42 UTC (~19.7 h), 492 battery samples, 236 dumpsys snapshots
**Bundle:** 144 MB zipped / **1.45 GB unzipped**, 4,758 files

There are two findings, and they share **one root cause**: the diagnostics module takes a full, heavy snapshot on *every screen on/off*, and on a flip phone the lid drives the screen constantly.

---

## 1. Yes — the diagnostics code is saving far too much (≈29× over its own cap)

| | |
|---|---|
| `dmesg` dumps | **1,068 MB — 73% of the whole bundle** |
| per snapshot | 4.51 MB, captured **237 times** |
| redundancy | consecutive dmesg dumps are **~92% identical** (39,664 of 43,153 lines shared) |
| trigger breakdown | **228 of 236 snapshots were `screen_transition`**, only 8 were `hourly` |
| size cap | `MAX_DIAG_BYTES = 50 MB` — **exceeded 29×** |

**Why the 50 MB cap never fired.** `enforceDiskBudget()` is real, and it *does* walk the dumpsys tree — but it's only called from `JsonlWriter.append()` **on UTC-day rollover** (`JsonlWriter.kt:52–59`). Within a day, unlimited GB can accumulate before the budget is ever checked. The only thing running after each snapshot is `enforceDumpsysRetention()` (`PrivilegedDumpsysScheduler.kt:149`), which is **date-based (14 days)** and so deletes nothing in a fresh capture.

**Why so many snapshots.** `DiagnosticsService` fires `requestSnapshotAsync("screen_transition")` on every `ACTION_SCREEN_ON/OFF` (`DiagnosticsService.kt:256–259`). The capture logged **123 lid events** (61 close / 62 open) plus other wakes → 228 transition snapshots. Each one runs ~20 root `dumpsys` commands **including `dmesg -T`** — the full kernel ring buffer, re-dumped from scratch every time (`PrivilegedDumpsysScheduler.kt:259`).

If `dmesg` were captured only hourly: **~36 MB instead of 1,068 MB.**

## 2. Why this user's battery only lasts ~1 day

The device-level signal is unambiguous: **it never goes to sleep.**

| State | Median current draw | Healthy target |
|---|---|---|
| Screen **off** (discharging) | **184 mA** | ~10–40 mA |
| "In doze" | 139 mA | single digits |
| Screen on | 444 mA | — |

A ~184 mA floor with the screen off implies the SoC stays out of deep idle. At 184 mA on the 1,961 mAh battery that's only **~10–11 h** of idle runtime — i.e. "barely a day."

**Who's burning it — batterystats' own attribution (`batterystats-checkin`, `pwi,uid` = estimated mAh):**

| Rank | UID / app | Estimated drain |
|---|---|---|
| **1** | **10107 — the launcher itself** | **620 mAh (latest window); up to 1,579 mAh = 81% of battery in the largest window** |
| 2 | 10112 — Spotify | 207 mAh |
| 3 | 10113 — OpenBubbles | 98 mAh |

The launcher is the **#1 consumer by a factor of 3×** over the next app. Corroborating its own kernel cost: launcher CPU time = **291 s user + 504 s system** (system > user is the signature of heavy shelling-out + flash I/O), and a `top` snapshot caught it at **26% CPU** with the screen-off lid closed.

**The mechanism — observer effect.** On this device the launcher's drain is dominated not by GPS (GPS sensor on-time here maxed at only ~1.4 min/window) but by **the diagnostics module measuring the battery**: every lid flip spawns `su -c` and dumps `dmesg -T` (4.5 MB) + 18 other dumpsys + logcat, hundreds of times a day, plus the persistent `LidSensorReader` `su getevent` subprocess and `RollingLogcatTail`. The tool built to find the drain has become a top *cause* of it — and it's the same per-flip snapshot that produces the 1 GB of logs.

The previously-documented **quack GPS path** (`HOLLY_BATTERY_FINDINGS.md`, `quack-gps-battery-fix-plan.md`) is a real second contributor that dominates on devices where BeaconDB fails; it's smaller on *this* capture but the fix still applies.

---

## 3. How to fix it (in the launcher)

### Fix A — Stop the diagnostics self-drain + the log bloat (one change, fixes both)
The lever is the `screen_transition` snapshot. In `PrivilegedDumpsysScheduler` / `DiagnosticsService`:

1. **Don't capture `dmesg` (and the other heavy dumps) on screen transitions.** Restrict the screen-triggered snapshot to a few cheap, small dumps; keep `dmesg`, `batterystats-history`, `wifi`, `activity_processes` on the **hourly** cadence only.
2. **Rate-limit screen-triggered snapshots** to ≥1 per 15–30 min (debounce the lid). A flip phone should not snapshot 228×/day.
3. **Capture `dmesg` incrementally** — track the last-seen kernel timestamp and append only new lines (or `dmesg -T | tail -n N`), instead of re-dumping the whole ring buffer every time. Kills the ~92% redundancy.
4. **Gzip dumpsys output** as it's written (the jsonl path already gzips; dumpsys doesn't).
5. **Actually enforce `MAX_DIAG_BYTES` after every snapshot** — call `enforceDiskBudget()` at the end of `runSnapshot()`, not only on jsonl day-rollover.

Items 1–3 alone take the bundle from ~1.45 GB to well under 50 MB **and** remove the launcher's largest non-GPS drain.

Also lighten the always-on logcat (the diagnostics module's other big cost):

6. **Per-snapshot `logcat -d`** is now hourly-only (heavy) and priority-filtered to `*:W` with a smaller 2,000-line tail, instead of 10,000 verbose lines on every snapshot.
7. **The continuous rolling logcat tail** dropped from `logcat -v threadtime *:V` (every line, every UID, forever, flushed per line) to `*:W`. That verbose stream was the single largest *always-on* cost — it kept the CPU and flash busy whenever anything on the device logged, so the tool meant to measure idle drain was itself preventing idle. Warning-and-above keeps the crash/reboot signal (`AndroidRuntime` FATAL, ANRs, vendor errors); kernel reboot causes still come through `dmesg`.

### Implemented on branch `feature/lighter-battery-diagnostics`
This branch carries items 1–3, 5, 6, 7 above (dmesg tail + heavy/hourly split, lid-flip debounce, real-time size-cap enforcement, and the logcat lightening). Net effect: a capture drops from ~1.45 GB to well under the 50 MB cap, and the diagnostics module stops being a top battery consumer — so the next capture actually measures the device, not the instrument. (Item 4, gzip of dumpsys, was deferred to avoid changing the on-disk format the analysis tooling expects.)

### Out of scope here — the quack GPS path
The separate GPS drain (`quack-gps-battery-fix-plan.md`: cancel `getCurrentLocation` in `cleanup()`, single-flight guard, drop 1 Hz GPS, gate the boot prewarm) is **not** part of this branch — it's tracked separately. It matters on BeaconDB-failure devices, but on *this* capture GPS was not the dominant drain (the diagnostics instrumentation was), so the lightening work here is the relevant fix for this user.
