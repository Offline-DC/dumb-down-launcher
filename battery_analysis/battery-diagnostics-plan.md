# Battery Drain Diagnostics Plan — TCL Flip 2 Launcher

## Context

We ship a custom launcher to TCL Flip 2 (model `4058G`) devices running Android 11.
A handful of beta users report severe battery drain — one observed case shows a
roughly linear decay from 100% to ~9% over ~22 hours, mostly with the lid closed.
That is approximately **4%/hour at idle**, where a healthy device on this hardware
should sit closer to 0.5–1%/hour with the lid closed.

The shape of the curve (steady, linear, present overnight) tells us this is not
a transient bug or a single misbehaving event — something is holding the SoC
out of deep idle / doze, or a peripheral (sensor, radio, display) is consuming
power continuously. The plan below is built around catching that.

We control the platform signing key (the launcher is a system pre-install), so
we have access to `READ_LOGS`, `BATTERY_STATS`, and the privileged `dumpsys`
subcommands that the OS normally restricts.

## Goals

1. Ship an instrumented build of the launcher to affected users one device
   at a time.
2. Capture, per device, a 24-hour structured diagnostic bundle that includes
   both launcher-level and system-level battery, wakelock, alarm, and sensor
   activity.
3. Produce that bundle in a schema designed for LLM-assisted analysis —
   pre-parsed, normalized, with derived metrics and hypothesis scoring already
   computed on-device — so that diagnosing a device is a matter of reading
   a handful of small JSON files rather than grepping bugreports.
4. Identify the root cause of the idle drain and ship a fix on the next beta.

## Phase 1 — Instrument the launcher

Add a `DiagnosticsService` to the launcher, gated behind a build flag and an
in-app opt-in toggle so the production build stays clean. Because the app is
platform-signed, it can run as a system service started early in boot. It does
four jobs concurrently.

### 1.1 Battery state sampling

Once per minute, capture the following from `BatteryManager` and the
`ACTION_BATTERY_CHANGED` sticky broadcast, and write one row to
`samples.jsonl`:

| Field | Source |
| --- | --- |
| `capacity_pct` | `BATTERY_PROPERTY_CAPACITY` |
| `current_now_ua` | `BATTERY_PROPERTY_CURRENT_NOW` |
| `charge_counter_ua` | `BATTERY_PROPERTY_CHARGE_COUNTER` |
| `energy_counter_nwh` | `BATTERY_PROPERTY_ENERGY_COUNTER` |
| `temp_c` | `ACTION_BATTERY_CHANGED` extra `temperature` ÷ 10 |
| `voltage_mv` | `ACTION_BATTERY_CHANGED` extra `voltage` |
| `status` | charging / discharging / full / not_charging |
| `screen_state` | derived from last screen on/off event |
| `lid_state` | derived from last lid sensor event |
| `in_doze` | from `PowerManager.isDeviceIdleMode()` |

This gives a precise per-minute drain curve and lets us slice it later by
state.

### 1.2 Event broadcasts

Subscribe to and timestamp every occurrence of the following system intents:

- `ACTION_SCREEN_ON`, `ACTION_SCREEN_OFF`
- `ACTION_USER_PRESENT`
- `ACTION_POWER_CONNECTED`, `ACTION_POWER_DISCONNECTED`
- `ACTION_BATTERY_LOW`, `ACTION_BATTERY_OKAY`
- `ACTION_DEVICE_IDLE_MODE_CHANGED` (doze entry / exit)
- `ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED` (light doze)
- Lid / hall sensor events for the flip mechanism — register a
  `SensorEventListener` for whatever sensor reports lid state on this
  hardware. **This is a primary suspect** given the form factor and the
  observation that drain is high while the lid is closed.

### 1.3 Launcher self-instrumentation

Wrap or intercept the launcher's own use of system resources so we know
exactly what the launcher is doing in any given window:

- Every `PowerManager.WakeLock.acquire()` and `release()`, with the tag,
  flags, and stack trace of the caller. Wrap `PowerManager.newWakeLock`
  in a logging proxy so this is impossible to forget.
- Every `AlarmManager.set*` call (tag, type, window, repeat interval).
- Every `JobScheduler.schedule()` (id, constraints, period).
- Every binder / AIDL call into the launcher process.
- Foreground / background lifecycle transitions of every Activity and
  Service.
- Significant UI events: app drawer open, search, voice, dialer launch,
  contacts open.

### 1.4 Privileged system snapshots

Once per hour, and on every screen-on and screen-off transition, capture
the following via `Runtime.exec()` (available because we're platform-signed):

```
dumpsys batterystats --checkin        → batterystats-checkin.txt (append)
dumpsys procstats --hours 24          → procstats.txt              (rotate)
dumpsys deviceidle                    → deviceidle.txt
dumpsys alarm                         → alarm.txt
dumpsys jobscheduler                  → jobscheduler.txt
dumpsys power                         → power.txt
dumpsys sensorservice                 → sensorservice.txt     ← lid sensor lives here
dumpsys activity processes            → activity-processes.txt
dumpsys netstats detail               → netstats.txt
logcat -d -v threadtime -t 10000      → logcat.txt            (rotate)
```

Write everything to `/data/data/<pkg>/files/diag/` (private) with a mirror
to `/sdcard/Android/data/<pkg>/files/diag/` for easy ADB pull.

## Phase 2 — On-device log hygiene

- One file per day, JSONL where structured, plain text where snapshotted.
- Gzip closed files. Retain 14 days.
- Total disk budget ≤ 50 MB so diagnostics never become the cause of their own
  bug reports.
- Maintain a `manifest.json` at the bundle root containing device serial,
  build fingerprint, launcher version, diagnostics build hash, and the
  `diagnostics_enabled_since` timestamp.
- Provide a "reset session" action that emits a `session_start` event with a
  fresh `capture_session_id` UUID. Every subsequent line in every file
  carries that UUID so partial pulls are easy to detect and discard.

## Phase 3 — Device recruitment and consent

Recruit affected users one at a time. Each device is investigated
independently — there is no "control cohort" to diff against; the
`hypotheses.json` file scores each device against absolute thresholds
derived from a healthy reference device (your own dogfood phone, captured
ahead of time with the same instrumentation).

For each user, capture a `device-info.json` alongside the bundle:

```json
{
  "device_id": "<adb get-serialno>",
  "model": "4058W",
  "hardware_revision": "<from /proc/cpuinfo or ro.boot.hardware.revision>",
  "build_fingerprint": "<from getprop ro.build.fingerprint>",
  "launcher_version": "X.Y.Z-diag",
  "diagnostics_build_hash": "<git sha>",
  "battery_design_capacity_mah": "<from dumpsys batterystats>",
  "carrier": "<from getprop gsm.sim.operator.alpha>",
  "android_security_patch": "<from getprop ro.build.version.security_patch>",
  "usage_profile_self_report": "~30 min calls/day, flip open ~50x/day",
  "notes": "free-form context the user reported",
  "capture_window_start_iso": "...",
  "capture_window_end_iso": "..."
}
```

The hardware revision matters: flip phones often ship with multiple battery
suppliers or hall-sensor part variants across production runs. Logging
`hardware_revision` per device lets us spot patterns across multiple
affected users even though each one is analyzed independently.

## Phase 4 — Deployment

- Cut a `launcher-diag-X.Y.Z` build off the main branch with the
  `DIAGNOSTICS_ENABLED` flag on.
- Push only to the selected affected users via the existing beta channel.
  Production users stay on the clean build.
- In the launcher, add a hidden settings entry triggered by a **long-press
  on the "Quack" app in the All Apps list**, following the same pattern
  the launcher already uses for the long-press on "Device Setup" in All
  Apps. Reuse that existing long-press handler / dispatch path rather than
  introducing a new gesture mechanism. The diagnostics screen that opens
  must:
  - Display, in plain language, what is being collected (battery samples,
    wakelocks, alarms, system logs).
  - Require an explicit opt-in before any logging starts.
  - Show the path to the diagnostics folder.
  - Expose a "reset session" button.
- Confirm consent with each user verbally before flipping the toggle. We
  capture logcat, so this is non-negotiable even for a single device.

## Phase 5 — Capture protocol

For each device, the same 24-hour window. The launcher should ideally
sit in a known state (lid closed, on a desk, not plugged in) for the
overnight portion so the idle-drain bucket has clean data.

```bash
DEV=$(adb get-serialno)
STAMP=$(date +%Y%m%d-%H%M)
OUT=diag-$DEV-$STAMP
mkdir -p $OUT

# Mark a clean window
adb shell dumpsys batterystats --reset
adb shell am broadcast -a com.example.launcher.DIAG_SESSION_START

# --- user uses phone normally for 24 hours ---

# End-of-window snapshots
adb bugreport $OUT/bugreport.zip
adb shell dumpsys batterystats           > $OUT/batterystats-final.txt
adb shell dumpsys sensorservice          > $OUT/sensorservice-final.txt
adb shell dumpsys deviceidle             > $OUT/deviceidle-final.txt
adb shell dumpsys alarm                  > $OUT/alarm-final.txt
adb shell dumpsys jobscheduler           > $OUT/jobscheduler-final.txt

# Pull the launcher-collected bundle
adb pull /sdcard/Android/data/com.example.launcher/files/diag/ $OUT/launcher-diag/

# Run the post-processing parser to produce the LLM-friendly bundle
./tools/build-analysis-bundle.py $OUT/
```

The `--reset` + 24h + final bugreport pattern is what Battery Historian
expects, and it lets us correlate the launcher's own event log against the
system's view of the same window.

## Phase 6 — Per-device analysis bundle

The single most important design decision: **do not hand raw `dumpsys`
output to the analyst (human or LLM)**. Parse it on-device — or in the
post-processing script — into a fixed schema, with derived metrics and
hypothesis scoring pre-computed. Raw bugreports stay on disk as the source
of truth but the analysis layer reads structured rollups.

### 6.1 Bundle layout

Every device produces the same files with the same schema:

```
diag-<deviceid>-<date>/
  device-info.json            ← hardware, build, usage profile, notes
  manifest.json               ← capture session id, time range, file checksums
  summary.json                ← headline metrics — read this first
  samples.jsonl               ← battery samples, 1/min, normalized fields
  events.jsonl                ← unified timestamped event stream
  wakelocks.jsonl             ← parsed acquire/release pairs with duration_ms
  alarms.jsonl                ← AlarmManager fires with tag, type, window
  sensor_activity.jsonl       ← per-sensor enable/disable + listener identity
  drain_windows.json          ← pre-computed drain rate per state bucket
  top_offenders.json          ← leaderboards: wakelocks, alarms, CPU consumers
  hypotheses.json             ← pre-scored evidence for each hypothesis
  raw/                        ← bugreport.zip, batterystats-final.txt, logcat
                                kept as ground truth but not fed to the LLM
  README.md                   ← schema documentation
```

### 6.2 Unified event schema

Every event in `events.jsonl` — battery sample, screen on/off, wakelock
acquire, alarm fire, sensor enable, lid open/close, launcher lifecycle —
has the same shape. State fields are denormalized onto every event so the
analyst can filter without joining:

```json
{
  "ts_ms": 1729872000000,
  "ts_iso": "2026-05-20T03:00:00Z",
  "monotonic_ms": 12345678,
  "capture_session_id": "uuid...",
  "type": "wakelock_acquire | wakelock_release | screen_on | screen_off | doze_enter | doze_exit | lid_open | lid_close | alarm_fire | sensor_enable | sensor_disable | launcher_lifecycle | battery_sample | ...",
  "source": "system | launcher | <package>",
  "payload": { /* type-specific */ },
  "screen_state": "on | off",
  "lid_state": "open | closed",
  "charging": false,
  "battery_level_pct": 42,
  "in_doze": true
}
```

The `type` enum is a strict, closed set; document it in `README.md` so
the analyst doesn't have to infer it.

### 6.3 `drain_windows.json` — the file that does the work

Bucket the 24-hour window into segments by state, and emit drain rate and
top contributors per bucket. This file alone usually answers "where is the
battery going":

```json
{
  "windows": [
    {
      "state": "screen_off_doze_lid_closed",
      "duration_s": 28800,
      "battery_drop_pct": 47,
      "drain_rate_pct_per_hour": 5.9,
      "drain_rate_mah_per_hour": 88.5,
      "mean_temp_c": 31.2,
      "top_wakelock_holders": [
        {"tag": "*sync*/...", "duration_s": 1820},
        {"tag": "SensorService", "duration_s": 1402},
        {"tag": "LauncherX/...", "duration_s": 980}
      ],
      "top_wake_reasons": [
        {"reason": "Abort:Last active Wakeup Source: hall_sensor", "count": 412}
      ]
    },
    { "state": "screen_off_doze_lid_open",     ... },
    { "state": "screen_off_active",            ... },
    { "state": "screen_on",                    ... },
    { "state": "charging",                     ... }
  ],
  "baseline_drain_rate_pct_per_hour": 5.4,
  "healthy_target_pct_per_hour": 0.8
}
```

### 6.4 `hypotheses.json` — pre-scored evidence

For each working hypothesis, dump the supporting numbers so the analyst
weighs evidence directly rather than grepping logs:

Each metric is paired with a `healthy_baseline` value — the absolute
threshold a clean reference device hits when idle. The analyst reads
the actual value against that baseline directly; no cross-device
comparison is required.

```json
{
  "lid_sensor_chatter": {
    "lid_events_24h": 1247,
    "lid_events_while_lid_reported_closed": 980,
    "wake_reasons_attributed_to_hall_sensor": 412,
    "sensor_listeners_registered": ["com.example.launcher", "com.tcl.dialer"],
    "healthy_baseline_lid_events_24h": 100
  },
  "launcher_wakelock_leak": {
    "launcher_partial_wakelock_seconds_screen_off": 4821,
    "longest_held_wakelock_ms": 187000,
    "wakelocks_never_released": 3,
    "healthy_baseline_seconds": 120
  },
  "companion_app_binding": {
    "packages_bound_to_launcher": ["com.tcl.dialer", "com.tcl.contacts"],
    "bound_seconds_screen_off": 84600,
    "healthy_baseline_seconds": 12000
  },
  "radio_thrash": {
    "mobile_radio_active_seconds_screen_off": 14200,
    "wifi_scan_count": 1899,
    "cell_scan_count": 720,
    "healthy_baseline_radio_active_seconds": 2400
  },
  "doze_blocked": {
    "expected_doze_entries_24h": 24,
    "actual_doze_entries_24h": 2,
    "longest_continuous_doze_s": 480,
    "healthy_baseline_doze_entries": 22
  }
}
```

Note the addition of `doze_blocked` — given the linear drain shape, the
single most likely root cause is that the device is not entering deep
doze at all when the lid is closed. Computing the actual vs. expected
doze entry count makes that immediately visible.

### 6.5 `summary.json` — the one-pager

Built last, after the other files exist. Twenty to forty normalized fields
that let the analyst eyeball the device's state without opening any of
the underlying files:

```json
{
  "device_id": "...",
  "capture_window_hours": 24,
  "battery_drop_total_pct": 91,
  "drain_rate_screen_off_lid_closed_pct_per_hour": 5.9,
  "drain_rate_screen_on_pct_per_hour": 8.2,
  "screen_on_time_s": 4200,
  "doze_total_time_s": 960,
  "doze_entries": 2,
  "top_5_wakelocks_by_duration": [...],
  "top_5_alarms_by_count": [...],
  "top_5_processes_by_cpu_time": [...],
  "sensor_events_24h": 1247,
  "launcher_cpu_time_s": 1820,
  "launcher_wake_count": 412,
  "mobile_radio_active_s": 14200,
  "wifi_scan_count": 1899
}
```

Read `summary.json` first; drop into `hypotheses.json` when a metric
looks off; drop into the per-`wakeup_sources` / `events.jsonl` / raw
dumps only when `hypotheses.json` doesn't already explain it.

## Phase 7 — Per-device analysis loop

For each device's bundle, in order:

1. Open `summary.json`. Is `drain_rate_screen_off_lid_closed_pct_per_hour`
   well above the healthy target (~0.5–1.0 %/hour)? If yes, this device
   genuinely has the bug we're chasing. If no, the user may have a
   different problem — note it and move on.
2. Open `hypotheses.json`. For each hypothesis, compare the actual value
   to the paired `healthy_baseline_*` field. Anything more than ~3×
   baseline is a candidate; anything 10×+ is a likely cause.
3. For the top candidate, open the relevant raw file under `raw/`:
   - `lid_sensor_chatter` → `dumpsys/wakeup_sources-*.txt`,
     `dumpsys/wakeup_reason-*.txt`, lid events in `events.jsonl`.
   - `doze_blocked` → `dumpsys/deviceidle-*.txt`,
     `dumpsys/suspend_stats-*.txt`.
   - `launcher_wakelock_leak` → `dumpsys/batterystats-checkin-*.txt`
     (search for the launcher's UID).
   - `companion_app_binding` → `dumpsys/activity_processes-*.txt`.
   - `radio_thrash` → `dumpsys/wifi-*.txt`,
     `dumpsys/connectivity-*.txt`, `dumpsys/netstats-*.txt`.
4. Write a 5-line writeup against the device: what the bundle showed,
   what the suspected cause is, what the fix would be.

If multiple affected devices accumulate, scan their writeups for shared
patterns — same hypothesis dominant across three devices is much
stronger evidence than one device, even without a formal cohort
comparison.

## Working hypotheses

Given the observed linear ~4%/hour idle drain on a lid-closed flip phone,
ranked by my prior:

1. **Doze is not entering or not staying entered.** Whatever the symptom
   looks like (wakelock, sensor, binding), the mechanism is that the SoC
   is not reaching the lowest power state. `deviceidle` dumps and the
   `doze_entries` metric in `hypotheses.json` are the direct read.
2. **Lid / hall sensor chatter.** A noisy or stuck hall sensor reports
   open/close events continuously while the device is physically closed,
   keeping the SoC awake. Cross-check the lid event count against the
   healthy baseline in `hypotheses.json` and the `gpio_keys` /
   `ClamShell_key` counts in `wakeup_sources-*.txt` and
   `wakeup_reason-*.txt`.
3. **Launcher wakelock leak.** A wakelock acquired on a launcher event
   that never gets released — common pattern when the launcher binds to
   a companion service whose unbind path has a bug.
4. **Companion app binding.** The launcher is keeping the dialer,
   contacts, or another TCL system app bound continuously, preventing
   that app from being frozen by app standby.
5. **Radio thrash.** Continuous cellular or Wi-Fi scanning triggered by
   a launcher widget or background work. Less likely given the flip form
   factor (no widgets in the usual sense) but worth excluding.

## Tools and dependencies

- **Battery Historian** (Google, Docker image) for visual inspection of
  `bugreport.zip`. Useful as a sanity check on top of the structured
  bundle, especially for kernel wakelocks.
- **`build-analysis-bundle.py`** — the post-processing script we write,
  which parses `dumpsys batterystats --checkin` and the launcher's raw
  logs into the structured schema above. This is the linchpin of the
  whole plan; once it exists, every downstream file falls out of it.

## Deliverables checklist

- [ ] `DiagnosticsService` implementation in the launcher, gated by
      `DIAGNOSTICS_ENABLED` build flag.
- [ ] Wakelock / alarm / job logging proxies.
- [ ] On-device hourly snapshot scheduler for the privileged `dumpsys`
      subcommands.
- [ ] Hidden settings screen with consent flow and reset-session button.
- [ ] `build-analysis-bundle.py` post-processor.
- [ ] Capture a healthy-baseline bundle from a known-good reference device
      (a dogfood phone you control) to derive the `healthy_baseline_*`
      thresholds used in `hypotheses.json`.
- [ ] Cut `launcher-diag-X.Y.Z` build off the beta channel.
- [ ] Recruit and consent affected users one at a time.
- [ ] 24-hour capture per device.
- [ ] Per-device analysis pass, root-cause writeup, fix on next beta.
