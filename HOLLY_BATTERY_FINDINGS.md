# Holly — battery diagnostics findings

Device: TCL 4058G (Flip 2), Android 11, launcher `v4.82.0`, diag build `diag-v4.82.0`.
Capture: 2026-06-12 19:03 → 2026-06-13 10:27 (UTC), session `9925bc32…`.

## TL;DR

There are **three separate problems**, and they're easy to conflate:

1. **The fast battery drain ("barely lasts a day") is the `quack` location feature
   pinning GPS on.** batterystats attributes **~7.8 hours of GPS to the launcher**
   during this capture. This is the dominant drain — bigger than the diagnostics —
   and it's a real launcher bug we can fix. **This is the answer to Holly's actual
   complaint.** (Details in "Real drain source" below.)
2. **The random shut-offs at non-empty battery are a hardware fault, not a launcher
   bug.** The battery itself is healthy; the phone lost power at 37% while sitting
   idle. We can't fix this in code — Holly needs a unit/battery swap. We *can* ship
   code to capture the cause next time.
3. **The "1% in the morning" was made worse still by leaving the diagnostics
   capture running overnight on top of #1.** Expected, and on us to make
   self-limiting.

## Real drain source — quack GPS (the main finding)

The system battery accounting points squarely at the launcher's location feature,
not at any third-party app and not (primarily) at the diagnostics:

- `batterystats … 9,10107,l,sr,-10000,28050253,9` — UID 10107 is
  `com.offlineinc.dumbdownlauncher`; sensor handle **-10000 is GPS**; it was held
  on for **28,050,253 ms ≈ 7.8 hours** across **9 sessions** (~52 min each).
- `GnssLocationProvider` partial wakelock acquired **28,289 times** — consistent
  with GPS running at 1 Hz for those long stretches (1 Hz × 7.8 h ≈ 28 k).
- The launcher is the **top power consumer** in `pwi` by a wide margin; no other
  third-party app shows meaningful drain.
- Rough cost: ~7.8 h of GPS at ~40–80 mA ≈ **350–470 mAh, ~20–25% of the 1961 mAh
  battery**, just for location.

Why it happens (`quack/QuackLocationHelper.kt` + `QuackLocationRefreshWorker.kt`):

- `MIN_TIME_MS = 0`, `MIN_DIST_M = 0f` → `requestLocationUpdates` runs GPS at its
  **maximum 1 Hz rate** with no throttle while waiting for a fix.
- The design intent is "BeaconDB (Wi-Fi/cell network location) wins first and GPS
  never runs." On Holly's device BeaconDB/network location is evidently **failing**,
  so every refresh falls through to the GPS path.
- Configured GPS timeouts are 2 min (hourly worker), 10 min (boot prewarm), 30 s
  (foreground). **9 sessions averaging ~52 min each is far longer than any of those
  caps allow** — so GPS is staying on well past the intended timeout. Most likely
  `cleanup()`/`removeUpdates()` isn't reliably stopping GPS on this hardware (or
  overlapping helper instances keep re-arming it), so a flip phone that can't get
  an indoor fix just holds the GPS chip on.

This is very plausibly the **product-wide ~4 %/hr idle drain** the diagnostics plan
set out to find. Note the plan's leading suspect was the lid/hall sensor — but in
Holly's capture there was **zero lid chatter**, and the data instead implicates GPS.

## Evidence

### The battery is healthy
From kernel `healthd` (pre-crash dmesg):

```
healthd: battery l=39 v=3782 t=33.0 h=2 st=3 c=-561000 fc=1961000 cc=49
```

- `h=2` = **BATTERY_HEALTH_GOOD**
- `fc=1961000` = full-charge capacity **1961 mAh**, *above* the design rating
  (`fgauge: battery3_type TLi017CA:TMO:1850mAh` → 1850 mAh design)
- `cc=49` = **49 charge cycles** — effectively a new cell

So this is not a worn-out / swollen-battery situation. The discharge curve is also
normal: voltage tracks SoC sensibly (≈3.82 V @ 55%, ≈3.76 V @ 37%, ≈3.63 V @ 1%).

### The phone hard-rebooted at 37%, while idle
- Screen went **off at 22:04:28**. No further user activity.
- A fresh **cold boot** occurred ~22:40: `dumpsys/dmesg-20260612-224140.txt` starts
  with `Booting Linux on physical CPU 0x0` (uptime 0), and a new `session_start`
  fires at 22:41:10.
- Proof it was a full power-cycle, not just an app restart: the kernel monotonic
  clock **reset from ~14.6 M ms (≈4 h uptime) down to ~0.5 M ms**
  (`lid_close` monotonic 14,613,617 @ 22:04 → 549,171 @ 22:49).
- Battery was at **37%** across the gap (38% @ 22:10 → 37% @ 22:41).

This matches Holly's "turned off yesterday evening even though it wasn't dead."

### It was not a software crash
The pre-crash logcat/dmesg (`*-220428`) show **no** FATAL, ANR, watchdog,
`lowmemorykiller`, or kernel panic for `com.offlineinc.dumbdownlauncher` or
system_server in the window leading up to the reboot. A healthy battery + idle
device + clean cold boot with no panic trace is most consistent with an **abrupt
power loss** — i.e. a power-delivery fault (loose/failing battery contact or a
PMIC/protection trip), which is exactly the class of "random reboot" the
`RebootLogging` module was built to chase.

> Caveat: this bundle has no `pstore`/`last_kmsg`/ramoops, so we can't 100% rule
> out a kernel panic that left no logcat trace. Capturing that on next boot is the
> recommended next step (see fix #3).

### The overnight drain was inflated by the diagnostics itself
Overnight, screen-off, in doze: **35% @ 22:51 → 1% @ 10:22 ≈ 3%/hour.** Healthy
idle on this hardware should be ~0.5–1%/hour (per `battery-diagnostics-plan.md`).
Contributors visible in the data:

- `dumpsys/suspend_stats-*`: **287 failed suspends (187 `failed_freeze`)**,
  `last_failed_dev: alarmtimer` — the device kept failing to stay asleep.
- Screen-off samples repeatedly show **−50 to −300 mA** with frequent doze
  exits, instead of the few-mA draw of real deep sleep.
- `DiagnosticsService` keeps the phone busy by design: a per-minute sampling
  wakeup, an hourly **+ every-screen-transition** `dumpsys` snapshot, a continuous
  `su -c getevent` lid reader, **and** (via `RebootLogging`) a continuous
  `su -c logcat -v threadtime *:V` tail to disk. Two always-on root subprocesses +
  a 60 s wakeup loop comfortably explain the elevated idle drain.

Holly forgot to turn the analysis off (her email), so it ran all night — hence 1%
by morning. `ACTION_BATTERY_LOW` fired at 04:28 and the service just logged it and
kept running down to 1%.

Note: because diagnostics dominated the idle draw, this capture does **not**
cleanly isolate the product-wide ~4%/h drain the plan is hunting (lid/hall sensor
hypothesis). For Holly specifically there was **no lid chatter** — zero
`lid_bounce` events in the whole capture — so the hall-sensor hypothesis is not
her problem.

## Recommended actions

### Fix the real drain (quack GPS) — highest priority
File: `app/src/main/java/com/offlineinc/dumbdownlauncher/quack/QuackLocationHelper.kt`

1. **Stop running GPS at 1 Hz.** `MIN_TIME_MS = 0` / `MIN_DIST_M = 0` is the worst
   case for power. Either set a sane `minTime` (e.g. tens of seconds) or, better,
   drop the continuous `requestLocationUpdates` path entirely and rely only on the
   single-shot `getCurrentLocation()` (already used on API 30+, which this device
   is) so the OS owns the timeout and teardown.
2. **Guarantee teardown.** Make the timeout that calls `cleanup()` authoritative —
   a single watchdog that always `removeUpdates()` for all listeners even if a fix
   never arrives, and ensure only one `QuackLocationHelper` can be in flight at a
   time. The 7.8 h / 9-session figure says GPS is outliving its timeout today.
3. **Fail GPS fast when there's no sky.** On a flip phone indoors GPS will not fix;
   holding it for minutes just burns battery. Cap the GPS fallback hard (e.g. 20–30 s)
   and fall back to last-known/persisted rather than waiting.
4. **Find out why BeaconDB is failing on her unit.** If network location worked,
   GPS would essentially never run (per the code's own comment). Worth confirming
   whether it's a Wi-Fi-off / connectivity / BeaconDB-service issue on these devices.

### For Holly (now)
Her battery is fine but the unit is dropping power at ~37%. This is a hardware
RMA / battery-pack reseat-or-replace, not a software fix. Have her turn the
diagnostics toggle **off** (long-press quack → toggle) so normal battery life
returns while the replacement is sorted.

### Code changes (next beta) — make diagnostics self-limiting
File: `app/src/main/java/com/offlineinc/dumbdownlauncher/diagnostics/DiagnosticsService.kt`
and `DiagnosticsConfig.kt`.

1. **Auto-stop on low battery.** The receiver already catches `ACTION_BATTERY_LOW`
   (line ~208) — have it stop the capture (flip the opt-in off + `stopSelf()`),
   not just log it. Add a guard around ~15% so the analysis never drains the last
   reserve. This alone would have saved Holly's morning charge.
2. **Auto-stop after a capture window.** The plan is a "24-hour bundle," but there's
   no timer. Add e.g. `MAX_CAPTURE_DURATION_MS = 36h` to `DiagnosticsConfig` and
   stop + auto-disable when exceeded, so users who forget the toggle don't bleed
   battery for days. Removes the need to email people the "turn it off" reminder.
3. **Capture the reboot cause.** On service start, read and copy
   `/sys/fs/pstore/console-ramoops*`, `/proc/last_kmsg`, and `/proc/cmdline` into
   the bundle. That's what tells us brownout vs. panic on the *next* random reboot —
   the single most useful signal we're currently missing.

### Lower-priority drain trims (for the general drain investigation)
4. Drop the rolling logcat from `*:V` (verbose, system-wide) to a filtered level
   (`*:W` + specific tags). Continuous verbose logcat is the heaviest always-on
   cost and is rarely needed post-mortem.
5. Don't fire a full `dumpsys` snapshot on *every* screen on/off — debounce or cap
   to once per N minutes. On a flip that gets opened often this is a lot of heavy
   shell work.
