# Drain report — I76LXWVSOVU8MBEY (TCL 4058G / Android 11)

- **Pull:** `diag_pulls/I76LXWVSOVU8MBEY_2026-06-17T16-36-49Z`
- **Data window:** 2026-06-12 → 2026-06-13 (~21 h, screen-off span 20.6 h)
- **Triage:** `drain_summary.json` in the pull dir

## TL;DR

Idle drain is **~1.12 %/hr screen-off** (concern zone; target ≤ 0.8, ~89 h
standby vs. the 100 h+ target) with an **in-Doze median of 97 mA** against a
≤ 10 mA floor — i.e. the device almost never reaches deep suspend. The
**suspend abort rate is 18.6 %** (target < 1 %).

The dominant causes are **not launcher code**:

1. **Wi-Fi PNO scanning for out-of-range saved networks** — Wi-Fi is ON but
   `DISCONNECTED`, with two saved SSIDs nowhere in range. The framework
   background-scans for them forever: **85,969 WLAN wakeups** (30× the next
   source) and the `WLAN AHB ISR` suspend abort (34×).
2. **Cellular-only operation** — `mobile_radio=231, wifi_radio=0`; modem
   keepalives (`ccmni_md`, `*telephony-radio*` wakelock) keep the modem off
   the suspend floor.
3. **MediaTek platform locks** — `bat_percent_notify_lock`, `dlpt_notify_lock`,
   `PTIM_wakelock`, `mtx-auxadc`, `alarmtimer`. App can't change these.

The launcher's own footprint this window was **modest**: ~80 s of CPU
wakelock across ~18 hourly-refresh runs, GPS held only **2.35 s total** (no
pinned GPS session), and its alarms logged **0 wakeups**. So nothing the
launcher did is the headline drain *today*. But two launcher code paths are
real, fixable levers that get worse on exactly this device's profile
(Wi-Fi-disconnected, cellular-only), and one is a latent foot-gun.

## Current draw by state (discharging)

| State | n | mean | median | max |
|---|---|---|---|---|
| screen_on | 43 | 332.1 | 300.8 | 822.6 mA |
| screen_off_not_doze | 34 | 153.6 | 75.4 | 515.1 mA |
| screen_off_in_doze | 15 | 115.8 | **97.1** | 254.9 mA |

Floor target ≤ 10 mA. In-Doze median **97 mA** ⇒ something holds it off the
floor continuously. The screen_off_not_doze median (75 mA) being *lower* than
in-Doze is the tell that the abort storm — not a launcher wake — is what keeps
it busy: it's thrashing suspend/resume rather than sitting in a clean wake.

## Root cause 1 — Wi-Fi PNO scan for absent saved networks (platform, not launcher)

Evidence, `dumpsys/wifi-*.txt`:

```
Wi-Fi is enabled
mWifiInfo SSID: <unknown ssid>, Supplicant state: DISCONNECTED, RSSI: -127
ID: 0 SSID: "GRASSROOTS_ANALYTICS"   (saved, out of range)
ID: 1 SSID: "STARR Guest Wireless"   (saved, out of range)
retrievePnoNetworkList "STARR Guest Wireless":[2437, 2412, 2462]   (repeated)
```

`wakeup_sources`: **WLAN = 85,969** wakeups (next non-charger source is
`mt-rtc` at 975). `suspend_stats`: `WLAN AHB ISR` is the #2 abort reason (34).
With Wi-Fi enabled, disconnected, and saved networks configured, Android runs
PNO background scans indefinitely. This is framework behavior — **a launcher
should not be toggling the user's Wi-Fi radio**, so this is not an app code
fix. It belongs to the `wifinudge/` feature's domain (nudge the user onto
Wi-Fi) or to provisioning policy, not to a code change in the drain path.

## Root cause 2 — cellular-only, no Wi-Fi (platform/usage, partly launcher-influenced)

`mobile_radio=231, wifi_radio=0, cell_high_tx=159`. The modem never gets to
idle on Wi-Fi; `ccmni_md` aborts (11) and `*telephony-radio*` wakelock
(9.5 s blamed, 248 acquisitions) confirm. The launcher contributes here only
to the extent its background network calls wake the modem (see Fix A).

## Launcher-fixable findings

### Fix A (highest-value launcher lever) — stop the hourly refresh from waking the *cellular* modem

`QuackLocationRefreshWorker` schedules with
`Constraints.setRequiredNetworkType(NetworkType.CONNECTED)`. On this device —
Wi-Fi disconnected — "connected" means **cellular**, so roughly every 2 h the
worker wakes the modem for a BeaconDB HTTPS lookup (the persisted-cache
short-circuit covers the in-between hour). Each wake is a modem ramp on a
device whose biggest problem is already the modem not sleeping.

- **File:** `app/.../quack/QuackLocationRefreshWorker.kt`, `schedule()`.
- **Change:** require **`NetworkType.UNMETERED`** instead of `CONNECTED`, so
  the hourly BeaconDB refresh only fires on Wi-Fi and never rings the cellular
  modem. (Foreground opens still take a live fix on cellular, and BeaconDB on
  cellular without an active Wi-Fi scan is coarse anyway.)
- **Trade-off:** location won't auto-refresh while traveling on cellular
  (the DC→NYC case the 1 h interval was added for). If that case matters,
  the lighter-touch alternative is to keep `CONNECTED` but lengthen the
  interval on metered networks (e.g. 1 h on Wi-Fi, 4–6 h on cellular) or gate
  on significant-motion so a stationary phone stops refreshing.
- **Expected impact:** removes ~12 cellular modem wakes/day. Small absolute
  %/hr but directly targets the modem-keepalive class that's keeping suspend
  from sticking.

### Fix B (latent foot-gun) — boot prewarm can spin GPS for 10 minutes

`DumbDownApp.onCreate()` runs the prewarm as:

```kotlin
QuackLocationHelper(this, noop, hardTimeoutMs = QuackLocationHelper.PREWARM_TIMEOUT_MS)
```

It **omits `allowGpsFallback`**, so it defaults to **true** — with a **10-minute**
timeout. The hourly worker deliberately passes `allowGpsFallback = false`
and the code's own comments call background GPS fallback "the single largest
idle-battery cost on this hardware." The prewarm is the HOME process, so it
re-runs on every reboot/process restart; if BeaconDB fails (Wi-Fi
disconnected — this device's normal state) it can pin the GPS chip for up to
10 minutes per start.

It did **not** fire this window (GPS wakelock totaled 2.35 s — `getCurrentLocation`
likely returned null fast, or BeaconDB succeeded on cellular), so this is a
risk to close, not a measured loss. But it's a one-line correctness gap.

- **File:** `app/.../DumbDownApp.kt` (prewarm block, ~line 228).
- **Change:** pass `allowGpsFallback = false` (and `activeWifiScan = false`)
  to the prewarm helper, matching `QuackLocationRefreshWorker`. The prewarm
  only needs to warm the BeaconDB/persisted cache; a cold-start GPS session
  is exactly what we don't want unattended in the background.
- **Expected impact:** eliminates worst-case 10-min background GPS sessions on
  Wi-Fi-disconnected boots. Protects against the Holly-style "GPS pinned for
  hours" regression the single-shot rewrite was meant to kill.

### Fix C (low priority) — 7 stacked launcher WorkManager jobs

`jobscheduler` shows **7** pending `u0a109` SystemJobService jobs. They run only
`DEVICE_NOT_DOZING` (good — they don't break Doze), so this is hygiene, not a
drain headline. Worth auditing that periodic workers (location refresh, beta
update reminder, etc.) aren't being re-enqueued redundantly on each
`onCreate`, but don't expect a measurable battery delta.

## Not a factor (ruled out)

- **Launcher Doze breaks:** 10 Doze exits, **0** attributable to launcher
  location wakes; **0** `quack_*` events emitted all window. The hourly worker
  is correctly waiting for maintenance windows.
- **Launcher alarms:** `QUACK_MONDAY` and `WIFI_NUDGE` are scheduled days out
  and logged **0 wakeups**. The 26 `alarmtimer` aborts are platform alarms, not
  these. (Auto-update 4 AM alarm also didn't fire in-window.)
- **Crashes / ANRs:** 0 crashes in the pull summary; logcat shows no launcher
  crash loop.
- **Pinned GPS:** GnssLocationProvider wakelock 2.35 s total — no sustained
  GPS engine session this window.

## Expected outcome + how to verify

Fixes A and B won't move the headline number much *while the user stays off
Wi-Fi* — the PNO scan + cellular modem dominate and are outside launcher code.
The honest framing: the launcher is close to its floor on this device; the big
lever is getting it onto Wi-Fi (the `wifinudge` feature's job) or not leaving
Wi-Fi enabled while perpetually out of range.

To verify the launcher-side fixes, re-pull after deploying and compare
`drain_summary.json`:

- **A:** `mobile_radio` event count and `ccmni_md` abort count should drop on a
  cellular-only idle night; confirm the hourly refresh no longer logs a
  BeaconDB fetch over cellular.
- **B:** force a Wi-Fi-off reboot and confirm GnssLocationProvider stays near
  zero and no 10-min `getCurrentLocation` session appears in logcat.
- **Reference:** a clean run should show suspend abort rate < 5 % and in-Doze
  median back toward single-digit mA — but expect that only once the device is
  on Wi-Fi or has its stale saved networks cleared.
