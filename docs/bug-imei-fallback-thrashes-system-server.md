# Bug: IMEI root-fallback cascade thrashes system_server on low-RAM devices

**Status:** open
**Severity:** high — causes multi-second UI freeze and a system_server watchdog event
**First observed:** v9, 2026-05-20, on TCL/MediaTek flip phone (240×320, ~tens of MB free RAM)
**Repro:** open the launcher on a device with no cached IMEI; freeze happens within ~5 seconds of `AllAppsActivity` becoming visible.

## Summary

When `SimInfoReader.readImei` (and the wider `readAll` cascade) fires on a memory-constrained device, the chain of `su` subprocesses it spawns wakes the Magisk root server repeatedly, the low-memory killer starts evicting processes, and system_server's main thread stalls for 10–20 seconds. The screen freezes and the watchdog snapshots system_server before things recover.

## Evidence (from `adb logcat`, 2026-05-20 09:51–09:54)

Sequence after launcher v9 install:

```
09:52:29  AllAppsActivity launched
09:52:35  SimInfoReader: TelephonyManager.imei denied — falling back to root
09:52:35–43  Eight su PIDs spawn back-to-back (12889, 12901, 12910, 12924, 12940, 12970, 12980, 12986)
            Each logs: avc: denied { search } for name="mm" ... sysfs_mm  scontext=untrusted_app
09:52:41.254  lowmemorykiller: Kill 'com.topjohnwu.magisk' (12539) ...
              device is low on swap (0kB < 68696kB) and thrashing (31%)
09:52:41.441  lowmemorykiller: Kill 'android.process.media' (12826) ...
09:52:41.951  lowmemorykiller: Kill 'com.android.settings' (12559) ...
09:53:29.808  Looper: Slow dispatch took 21212ms main h=NotificationManagerService$WorkerHandler
09:53:44.903  Looper: Slow dispatch took 5871ms main h=AlarmManagerService$AlarmHandler
09:53:45.323  Watchdog: WAITED_HALF
09:54:18.887  tombstoned: received crash request for pid 1054 (system_server)
09:54:18.889  system_server: Wrote stack traces to tombstoned
```

During the stall, `FramebufferSurface` reports `fps=0.11` — that's the visible "freeze". system_server didn't die; the watchdog took a Java backtrace after the long stall and the app stack recovered (a new `dumbdownlauncher` PID is forked at 09:54:16).

## Root cause

Three things compound:

1. **The fallback cascade is unconditional and on the calling thread.**
   `SimInfoReader.readImei` (`app/src/main/java/.../registration/SimInfoReader.kt`, lines ~157–197) does up to 6 `parseServiceCall` invocations (3 codes × {with subId, without}), then 4 `getprop` calls, then `queryField("imsi")`, then `getprop ro.serialno` — every one of which is `ProcessBuilder("su", "-c", cmd).start()`. On builds where the service calls fail (TCL/MediaTek emit "Attempt to get length of null array"), the cascade runs to the end before returning.

2. **Each `su` wakes Magisk's root server.** `com.topjohnwu.magisk:root:0` starts a JVM to handle the call. On a device with ~tens of MB free, that's enough to push the system into LMK territory.

3. **LMK kills Magisk mid-cascade, the next `su` respawns it.** You can see Magisk PIDs 12260, 12539, 13195, 13282 start and die in a loop. Each respawn pays the JVM-init cost again. Meanwhile system_server's main thread is blocked behind binder traffic to a churning Magisk and a flapping `com.android.settings`/`android.process.media`.

The SELinux `avc: denied { search } ... sysfs_mm` line is unrelated to the freeze — it's the kernel refusing an untrusted_app's traversal of `/sys/.../mm/`. The `su` calls still return; they're just noisy. But it confirms our `su` subprocess is the source of the spam.

## Why v9 in particular

The boot-side path at `DumbDownApp.kt:436–448` polls `SimInfoReader.readImei` every 10s for up to 10 minutes when no IMEI is cached. On a first install with no cached IMEI, that fires on launcher start. Combined with the `readAll` call from `DeviceRegistrationScreen.kt:128` (and `BootRegistrationScreen.kt:202`), several full cascades can run in the first minute of the app being open.

`SimInfoReader.kt` already has comments acknowledging the cost ("avoids ~8 failed `su -c` round trips that take 10-15 seconds") and that callers must move it off the UI thread. The freeze suggests at least one caller isn't doing that, or the boot polling thread is busy enough to starve other work on a 240×320 device with one slow core.

## Fix proposals (pick a combination)

1. **Cache aggressively, fail closed.** After the first successful `readImei` on a device, write the result to SharedPreferences with a long TTL. Treat absence of a SIM (and absence of a cached value) as terminal for a session — don't re-run the cascade on every launcher start. `DeviceRegistrar.getCachedImei` is already the preferred path in `DumbDownApp.kt`; the gap is when no cache exists yet, which is exactly when we currently thrash hardest.

2. **Short-circuit the service-call codes per device.** Once a code (e.g. `1` with `i32 0`) succeeds on a device, persist that pair and only try it on subsequent calls. The cascade only exists to discover the right combo — there's no reason to re-discover it every run.

3. **Pool one `su` shell instead of spawning N.** A single long-lived `su` process that we pipe commands into removes the JVM-init-per-call cost on Magisk's side. `libsu` (topjohnwu) has this; if we don't want the dependency, a hand-rolled `Process` we keep open and feed newline-terminated commands works.

4. **Detect low-memory devices and skip the cascade.** `ActivityManager.isLowRamDevice()` or a hard MemTotal check could downgrade the cascade to "TelephonyManager + content provider only" — the two cheapest paths — on these flip phones. The siminfo content query alone already covers the working cases on TCL/MediaTek per the existing `readAll` fast-path comment.

5. **Move the boot poller off the schedule it's on.** Polling every 10s for 10 minutes (`DumbDownApp.kt:437–448`) means up to 60 cascades after a fresh install with no SIM. Switch to exponential backoff (e.g. 10s, 30s, 1m, 5m) and bail after fewer attempts.

6. **Watchdog the calls.** Wrap each `runSu` invocation in a short timeout (e.g. 1.5s); on TCL/MediaTek where service calls always fail, that bounds the cascade to ~10s worst-case instead of running to completion regardless of how slow Magisk is to respond.

(1) + (5) together should eliminate the user-visible freeze on first install. (2) + (3) reduce the steady-state cost. (4) is the safest belt-and-braces.

## Verification

After the fix, repro the original scenario:

- Wipe `SharedPreferences` to drop the cached IMEI.
- Install over the running launcher.
- Grab `adb logcat` for the first 90 seconds.

Pass criteria:

- No `Looper: Slow dispatch took ...ms` warnings above 1 second on the main thread.
- No `Watchdog: WAITED_HALF` lines.
- No `lowmemorykiller: Kill 'com.topjohnwu.magisk'` lines attributable to our `su` traffic.
- `FramebufferSurface` `fps` doesn't drop to single digits during launcher startup.

## Related code

- `app/src/main/java/com/offlineinc/dumbdownlauncher/registration/SimInfoReader.kt` — the cascade itself.
- `app/src/main/java/com/offlineinc/dumbdownlauncher/DumbDownApp.kt:436–453` — 10s × 60 boot poller.
- `app/src/main/java/com/offlineinc/dumbdownlauncher/ui/DeviceRegistrationScreen.kt:119–128` — UI-thread guard comment + `readAll` call.
- `app/src/main/java/com/offlineinc/dumbdownlauncher/ui/BootRegistrationScreen.kt:150–204` — boot-time `readAll` call.
- `app/src/main/java/com/offlineinc/dumbdownlauncher/registration/DeviceRegistrar.kt:524–526` — registration cascade.
