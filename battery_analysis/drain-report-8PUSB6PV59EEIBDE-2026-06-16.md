# Battery Drain Analysis — 8PUSB6PV59EEIBDE (TCL 4058R / Flip 2, Android 11)

**Capture window:** 2026-06-15 21:03Z → 2026-06-16 13:19Z (16.3 h)
**Total battery drop:** 12% over the discharging portion
**Measured baseline idle drain:** ~2.57 %/hr (target for this hardware: ≤0.8 %/hr)
**Source data:** `diag_pulls/8PUSB6PV59EEIBDE_2026-06-16T13-19-45Z/` + `drain_probes/8PUSB6PV59EEIBDE_2026-06-16T13-20-13Z/`

---

## TL;DR

The phone is drawing **~3x more idle current than it should**. Two independent, compounding problems, both fixable in the launcher:

1. **The device almost never reaches deep suspend.** Even when Android Doze is active, the WLAN chip generates wakeups that abort kernel suspend (1,581 failed suspends in the window). Idle current sits at a ~47 mA *floor* instead of the ~4–10 mA this hardware reaches in true suspend.
2. **The launcher's "quack" location job repeatedly breaks Doze** — roughly every 90 minutes overnight — and because its network-location scan returns *zero* Wi-Fi APs and cells, it **always falls back to a ~5-second GPS fix**. Each wake doubles current draw and resets the Doze backoff timer, so the phone never earns the deepest idle stages.

Net effect: standby life is ~38 h. Fixing both should push it past ~150 h.

---

## How current draw breaks down

Measured directly from `current_now_ua` in the battery samples (avg battery voltage 3.99 V):

| State | Samples | Mean draw | Median draw |
|---|---|---|---|
| Screen ON | 15 | 305 mA | 246 mA |
| Screen OFF, **not** in Doze | 66 | **152 mA** | 126 mA |
| Screen OFF, in Doze | 145 | 77 mA | 47 mA |
| Deep idle floor (best overnight samples) | — | — | **~4 mA** |

The two rows that matter: **in Doze the median is 47 mA, but the floor is ~4 mA.** Something is holding the device off its suspend floor even during Doze. And **leaving Doze doubles draw** (47 → 126 mA median), which is what the launcher's location wakes do.

---

## Root cause #1 — WLAN is blocking kernel suspend (the 47 mA floor)

`dumpsys suspend_stats` over the window:

```
success:           9039
fail:              1581   ← ~15% of suspend attempts fail
failed_freeze:      954
failed_suspend:     427
last_failed_dev:    alarmtimer
last_failed_errno:  -16   (EBUSY)
last_failed_step:   freeze
```

`wakeup_sources` and the batterystats history agree on the culprit — **WLAN is the top wakeup source by a wide margin** (13,758 wakeups; next-largest non-charger source is ~1,988), and the history is full of:

```
wake_reason="Abort:Pending Wakeup Sources: WLAN AHB ISR "
```

Critically, **the phone is not even associated to Wi-Fi overnight** — every overnight `netstats` bucket for the launcher is on MOBILE (cellular), and the location scans report `wifi_ap_count: 0`. So the WLAN chip is powered and generating interrupts (background scans), preventing suspend, while delivering no connectivity. Worst of both worlds: Wi-Fi radio cost with cellular carrying the traffic (which also keeps `*telephony-radio*` awake — 737 wakelock acquisitions).

**Why this is the launcher's problem to solve:** on a nightstand flip phone, background Wi-Fi scanning while disconnected has no user value overnight. If the launcher (or its setup) leaves Wi-Fi scan-always-on enabled and the device roams out of any known network, you get exactly this signature.

---

## Root cause #2 — the location job breaks Doze and forces GPS

The Doze timeline (`events.jsonl`, `doze_changed`) interleaved with the launcher's location events tells the whole story:

```
02:39:22  ENTER doze
03:34:53  EXIT doze   ← 03:34:53 quack_location_result (persisted_cache)
03:35:23  ENTER doze
05:07:06  EXIT doze   ← 05:07:06 quack_netloc_scan (0 wifi, 0 cell) → 05:07:11 GPS fix (5.07s)
05:07:36  ENTER doze
08:40:37  EXIT doze   ← 08:40:37 quack_netloc_scan (0 wifi, 0 cell) → 08:40:42 GPS fix (5.06s)
08:41:07  ENTER doze
```

Every `quack_netloc_scan` returns `outcome: insufficient_signals, wifi_ap_count: 0, cell_count: 0`, so every one sets `fell_back_to_gps: true` and runs GPS for ~5 seconds. Over the window, `GnssLocationProvider` was acquired **1,111 times**.

Two costs here:
- **Direct:** GPS is the most expensive location source; ~5 s of GPS plus radio activity per wake.
- **Indirect (bigger):** each exit from idle **restarts Android's Doze backoff schedule**. With wakes every ~90 min, the device keeps re-climbing the Doze ladder and never reaches the longest, lowest-power maintenance intervals. This is a major reason the in-Doze median is 47 mA rather than single digits.

Note the contrast: the `persisted_cache` location results cost **0–6 ms**. When the cache is used, it's free. The damage is entirely in the GPS-fallback path.

---

## Not a factor

- **No crashes.** Zero `AndroidRuntime`/FATAL lines for the launcher in logcat.
- **The dropbox ANR/WTF entries are framework noise** — `system_server`/`systemui` `NOTIFICATION_CHANGED` non-protected-broadcast WTFs from TCL's build, unrelated to the launcher and not battery-relevant.
- **No leaked launcher wakelock.** The launcher's only wakelock is its WorkManager `SystemJobService` job (6.8 s total, 5 acquisitions) — normal. The drain is from *what the job does* (GPS + breaking Doze), not a held lock.

---

## Recommended fixes (ranked by expected impact)

### 1. Stop forcing GPS on overnight/stationary location updates  — *biggest, easiest win*
In the quack location path, when `quack_netloc_scan` returns `insufficient_signals`, **do not fall back to GPS while the screen is off**. Options, in order of preference:
- Serve the **last persisted cache** value (already 0 ms cost) and skip the fix entirely when the device is stationary / screen-off.
- Only allow GPS fallback on **explicit user/foreground requests**, never from the background periodic job.
- If a background fix is truly required, gate it behind a significant-motion check so a phone sitting on a nightstand never triggers it.

### 2. Make the periodic location job Doze-friendly
- Schedule it with **WorkManager periodic work / inexact alarms** and let it run *inside* Doze maintenance windows rather than waking the device. Never use `setExactAndAllowWhileIdle` for routine location refresh.
- **Back off hard when screen-off + stationary** (e.g. 90 min → 4–6 h, or pause until next unlock). The user can't see location while the lid is closed anyway.

### 3. Tame Wi-Fi while disconnected overnight
- Ensure the launcher isn't leaving **Wi-Fi scan-always-on** active when disconnected, and consider deferring Wi-Fi/network work while in Doze. The goal is to eliminate the `WLAN AHB ISR` suspend aborts so the kernel can actually freeze.
- Validate with a follow-up capture that `suspend_stats/fail` drops toward zero and the in-Doze median current falls from ~47 mA toward the ~4–10 mA floor.

### 4. Prefer Wi-Fi over cellular for background polling
- Overnight polling ran on **cellular** (~300 KB / 2 h) while a known Wi-Fi network existed. If the launcher controls this polling, defer it to when Wi-Fi is associated, or batch it, to avoid keeping `*telephony-radio*` awake.

---

## Expected outcome

| Metric | Now | After fixes (target) |
|---|---|---|
| In-Doze median current | 47 mA | ~5–10 mA |
| Idle drain | 2.57 %/hr | ≤0.8 %/hr |
| `suspend_stats` fail rate | ~15% | <1% |
| Standby life | ~38 h | ~150+ h |

## How to verify after a fix

1. Reflash, leave the phone idle overnight (lid closed, off charger).
2. `./scripts/diag_preflight.sh` before bed to confirm the service survives Doze.
3. Pull again: `./scripts/diag_pull.sh --logcat --bundle`, then rebuild the bundle.
4. Check: `suspend_stats/fail` near zero, `doze_changed` shows long uninterrupted idle stretches, **no** `quack_location_result` with `fell_back_to_gps: true` while screen-off, and in-Doze median current in the single digits.
