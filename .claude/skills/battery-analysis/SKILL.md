---
name: battery-analysis
description: >-
  Analyze a pulled launcher battery-diagnostics bundle and write a root-caused
  drain report. Use when someone wants to investigate battery drain, idle
  power, standby life, Doze behavior, wakelocks, or suspend issues on a TCL
  Flip 2 (or similar) running the dumb-down-launcher diagnostics module, and a
  diagnostics tree has already been pulled (a diag_pulls/<serial>_<ts>/ dir, a
  launcher-diag/ folder, or a build-analysis-bundle.py _bundle/). Triggers:
  "analyze this battery pull", "why is this phone draining", "what's keeping it
  awake", "write a drain report". Does NOT capture from the device — pulling is
  done separately by battery_analysis/diag_pull.sh.
---

# Battery drain analysis

Turn an already-pulled diagnostics bundle into a root-caused finding. This is
the analyze-and-report half of the workflow in
`battery_analysis/README.md`. Capture (preflight/pull/probe) happens separately.

## Before you start

Read `battery_analysis/METHOD.md` — it defines the signals, the healthy
thresholds for this hardware, the abort-reason classification (app-influenced
vs. platform/kernel vs. benign), and the report template. Apply it; don't
re-derive it.

## Steps

1. **Locate the data.** Accept any of: a `diag_pulls/<serial>_<timestamp>/`
   pull dir, a `launcher-diag/` dir, or a `_bundle/` dir. If the user only
   built a `.tar.gz`, extract it first. Remember the layout gotcha: the pull
   writes `diag/` but `build-analysis-bundle.py` wants `launcher-diag/`.
   `drain_summary.py` handles both, so prefer it for triage.

2. **Run the triage script:**
   ```bash
   python3 battery_analysis/drain_summary.py <path-to-pull> --json
   ```
   This prints, in one pass: current draw by state, Doze breaks vs. launcher
   location wakes, kernel suspend success/abort breakdown, cellular-vs-Wi-Fi
   radio split, and the top wakeup sources. The `--json` flag also writes
   `drain_summary.json` next to the data.

3. **Confirm in the raw dumps.** Before asserting a root cause, verify it
   against the raw `dumpsys/` files (see METHOD.md "Going deeper"):
   - `grep ',wl,' dumpsys/batterystats-checkin-*.txt` for wakelock holders
   - `dumpsys/netstats-*.txt` for which uid is transmitting and over what transport
   - `wake_reason=` lines in `batterystats-history` for what woke it
   - `logcat-*.txt` + dropbox for crashes/ANRs (ignore framework WTF noise)

4. **Reason about magnitude and ownership.** Separate launcher-fixable causes
   from MediaTek/TCL platform behavior the app can't change. Rank causes by
   expected battery impact, and be explicit about uncertainty — instantaneous
   current sampling is a strong signal, not a controlled A/B test.

5. **Write the finding** to `battery_analysis/findings/<deviceid>-<date>.md`
   using the structure in METHOD.md: TL;DR → current-draw table → one section
   per root cause (with evidence) → "not a factor" → ranked fixes (with
   expected impact) → expected outcome + how to verify with a re-pull.

## Notes

- Read-only: never modify the pulled data or touch the device.
- `build-analysis-bundle.py`'s wakelock/sensor/alarm parsers are v1 stubs and
  emit empty files — get those numbers from the raw `dumpsys/` instead.
- If proposing launcher code changes, the location subsystem lives in
  `app/src/main/java/com/offlineinc/dumbdownlauncher/quack/`.
