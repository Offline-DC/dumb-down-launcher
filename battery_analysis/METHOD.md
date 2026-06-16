# Battery Drain Analysis — Method

How to turn a pulled diagnostics bundle into a root-caused drain report. This
is the repeatable playbook behind every file in `findings/`. The mechanical
parts are automated by [`drain_summary.py`](drain_summary.py); this document
explains what the numbers mean and how to reason from them.

For how the data is *captured and produced*, see
[`battery-diagnostics-plan.md`](battery-diagnostics-plan.md). This file is only
about *analysis*.

---

## Inputs

A pulled diagnostics tree (from `battery_analysis/diag_pull.sh`) contains:

| File | What it gives you |
|---|---|
| `samples-*.jsonl` | Periodic battery samples: `current_now_ua` (instantaneous draw), `capacity_pct`, `temp_c`, `voltage_mv`, plus `screen_state` / `lid_state` / `charging` / `in_doze` tags. |
| `events-*.jsonl` | State changes: screen on/off, lid, `doze_changed`, and launcher events like `quack_location_result` / `quack_netloc_scan`. |
| `dumpsys/suspend_stats-*.txt` | Kernel suspend success/fail counters. |
| `dumpsys/batterystats-history-*.txt` | Per-event timeline incl. `wake_reason`, radio on/off, wifi_scan. |
| `dumpsys/wakeup_sources-*.txt` | Wakeup counts per kernel wakeup source. |
| `dumpsys/{netstats,power,deviceidle,...}` | Per-uid bytes, wakelocks, idle state. |

Optionally run `tools/build-analysis-bundle.py` first to get parsed
`samples.jsonl` / `drain_windows.json` / `summary.json`. Note its v1 wakelock /
sensor / alarm parsers are **stubs** — for those, read the raw `dumpsys/` files.

> **Gotcha:** `diag_pull.sh` writes a `diag/` subfolder, but
> `build-analysis-bundle.py` expects `launcher-diag/`. Either rename
> (`mv diag launcher-diag`) or point the analyzer at a dir whose child is
> `launcher-diag/`. `drain_summary.py` handles both layouts automatically.

---

## Healthy reference (TCL Flip 2 / MediaTek)

| Metric | Healthy | Concern |
|---|---|---|
| Idle drain (screen-off) | ≤ 0.8 %/hr | > 1.5 %/hr |
| Deep-suspend current floor | ~4–10 mA | > 20 mA |
| In-Doze median current | single digits | tens of mA = something blocking suspend |
| Suspend abort rate | < 1 % | > 5 % |
| Standby life | 100 h+ | < 60 h |

---

## The five signals (what `drain_summary.py` prints)

### 1. Current draw by state
Bucket `current_now_ua` by `screen_state` + `in_doze`. The key comparison is
**in-Doze median vs. the suspend floor**. If the device hits ~4 mA at its best
but the in-Doze *median* is 40–80 mA, something is repeatedly holding it off
the floor. Also compare `screen_off_in_doze` vs `screen_off_not_doze`: a large
gap means Doze is working *when it's allowed to*, so the question becomes "what
keeps pulling it out of Doze?"

### 2. Doze timeline + launcher wakes
Line up `doze_changed` (idle true/false) against launcher location events. An
`is_device_idle_mode:false` transition that coincides (within ~90 s) with a
`quack_*` event is a launcher-caused Doze break. Each break **restarts
Android's Doze backoff**, so frequent breaks prevent the deepest, lowest-power
idle stages. `fell_back_to_gps:true` events are the expensive ones — a
background GPS fix costs ~5 s of GPS chip time per fire.

### 3. Kernel suspend
`fail / (success+fail)` is the abort rate. Then group `Abort:` reasons from the
history. Read them in three classes:
- **App-influenced:** `ccmni_md*` (cellular modem — network keepalives),
  `WLAN AHB ISR` (Wi-Fi chip — scans / connectivity).
- **Platform/kernel (not app-fixable):** `bat_percent_notify_lock`,
  `dlpt_notify_lock`, `PTIM_wakelock`, `mt635x-auxadc`, `alarmtimer`. These are
  MediaTek power-management locks that fire on their own timers.
- **Benign:** occasional `mt-rtc`, `eventpoll`.

Attribute drain to the app only for the first class.

### 4. Radio on-time
`mobile_radio` vs `wifi_radio` event counts reveal which transport the device
used. **A device idle on cellular drains far more than on Wi-Fi**, and keeps
`ccmni_md*` from suspending. If `mobile_radio` ≫ `wifi_radio` overnight, the
biggest lever is usually "get it onto Wi-Fi / reduce cellular keepalives," not
anything Wi-Fi-specific. Cross-check with `dumpsys/netstats` per-uid bytes to
see *which app* is transmitting.

### 5. Top wakeup sources
Sanity check. Charger/usb/ac counts are noise from being plugged in. Focus on
the largest non-charger source and confirm it lines up with the abort
breakdown.

---

## Going deeper (raw dumps)

When the five signals point at a culprit, confirm in the raw files:
- **Which app holds wakelocks:** `grep ',wl,' dumpsys/batterystats-checkin-*.txt`
  (look for `*telephony-radio*`, `GnssLocationProvider`, package-attributed `*job*`).
- **Which uid transmits:** `dumpsys/netstats-*.txt` per-uid `rb`/`tb` per bucket.
- **What woke it:** `wake_reason=` lines in `batterystats-history`.
- **Crashes/ANRs:** `logcat-*.txt` + `dumpsys dropbox` (note: framework
  `system_server_wtf` / `systemui` noise is usually not battery-relevant).

---

## Writing the finding

Save to `findings/<deviceid-or-tester>-<date>.md`. Structure that has worked:

1. **TL;DR** — the one or two root causes, ranked, with the headline drain number.
2. **Current-draw table** — the state breakdown, naming the floor vs. the median.
3. **One section per root cause** — each with the *evidence* (the actual
   counters/log lines), not just the conclusion.
4. **Not a factor** — explicitly clear suspects you ruled out (crashes, leaked
   wakelocks, benign ANRs). This is as valuable as the positive findings.
5. **Ranked fixes** — concrete, with expected impact; separate app-fixable from
   platform/kernel behavior the app can't change.
6. **Expected outcome + how to verify** — the before/after metrics to re-pull and check.

Be honest about magnitude and uncertainty: instantaneous-current sampling is a
strong signal but not a controlled A/B. When the dominant blocker is outside
the change you're proposing (e.g. cellular modem vs. a location fix), say so.

See [`findings/drain-report-8PUSB6PV59EEIBDE-2026-06-16.md`](findings/drain-report-8PUSB6PV59EEIBDE-2026-06-16.md)
for a worked example.
