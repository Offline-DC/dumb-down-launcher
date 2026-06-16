# Battery Analysis

Battery life is a core feature of these phones. This directory is the home for
everything about diagnosing and improving launcher-related battery drain:
the **method**, the **tooling**, and the accumulated **findings**.

## Layout

```
battery_analysis/
  README.md                      ← you are here: the hub
  METHOD.md                      ← how to analyze a bundle + interpret the signals
  battery-diagnostics-plan.md    ← design of the on-device instrumentation + capture
  drain_summary.py               ← one-pass triage of a pulled bundle (method as code)
  analyze.sh                     ← batch-run the analyzer over diag_pulls/*.tar.gz
  _compare.py                    ← cross-device leaderboard (worst overnight drain)
  findings/                      ← one report per investigation
  baddevice/                     ← sample/reference bundle
  # ── capture tooling (operates on a connected device over adb) ──
  diag_preflight.sh              ← before-bed: will the OS leave the service alone overnight?
  diag_pull.sh                   ← pull the diagnostics tree off the device
  diag_drain_probe.sh            ← on-demand live probes (location, wakelocks, netstats, doze)
  diag_watcher.sh                ← continuous health check while plugged in
  diag_reducesar_check.sh        ← SAR-reduction state check (not battery-specific)
  diag_reducesar_reset.sh        ← SAR-reduction reset (not battery-specific)
```

The one piece still outside this dir is the bundle builder, which lives with
the other repo tooling:

```
tools/build-analysis-bundle.py ← turn a pull into a structured LLM-friendly bundle
```

Run the scripts from the repo root (their output paths — `diag_pulls/`,
`drain_probes/` — are relative to wherever you invoke them, and those dirs are
gitignored).

## End-to-end workflow

```bash
# 1. (optional) confirm the capture will survive the night
./battery_analysis/diag_preflight.sh

# 2. pull logs + battery data off the device
./battery_analysis/diag_pull.sh --logcat --bundle

# 3. (optional) capture live drain probes while the phone is idle/unplugged
./battery_analysis/diag_drain_probe.sh

# 4. fast triage — current-by-state, Doze breaks, suspend aborts, radio split
python3 battery_analysis/drain_summary.py diag_pulls/<serial>_<timestamp>

# 5. (optional) full structured bundle
#    NOTE: rename diag/ -> launcher-diag/ first (see METHOD.md gotcha)
python3 tools/build-analysis-bundle.py diag_pulls/<serial>_<timestamp>

# 6. write the finding -> findings/<deviceid>-<date>.md  (template in METHOD.md)
```

For the analysis reasoning itself — what the numbers mean and how to root-cause
from them — read [METHOD.md](METHOD.md).

## Findings

Each investigation gets a dated report in [`findings/`](findings/). Newest first:

- [`drain-report-8PUSB6PV59EEIBDE-2026-06-16.md`](findings/drain-report-8PUSB6PV59EEIBDE-2026-06-16.md)
  — TCL Flip 2: hourly quack location job forcing GPS + breaking Doze; cellular
  modem keepalives as the dominant suspend-blocker.
- [`HOLLY_BATTERY_FINDINGS.md`](findings/HOLLY_BATTERY_FINDINGS.md) — earlier investigation.

## Claude skill

`.claude/skills/battery-analysis/` packages the analyze-and-report half of this
workflow so it can be run consistently. It assumes a bundle has already been
pulled, runs `drain_summary.py`, applies METHOD.md, and writes a finding.
