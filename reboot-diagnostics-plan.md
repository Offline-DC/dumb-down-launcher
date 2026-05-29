# Reboot Logging — TCL Flip 2 Launcher

## Context

One beta user on the TCL Flip 2 (model `4058W`, Android 11) reports
the phone randomly restarting at apparently arbitrary times. We don't
have correlated logs — by the time anyone goes looking, the previous
boot's state is gone.

Scope of this module is deliberately tight: **keep a rolling buffer
of the last 24 hours of logcat on disk, so when the device dies, the
file from immediately before the crash is always there to be
harvested — plus enough trailing history to look for the same pattern
recurring earlier in the day.** That's it. No post-mortem capture,
no per-reboot classification, no JSONL summary. If the cause is named
anywhere on the device, it'll be in the rolling buffer; if it isn't,
that's a separate (larger) investigation.

## Why "rolling logcat" is the right framing here

A point-in-time `logcat -d -t 10000` snapshot grabs the last ten
thousand lines at the moment it fires. If the device crashes 59
minutes after a snapshot, that snapshot is already stale and the
next one never happens. The fix is a continuous tail: there is
always an in-progress segment file open on disk, never more than
a couple of MB behind the live stream, so a crash leaves the
previous segment + the in-progress current.log behind for harvest.

## On-device module (`app/src/main/.../diagnostics/`)

### `RollingLogcatTail.kt`

The actual machinery. Spawns `su -c logcat -v threadtime *:V` as a
long-lived child, appends every line to
`<filesDir>/diag/rolling-logcat/current.log`, rotates that file to
`segment-YYYYmmdd-HHMMSS.log` when it hits 10 MB, and gzips the
rotated segment in the background.

Eviction is layered:

  1. **Time-based (the normal path):** on every rotate, any segment
     older than 24 hours is deleted. This is what bounds the ring
     in practice.
  2. **Size-based (the safety net):** if the ring still exceeds the
     500 MB cap after time-based eviction — which only happens on a
     pathologically chatty device producing > 200 MB/hour of verbose
     logcat — segments are evicted oldest-first until under budget.

The subprocess is respawned with bounded exponential backoff if it
dies (Magisk policy reload, OOM kill, etc.). Mirrors every closed
segment to `<getExternalFilesDir(null)>/diag/rolling-logcat/` so a
no-root `adb pull` retrieves the bundle.

On every fresh process start, if a previous `current.log` is still
sitting on disk (i.e. the launcher process died without orderly
shutdown), it's renamed to `segment-pre-<ts>.log` so it survives
eviction and is named like the other segments. That renamed file
is "the log file from before the crash" — the single most useful
artifact this module produces.

`*:V` is deliberately liberal. We'd rather over-collect and trim
than miss the line that names the cause. The disk cost is bounded
by the rolling cap, not by the verbosity level.

### `RebootLoggingService.kt`

Foreground service (`dataSync` type, `START_STICKY`) so the tail
survives doze and the OOM killer. Started from
`DumbDownApp.onCreate` whenever both
`BuildConfig.REBOOT_LOGGING_ENABLED` and
`RebootLoggingStore.enabled` are true. Stopped by toggling either
off.

### `RebootLoggingStore.kt`, `RebootLoggingConfig.kt`

SharedPreferences opt-in + the rotation/budget constants.

### `ShellRunner.kt`, `DiagPaths.kt`

Plumbing. `ShellRunner` is the `su -c` runner; `DiagPaths` resolves
`<filesDir>/diag/` plus the `/sdcard/Android/data/` mirror.

## Companion script (`scripts/pull-reboot-evidence.sh`)

One command, one device. Pulls the rolling-logcat directory into a
stamped local folder, decompresses the segments, sorts them
chronologically, and concatenates into a single timeline file so
the support engineer reads one log instead of unzipping six.

## Manifest + build gating

  - `app/build.gradle.kts`: `buildConfigField("boolean",
    "REBOOT_LOGGING_ENABLED", "false")`. Flip to `true` only on
    the diag beta build that ships to the affected user.
  - `AndroidManifest.xml`: adds `FOREGROUND_SERVICE` and
    `WAKE_LOCK` permissions, registers
    `.diagnostics.RebootLoggingService` as
    `android:exported="false"` with
    `foregroundServiceType="dataSync"`. No `READ_LOGS`, `DUMP`,
    or `BATTERY_STATS` on the app itself — those flow through
    `su -c`.

## Capture protocol

1. Build with `REBOOT_LOGGING_ENABLED=true` and a `-beta.<N>` version
   suffix (currently `v4.77.0-beta.0`). The suffix gates the build to
   the beta channel so only enrolled beta users (Marco) auto-update
   into it; production users on the no-suffix version don't see it.
2. Push via the existing beta channel.
3. On Marco's device, the first time the launcher process starts
   under the new build, `RebootLoggingService.startIfEnabled` sees
   `enabledSinceMs == 0L`, auto-flips the runtime opt-in, and starts
   the foreground service. A persistent "Storing logs for testing…"
   notification appears in the shade — that's confirmation that
   collection is live. No adb step required.
4. Wait. The next time the device reboots, the rolling-logcat ring
   carries forward — the `segment-pre-*.log` left behind is the
   in-progress segment from immediately before the crash, and the
   preceding ~24 hours of gzipped segments are still on disk to
   look for the same fault earlier in the day.
5. Harvest with:
   ```bash
   ./scripts/pull-reboot-evidence.sh
   ```
6. To stop collection without uninstalling (e.g. after Marco's
   investigation closes), flip the opt-in off:
   ```bash
   adb shell run-as com.offlineinc.dumbdownlauncher \
     "sed -i 's/reboot_logging_enabled\">true/reboot_logging_enabled\">false/' \
      shared_prefs/reboot_logging_prefs.xml"
   ```
   `enabledSinceMs` stays non-zero so subsequent process starts respect
   the explicit kill rather than re-auto-enabling.

## Disk budget

Two limits, layered:

  - **24-hour retention** is the normal eviction signal. A typical
    chatty Android 11 device produces ~10–15 MB of verbose logcat per
    hour; gzip rotation lands rotated segments around ~1 MB each, so
    24 hours of history compresses to roughly 25–40 MB on disk in the
    common case. Almost always the binding constraint.
  - **500 MB hard cap** is the safety net. Worst-case verbose log
    spam (50+ MB/hour, sustained) compresses to ~120 MB/day, still
    under the cap. The cap only matters on a pathological device or
    if log spam temporarily spikes well above steady state. On the
    TCL Flip 2 the cap is ~11 % of the ~4.5 GB `/data` partition —
    deliberately generous so the size-based eviction is the rare
    exception, not the rule. If we ever observe drain on the user's
    disk because of this we can dial it down later without breaking
    anything that already harvested.

## Privacy

Logcat contains URLs, notification text, account hashes, and
similar. The opt-in screen must say this explicitly, the support
engineer must verbalise it on the call, and the harvest bundle
must be treated as sensitive. The mirror under
`/sdcard/Android/data/<pkg>/` is sandboxed to the launcher's uid on
Android 11 — other apps can't read it without root or USB
debugging.

When the investigation closes:

  - Flip `REBOOT_LOGGING_ENABLED` back to false in the next build.
  - Toggle `reboot_logging_enabled` to false in prefs (the service
    self-stops on the next process start).
  - Delete `<filesDir>/diag/` and the mirror.
