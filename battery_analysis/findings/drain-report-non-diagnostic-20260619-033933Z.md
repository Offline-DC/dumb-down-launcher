# Battery Drain Analysis (excluding the diagnostics overhead) — capture `20260619-033933Z`

**Device:** TCL 4058W, Android 11, launcher **v5.8.0**
**Window:** 2026-06-18 21:09 → 2026-06-19 03:37 UTC (samples span ~26.7 h), 424 battery samples
**Observed drain:** 63% → 7% over 26.7 h ≈ **2.1%/h → ~48 h projected runtime**

This device is healthier than the previous capture, but it still **never reaches true deep sleep**: ~77 mA median with the screen off, ~71 mA even "in doze" (a device that actually suspends sits around 5–30 mA). So the question is what holds it awake — and on this device it is **not the launcher** (only 32 mAh attributed) and not primarily the GPS path. It's the **Wi-Fi radio plus the system/carrier location & IMS stack.**

## What's actually draining it (ranked, diagnostics set aside)

### 1. Wi-Fi never settles — the single biggest sleep-killer
The kernel wakeup sources are dominated by Wi-Fi:

- **`WLAN AHB ISR` held a wakelock for ~8.3% of wall-clock — extrapolates to ~2 h/day of pure Wi-Fi-interrupt wakelock** (grew 7.77M→9.71M ms over the 6.5 h of dumpsys snapshots).
- **`WLAN TX THREAD` woke 311,565 times**; `mt635x-auxadc` (MTK ADC) 217,772 times.

The driver of those scans is a **system Wi-Fi location provider**: `com.skyhookwireless.provider` (uid 1000) fired **307 alarm-wakeups** in the window — the top wakeup source on the device, each one a Wi-Fi scan to compute location. This is what keeps the WLAN chip and SoC awake.

### 2. The device is failing to suspend ~28% of the time
`suspend_stats`: **13,761 failed suspends out of 49,921 attempts (28%)**, almost all `failed_freeze` (11,546). `last_failed_dev = alarmtimer`, `errno -16 (EBUSY)`, step `freeze`. Translation: alarms are scheduled so densely that the kernel can't freeze processes to enter suspend, so it aborts and stays in a higher-power state. The dense alarm cadence comes from the same system/carrier services (Skyhook, `networkstack`, carrier entitlement, Polaris) — see below.

### 3. Carrier / IMS / location services (all system-side, not the launcher)
By batterystats' own `pwi` estimate (mAh), excluding diagnostics:

| Attribution | mAh | What it is |
|---|---|---|
| uid 1000 `system_server` | 564 | hosts Skyhook Wi-Fi location + alarm/Wi-Fi management |
| uid 1001 `com.mediatek.gba` | 92.7 | MediaTek IMS/Wi-Fi-calling bootstrapping auth |
| uid 10099 `com.openbubbles.messaging` | 77.9 | iMessage bridge persistent connection |
| uid 10032 `systemui` | 58.8 | normal |
| **uid 10094 `dumbdownlauncher`** | **32.3** | **the launcher itself — minor** |
| uid 10014 `com.polariswireless.zclient` | (holds GPS ~5 min + RTC alarms) | carrier E911 location |
| uid 10016 `com.tct.gcs.wfcmanager` | 7.7 | TCL Wi-Fi-calling manager |

`com.polariswireless.zclient` is the only thing holding the GPS sensor (~321 s), and `com.mediatek.gba` + `com.tct.entitlement` + `wfcmanager` are the Wi-Fi-calling entitlement/IMS stack polling in the background.

### The launcher is largely exonerated on this device
- Launcher attribution is **32 mAh** — 17× less than `system_server`, below `systemui`.
- Its only real wakelock is the WorkManager periodic jobs (~29 s of partial wakelock total).
- Launcher logcat shows **zero** `QuackLocation`/`BeaconDB`/`getCurrentLocation` activity in the window, and zero `skyhook`/`polaris` references — the location thrash is **system/carrier-initiated, not quack.**

(For reference, the diagnostics still cost 414 mAh under uid 0 / root — the 2nd-largest line — which is exactly why the lighter-diagnostics branch matters, but it's set aside here per the ask.)

## What can actually be done about it
These are system/carrier packages baked into the ROM, but this is a custom-launcher/ROM product that already disables bloat and self-grants perms — so the lever is **provisioning, not app code**:

1. **Disable/stop the Skyhook Wi-Fi location provider** (`com.skyhookwireless.provider`) — it's the #1 wakeup source and the engine behind the Wi-Fi scan thrash. quack/weather use BeaconDB directly and don't need the system network-location provider. This alone should cut the WLAN-ISR wakelock and a large chunk of the suspend-freeze failures.
2. **Disable the unused carrier Wi-Fi-calling / location stack** if VoWiFi isn't a shipped feature: `com.mediatek.gba`, `com.tct.entitlement`, `com.tct.gcs.wfcmanager`, `com.polariswireless.zclient`. Together that's ~100+ mAh plus their alarm/GPS wakeups (and Polaris's GPS hold).
3. **Re-check after disabling**: the suspend `failed_freeze` rate should drop sharply once the dense alarm sources (Skyhook 307, entitlement, networkstack) are gone — that's the path back to a real <30 mA deep-sleep floor.
4. OpenBubbles (77.9 mAh) is a legitimate messaging-bridge cost; worth a separate look at its keepalive/ping cadence, but it's expected for a live iMessage connection.

Net: on this user's phone the battery is being eaten by **system Wi-Fi location scanning + the carrier IMS/location services preventing suspend**, not by the launcher. The fix is disabling those ROM packages in provisioning, not launcher code changes.
