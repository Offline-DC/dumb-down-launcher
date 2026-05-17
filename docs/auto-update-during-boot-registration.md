# Auto-update during device registration

## Goal

During the device-setup boot screen, after the `CHECKING_BUNDLE` step
completes, the launcher should check GitHub for a newer release of itself.
If one exists, the launcher should download it and `pm install -r` it
silently. After the install kills the running process and Android
re-launches the launcher as HOME, the user must land back on the **next**
onboarding step (the linking screen), **not** at the start of device
setup again.

The "don't restart setup" property must only hold for the auto-update
relaunch path. A cold boot, an OOM kill, or a user re-entering Device
Setup from AllAppsActivity must all run the full
`SIM → /register → bundle → update` pass exactly as they do today.

## Where this slots into the existing flow

`BootRegistrationScreen.runOnce()` (`app/src/main/java/.../ui/BootRegistrationScreen.kt`)
currently steps through:

```
WAITING → LOADING → FINISHING_SIM → REGISTERING → CHECKING_BUNDLE → DONE
```

The plan inserts a `CHECKING_UPDATE` state (with a `DOWNLOADING_UPDATE`
sub-state) between `CHECKING_BUNDLE` and `DONE`:

```
... → CHECKING_BUNDLE → CHECKING_UPDATE → [DOWNLOADING_UPDATE → pm install -r] → DONE
                                       ↘ (no update or failure) ↗
```

The existing update infrastructure under `app/src/main/java/.../update/`
(`UpdateChecker`, `UpdateCheckWorker`, `DownloadAndInstallReceiver`) is
re-used. The launcher is platform-signed and has `su`, so
`pm install -r` runs silently with no user prompt.

## State persistence considerations

`pm install -r` issues SIGKILL to the running launcher — `onPause`,
`onDestroy`, and the `QueuedWork` thread that flushes `.apply()` writes
do **not** get a chance to run. Anything not durable on disk before
`pm install` is invoked is lost.

`DeviceRegistrar.refreshBundleFlags` writes
`PairingStore.hideAudioBundle` and `PairingStore.hideSmartTxt` using the
setters in `PairingStore.kt`, both of which use `.apply()`
(asynchronous). `saveRegistration()` uses `.commit()` on `device_registered`
and `flip_phone_number`, but it goes through a different in-process
`EditorImpl` than the bundle-flag writes, so any drain of pending
`.apply()` writes is implementation-dependent and not safe to rely on.

The fix is one explicit synchronous batch commit immediately before
`pm install -r`, covering every key the post-restart code path reads:

```kotlin
val prefs = ctx.getSharedPreferences("device_pairing", Context.MODE_PRIVATE)
prefs.edit()
    .putBoolean("device_registered", true)
    .putString("flip_phone_number", phone)
    .putBoolean("hide_audio_bundle", PairingStore(ctx).hideAudioBundle)
    .putBoolean("hide_smart_txt", PairingStore(ctx).hideSmartTxt)
    .putBoolean("resume_after_update", true)
    .commit()   // synchronous fsync — durable before pm install runs
```

One batch, one fsync, no inter-instance drain assumptions.

## The one-shot `resumeAfterUpdate` flag

The fast-path that skips re-doing
`SIM → /register → bundle → update` on the post-install relaunch is gated
on a single new field in `PairingStore`:

```kotlin
/**
 * One-shot signal that the launcher just auto-installed itself during
 * BootRegistrationScreen and is being relaunched by Android-as-HOME.
 * BootRegistrationScreen consumes (and clears) this flag on its first
 * read after relaunch to skip the SIM/register/bundle/update pass that
 * already ran pre-install. Never set anywhere else — a cold boot, an
 * OOM kill, or a Device Setup re-entry all leave this false, so they
 * all run the full pass as before.
 */
var resumeAfterUpdate: Boolean
    get() = prefs.getBoolean("resume_after_update", false)
    set(v) = prefs.edit().putBoolean("resume_after_update", v).commit()
```

The fast-path at the top of `BootRegistrationScreen.runOnce()`:

```kotlin
val store = PairingStore(ctx)
if (store.resumeAfterUpdate &&
    store.deviceRegistered &&
    !store.flipPhoneNumber.isNullOrBlank()
) {
    store.resumeAfterUpdate = false   // consume so future kills don't loop
    phoneNumber = store.flipPhoneNumber
    state = BootState.DONE
    return
}
```

The flag is written in exactly one place (`OnboardingUpdater`, right
before `pm install -r`) and read in exactly one place
(`BootRegistrationScreen`, right after relaunch).

### Behavior matrix

| Scenario | `resumeAfterUpdate` | Boot screen behavior |
| --- | --- | --- |
| Fresh phone, first boot | false | Full `SIM → /register → bundle → update` pass |
| Cold boot mid-onboarding (battery, OOM) | false | Full pass (same as today) |
| Device Setup re-entry from AllApps | false | Full pass (re-fetches bundle flags in case tier changed) |
| Relaunch after auto-installed update | **true** | Fast-path straight to `DONE` → linking screen |

Nothing in the AllApps "device setup" re-entry path touches
`resumeAfterUpdate`, so re-entry always re-runs everything — preserving
the existing intentional behavior that re-entry refreshes bundle flags.

## The new `OnboardingUpdater` helper

A new file at `app/src/main/java/.../update/OnboardingUpdater.kt` with
one entry point:

```kotlin
suspend fun checkAndInstall(
    ctx: Context,
    phone: String,
    onPhase: (BootState) -> Unit,
): InstallOutcome
```

`InstallOutcome` is one of `NO_UPDATE`, `NETWORK_ERROR`, `INSTALLING`.
All work runs on `Dispatchers.IO`.

Flow:

1. `NetworkUtils.isNetworkAvailable(ctx)` short-circuit → `NETWORK_ERROR`.
2. `UpdateChecker.fetchLatest(PairingStore(ctx).betaTesterMode)`. Look
   at the `"dumb-down-launcher"` entry only — Snake updates stay on the
   periodic worker, not on the onboarding critical path.
3. If `info.versionCode <= BuildConfig.VERSION_CODE` → `NO_UPDATE`.
4. Emit `BootState.DOWNLOADING_UPDATE` via `onPhase`. Download the APK
   to `ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)`, using
   the same `DownloadManager` pattern as
   `DownloadAndInstallReceiver.startDownload`, and `await` completion.
5. Do the synchronous batch commit shown above.
6. Run `su -c pm install -r <apk path>`.
   - On success: `pm install` kills our process; control never returns.
   - On non-zero exit / exception: clear `resumeAfterUpdate` and return
     `NO_UPDATE`. The user finishes onboarding on the current version;
     the periodic `UpdateCheckWorker` will surface the update later.

## `BootRegistrationScreen` changes

- Extend `BootState` with `CHECKING_UPDATE` and `DOWNLOADING_UPDATE`.
- Add the fast-path block at the top of `runOnce()` (shown above).
- After the existing `fetchBundleFlags(phone)` call, before
  `state = BootState.DONE`, add:

  ```kotlin
  state = BootState.CHECKING_UPDATE
  val outcome = withContext(Dispatchers.IO) {
      OnboardingUpdater.checkAndInstall(ctx, phone) { newPhase ->
          state = newPhase   // drives DOWNLOADING_UPDATE UI
      }
  }
  if (outcome == OnboardingUpdater.InstallOutcome.INSTALLING) {
      awaitCancellation()   // process is about to die; don't race to DONE
  }
  state = BootState.DONE
  ```
- Mirror the same update call at the tail of the `REG_ERROR` retry
  handler so a successful retry also runs the update check.
- Render copy:
  - `CHECKING_UPDATE` → "checking for updates…"
  - `DOWNLOADING_UPDATE` → "downloading update…"

Both new states sit alongside `LOADING / FINISHING_SIM / REGISTERING /
CHECKING_BUNDLE` in the existing spinner branch, so the `showStillTrying`
reassurance line after 30 s and the "this can take a few minutes"
footer apply to them automatically.

## Files that change

- `app/src/main/java/.../pairing/PairingStore.kt`
  - Add `resumeAfterUpdate` field (synchronous `.commit()` setter).
- `app/src/main/java/.../update/OnboardingUpdater.kt` *(new)*
  - The `checkAndInstall` suspend function described above.
- `app/src/main/java/.../ui/BootRegistrationScreen.kt`
  - New `BootState` values, fast-path block, update-check call, UI text,
    and `REG_ERROR` retry mirroring.
- `app/src/main/java/.../MainActivity.kt`
  - No routing changes needed. The existing `nextStepAfterBoot` already
    sends the user to the linking screen when `linkingChoice == null`,
    which is exactly what the fast-path's `state = DONE → onComplete`
    triggers.

`UpdateChecker`, `UpdateCheckWorker`, `DownloadAndInstallReceiver`,
`DeviceRegistrar`, and `PlatformPreferences` need no changes.

## Failure handling summary

| Failure mode | Behavior |
| --- | --- |
| No network during `CHECKING_UPDATE` | Log, fall through to `DONE`. No error UI. |
| GitHub fetch fails mid-flight | Log, fall through to `DONE`. |
| `DownloadManager` reports failure | Log, fall through to `DONE`. |
| `pm install -r` returns non-zero | Clear `resumeAfterUpdate`, fall through to `DONE`. Periodic worker will retry. |
| Process killed between download and install | `resumeAfterUpdate` not yet set on disk → next boot runs full pass, no half-state. |
| Process killed *after* commit but `pm install` somehow not invoked | Flag is consumed on first read after relaunch; worst case is one cold boot fast-pathing inappropriately, then back to normal. |

---

# Testing

Three layers, ordered easiest first. Each catches a different class of
bug — running all three is recommended.

## Layer 1 — Unit-test the routing logic

No device needed; ~5 minutes. The fast-path is pure state inspection, so
extract the decision into a function:

```kotlin
// in BootRegistrationScreen or a new BootDecision.kt
enum class BootDecision { FAST_PATH_TO_DONE, FULL_PASS }

fun decideBootAction(store: PairingStore, isReentry: Boolean): BootDecision =
    if (!isReentry &&
        store.resumeAfterUpdate &&
        store.deviceRegistered &&
        !store.flipPhoneNumber.isNullOrBlank()
    ) BootDecision.FAST_PATH_TO_DONE
    else BootDecision.FULL_PASS
```

Then under `app/src/test/`:

```kotlin
@Test fun fastPath_fires_when_resumeFlag_set_after_update() {
    val store = PairingStore(context).apply {
        saveRegistration("+15551234567")
        resumeAfterUpdate = true
    }
    assertEquals(BootDecision.FAST_PATH_TO_DONE,
                 decideBootAction(store, isReentry = false))
}

@Test fun reentry_never_fastPaths_even_when_registered() {
    val store = PairingStore(context).apply {
        saveRegistration("+15551234567")
        resumeAfterUpdate = true   // even if somehow set
    }
    assertEquals(BootDecision.FULL_PASS,
                 decideBootAction(store, isReentry = true))
}

@Test fun coldBoot_after_OOM_runs_full_pass() {
    val store = PairingStore(context).apply {
        saveRegistration("+15551234567")
        // resumeAfterUpdate stays false
    }
    assertEquals(BootDecision.FULL_PASS,
                 decideBootAction(store, isReentry = false))
}

@Test fun freshPhone_runs_full_pass() {
    val store = PairingStore(context)  // nothing set
    assertEquals(BootDecision.FULL_PASS,
                 decideBootAction(store, isReentry = false))
}

@Test fun resumeFlag_consumed_on_fast_path() {
    val store = PairingStore(context).apply {
        saveRegistration("+15551234567")
        resumeAfterUpdate = true
    }
    decideBootAction(store, isReentry = false)
    // After consumption (move the flag-clear into decideBootAction or
    // assert via a separate consume() step in the production code):
    assertFalse(store.resumeAfterUpdate)
}
```

Catches: routing bugs, missing re-entry guard, forgetting to consume the
flag.

## Layer 2 — Manually arm the flag and verify the post-restart UX

Real device or rooted emulator; ~10 minutes. Tests the screen-transition
behavior without involving a real APK download or install.

```bash
# 1. Install current launcher build, run device setup, stop on the
#    linking screen (i.e. boot_registration has completed once).
adb shell am force-stop com.offlineinc.dumbdownlauncher

# 2. Inspect state — deviceRegistered should be true, linking choice null
adb shell "su -c cat /data/data/com.offlineinc.dumbdownlauncher/shared_prefs/device_pairing.xml"
adb shell "su -c cat /data/data/com.offlineinc.dumbdownlauncher/shared_prefs/launcher_prefs.xml"

# 3. Arm resume_after_update manually (simulates what OnboardingUpdater
#    writes right before pm install -r).
adb shell "su -c 'sed -i \
  \"s|</map>|<boolean name=\\\"resume_after_update\\\" value=\\\"true\\\" />\\n</map>|\" \
  /data/data/com.offlineinc.dumbdownlauncher/shared_prefs/device_pairing.xml'"

# 4. Relaunch
adb shell am start -n com.offlineinc.dumbdownlauncher/.MainActivity

# Expected: single frame of duck, then linking screen.
# NOT expected: any of "waiting for ur sim…" / "activating ur phone…" /
# "checking bundle…" delays.
```

Negative test — re-entry should NOT fast-path even when the flag is set:

```bash
# Re-arm the flag the same way as above, then:
# Tap AllApps → "device setup".
# Expected: full boot pass runs (SIM/register/bundle/update).
```

Catches: persistence-write ordering bugs, fast-path UI flicker, the
re-entry override accidentally being bypassed.

## Layer 3 — End-to-end test of the actual auto-download

Real device or rooted emulator + a local HTTP server; ~30 minutes.

### Step 1. Add a debug-only API override hook

In `UpdateChecker.kt`:

```kotlin
private fun launcherApiUrl(ctx: Context): String {
    if (BuildConfig.DEBUG) {
        val override = ctx
            .getSharedPreferences("debug_update", Context.MODE_PRIVATE)
            .getString("launcher_api_url", null)
        if (!override.isNullOrBlank()) return override
    }
    return LAUNCHER_API
}
```

Plumb `ctx` through `fetchLatest` (small refactor — keep the change
behind `BuildConfig.DEBUG` so it's a no-op in release builds).

### Step 2. Serve a fake release from your workstation

```bash
mkdir /tmp/fake-releases && cd /tmp/fake-releases

# Build a "newer" launcher APK: edit app/build.gradle so
#   versionCode = 99999
#   versionName = "99.0.0"
# then in the launcher repo:
./gradlew assembleRelease
cp app/build/outputs/apk/release/app-release.apk /tmp/fake-releases/

# Fake GitHub releases JSON
cat > /tmp/fake-releases/releases.json <<'EOF'
[
  {
    "tag_name": "v99.0.0",
    "draft": false,
    "prerelease": false,
    "body": "version_code=99999",
    "assets": [
      {
        "name": "app-release.apk",
        "state": "uploaded",
        "browser_download_url": "http://10.0.2.2:8080/app-release.apk"
      }
    ]
  }
]
EOF

python3 -m http.server 8080
```

Use `10.0.2.2` for an emulator (AVD's host-loopback alias). For a
physical device, use your laptop's LAN IP.

### Step 3. Point the device at the local server

Install a build with the **lower** versionCode (the current release one),
then plant the debug override:

```bash
adb shell "run-as com.offlineinc.dumbdownlauncher mkdir -p shared_prefs"
adb shell "run-as com.offlineinc.dumbdownlauncher sh -c 'cat > shared_prefs/debug_update.xml'" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
  <string name="launcher_api_url">http://10.0.2.2:8080/releases.json</string>
</map>
EOF
```

### Step 4. Run device setup and watch logs

```bash
adb shell pm clear com.offlineinc.dumbdownlauncher
adb shell am start -n com.offlineinc.dumbdownlauncher/.MainActivity

adb logcat -s BootRegistration OnboardingUpdater UpdateChecker DeviceRegistrar
```

Expected sequence:

1. Boot registration runs through `CHECKING_BUNDLE`.
2. `CHECKING_UPDATE` — UpdateChecker hits
   `http://10.0.2.2:8080/releases.json`, sees versionCode 99999 >
   installed, returns the local APK URL.
3. `DOWNLOADING_UPDATE` — DownloadManager fetches `app-release.apk` from
   the local server.
4. `OnboardingUpdater` commits the resume flag + registration state,
   invokes `su -c pm install -r <apk path>`.
5. Process is killed; Android relaunches the launcher as HOME.
6. `MainActivity.onCreate` → routes to `boot_registration` (linkingChoice
   still null).
7. `BootRegistrationScreen` fast-path fires (look for the consume log
   line), `state` goes straight to `DONE`.
8. Linking screen appears.

### Optional: stub install for non-rooted environments

If your dev environment can't run `pm install -r` silently (no root, not
platform-signed), short-circuit the install in debug builds:

```kotlin
private fun installApk(apk: File): Boolean {
    if (BuildConfig.DEBUG && BuildConfig.STUB_INSTALL) {
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 500)
        return true
    }
    // real path
    return exec("su -c pm install -r ${apk.absolutePath}").exitCode == 0
}
```

`Process.killProcess(myPid())` is a faithful simulation of what
`pm install -r` does to the running launcher — combined with the resume
flag write right before it, you get the exact post-install relaunch UX
without needing root or a real install.

Catches: download wiring bugs, the actual silent install path, the
end-to-end ordering of `commit() → kill → relaunch → fast-path`.
