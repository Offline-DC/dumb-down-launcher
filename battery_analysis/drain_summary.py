#!/usr/bin/env python3
"""
drain_summary.py — fast triage of a pulled launcher battery-diagnostics tree.

This is the "method as code" companion to METHOD.md. It reproduces, in one
pass, the cross-referencing an analyst does by hand when figuring out what is
draining a TCL Flip 2 (or similar) running the launcher diagnostics module:

  1. Current draw (mA) bucketed by device state          ← samples-*.jsonl
  2. Doze timeline + which launcher events break Doze     ← events-*.jsonl
  3. Kernel suspend success/abort breakdown               ← dumpsys/suspend_stats + batterystats-history
  4. Radio split: cellular vs Wi-Fi on-time + scans       ← dumpsys/batterystats-history
  5. Top wakeup sources                                    ← dumpsys/wakeup_sources

It is intentionally stdlib-only so it runs anywhere Python 3 does, and it is
read-only — it never touches the device or mutates the pull.

Usage:
    python3 battery_analysis/drain_summary.py <path>

<path> may be any of:
    - a pull dir from scripts/diag_pull.sh  (contains diag/ or launcher-diag/)
    - a staged analysis dir                 (contains launcher-diag/)
    - a launcher-diag/ dir itself
    - a build-analysis-bundle.py _bundle/   (uses samples.jsonl/events.jsonl + raw/)

Add --json to also emit a machine-readable summary to stdout-adjacent file
<path>/drain_summary.json.

Healthy reference for this hardware (see METHOD.md):
    idle drain target      ≤ 0.8 %/hr
    deep-suspend floor     ~4–10 mA
    suspend abort rate     < 1 %
"""
from __future__ import annotations

import glob
import gzip
import json
import os
import re
import statistics as st
import sys
from collections import Counter

# ── Thresholds (keep in sync with METHOD.md) ───────────────────────────────
IDLE_TARGET_PCT_PER_HR = 0.8
SUSPEND_FLOOR_MA = 10.0
WAKE_WINDOW_MS = 90_000  # how close a sample must be to a launcher wake to count


def _open(path):
    return gzip.open(path, "rt") if path.endswith(".gz") else open(path)


def _load_jsonl(paths):
    rows = []
    for p in sorted(paths):
        try:
            with _open(p) as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        rows.append(json.loads(line))
                    except json.JSONDecodeError:
                        pass
        except OSError:
            pass
    return rows


def locate(root):
    """Return (samples, events, dumpsys_dir) resolving the various pull layouts."""
    root = os.path.abspath(root)
    candidates = [
        root,
        os.path.join(root, "launcher-diag"),
        os.path.join(root, "diag"),
        os.path.join(root, "raw", "launcher-diag"),
        os.path.join(root, "raw"),
    ]
    samples = events = None
    dumpsys = None
    for base in candidates:
        if not os.path.isdir(base):
            continue
        s = glob.glob(os.path.join(base, "samples-*.jsonl*"))
        e = glob.glob(os.path.join(base, "events-*.jsonl*"))
        if s and samples is None:
            samples = s
        if e and events is None:
            events = e
        d = os.path.join(base, "dumpsys")
        if os.path.isdir(d) and dumpsys is None:
            dumpsys = d
    # _bundle layout: flat samples.jsonl / events.jsonl
    if samples is None:
        s = glob.glob(os.path.join(root, "samples.jsonl"))
        if s:
            samples = s
    if events is None:
        e = glob.glob(os.path.join(root, "events.jsonl"))
        if e:
            events = e
    return samples or [], events or [], dumpsys


def newest(dumpsys_dir, prefix):
    if not dumpsys_dir:
        return None
    m = sorted(glob.glob(os.path.join(dumpsys_dir, f"{prefix}-*.txt")))
    return m[-1] if m else None


def ma(sample):
    c = sample.get("payload", {}).get("current_now_ua")
    return abs(c) / 1000.0 if c is not None else None


# ── 1. current by state ─────────────────────────────────────────────────────
def current_by_state(samples):
    disch = [s for s in samples if s.get("payload", {}).get("status") == "discharging"]
    out = {}

    def seg(name, filt):
        vals = [ma(s) for s in disch if filt(s) and ma(s) is not None]
        if vals:
            out[name] = {
                "n": len(vals),
                "mean_ma": round(st.mean(vals), 1),
                "median_ma": round(st.median(vals), 1),
                "max_ma": round(max(vals), 1),
                "min_ma": round(min(vals), 1),
            }

    seg("screen_on", lambda s: s.get("screen_state") == "on")
    seg("screen_off_in_doze", lambda s: s.get("screen_state") == "off" and s.get("in_doze"))
    seg("screen_off_not_doze", lambda s: s.get("screen_state") == "off" and not s.get("in_doze"))
    return out, disch


# ── 2. doze breaks + launcher wakes ─────────────────────────────────────────
LAUNCHER_WAKE_TYPES = {
    "quack_location_result", "quack_netloc_scan",
}


def doze_and_wakes(events):
    doze = [e for e in events if e.get("type") == "doze_changed"]
    wakes = [e for e in events if e.get("type") in LAUNCHER_WAKE_TYPES]
    transitions = []
    for e in doze:
        transitions.append({
            "ts": e.get("ts_iso"),
            "idle": e.get("payload", {}).get("is_device_idle_mode"),
            "screen": e.get("screen_state"),
            "lid": e.get("lid_state"),
        })
    gps_fallbacks = [
        e for e in wakes
        if e.get("payload", {}).get("fell_back_to_gps") is True
    ]
    # exits from idle (idle True -> False) attributable to a launcher wake within WAKE_WINDOW_MS
    exits = [e for e in doze if e.get("payload", {}).get("is_device_idle_mode") is False]
    wake_ms = [w.get("ts_ms") for w in wakes if w.get("ts_ms")]
    attributed = 0
    for ex in exits:
        t = ex.get("ts_ms")
        if t and any(abs(t - w) <= WAKE_WINDOW_MS for w in wake_ms):
            attributed += 1
    return {
        "doze_transitions": transitions,
        "doze_exit_count": len(exits),
        "doze_exits_near_launcher_wake": attributed,
        "launcher_location_wakes": len(wakes),
        "gps_fallback_wakes": len(gps_fallbacks),
    }


# ── 3. suspend stats + abort grouping ───────────────────────────────────────
def suspend_summary(dumpsys_dir):
    out = {}
    sp = newest(dumpsys_dir, "suspend_stats")
    if sp:
        kv = {}
        for line in open(sp):
            m = re.search(r"/([a-z_]+):(\d+)", line.strip())
            if m:
                kv[m.group(1)] = int(m.group(2))
        success = kv.get("success", 0)
        fail = kv.get("fail", 0)
        total = success + fail
        out["success"] = success
        out["fail"] = fail
        out["fail_pct"] = round(100 * fail / total, 1) if total else None
        out["last_failed_dev"] = None  # filled below if present
        for line in open(sp):
            if "last_failed_dev" in line:
                out["last_failed_dev"] = line.split(":", 1)[-1].strip()
    hist = newest(dumpsys_dir, "batterystats-history")
    if hist:
        aborts = Counter()
        for line in open(hist, errors="ignore"):
            m = re.search(r'Abort:([^"]*)', line)
            if m:
                key = re.sub(r"\d+", "", m.group(1)).strip()
                aborts[key] += 1
        out["top_aborts"] = aborts.most_common(8)
    return out


# ── 4. radio split ──────────────────────────────────────────────────────────
def radio_summary(dumpsys_dir):
    hist = newest(dumpsys_dir, "batterystats-history")
    if not hist:
        return {}
    txt = open(hist, errors="ignore").read()
    return {
        "mobile_radio_on_events": txt.count("+mobile_radio"),
        "wifi_radio_on_events": txt.count("+wifi_radio"),
        "wifi_scan_events": txt.count("+wifi_scan"),
        "cellular_high_tx_power_events": txt.count("+cellular_high_tx_power"),
    }


# ── 5. wakeup sources ───────────────────────────────────────────────────────
def wakeup_sources(dumpsys_dir):
    wsf = newest(dumpsys_dir, "wakeup_sources")
    if not wsf:
        return []
    rows = []
    for i, line in enumerate(open(wsf, errors="ignore")):
        if i == 0:
            continue
        parts = line.split()
        if len(parts) >= 4:
            name = parts[0]
            try:
                wakeup_count = int(parts[3])
            except ValueError:
                continue
            rows.append((wakeup_count, name))
    rows.sort(reverse=True)
    return rows[:10]


def fmt_table(by_state):
    order = ["screen_on", "screen_off_not_doze", "screen_off_in_doze"]
    lines = ["  state                  n   mean    median   max"]
    for k in order:
        v = by_state.get(k)
        if v:
            lines.append(f"  {k:20s} {v['n']:>3}  {v['mean_ma']:>6}  {v['median_ma']:>6}  {v['max_ma']:>6}  mA")
    return "\n".join(lines)


def main(argv):
    if len(argv) < 2 or argv[1] in ("-h", "--help"):
        print(__doc__)
        return 0
    root = argv[1]
    want_json = "--json" in argv[2:]
    if not os.path.exists(root):
        print(f"error: {root} does not exist", file=sys.stderr)
        return 2

    samples_paths, events_paths, dumpsys_dir = locate(root)
    samples = _load_jsonl(samples_paths)
    events = _load_jsonl(events_paths)

    if not samples and not events and not dumpsys_dir:
        print(f"error: no diagnostics data found under {root}", file=sys.stderr)
        print("       expected samples-*.jsonl / events-*.jsonl / dumpsys/ — check the path.", file=sys.stderr)
        return 2

    by_state, disch = current_by_state(samples)
    dz = doze_and_wakes(events)
    susp = suspend_summary(dumpsys_dir)
    radio = radio_summary(dumpsys_dir)
    wsrc = wakeup_sources(dumpsys_dir)

    B = "\033[1m" if sys.stdout.isatty() else ""
    X = "\033[0m" if sys.stdout.isatty() else ""

    print(f"\n{B}── drain summary: {os.path.basename(os.path.abspath(root))} ──{X}")
    print(f"  samples: {len(samples)}   events: {len(events)}   dumpsys: {'yes' if dumpsys_dir else 'no'}")

    print(f"\n{B}1. current draw by state (discharging only){X}")
    if by_state:
        print(fmt_table(by_state))
        floor = by_state.get("screen_off_in_doze", {}).get("median_ma")
        if floor is not None:
            verdict = "OK" if floor <= SUSPEND_FLOOR_MA else f"HIGH (floor target ≤{SUSPEND_FLOOR_MA} mA)"
            print(f"  → in-Doze median {floor} mA  [{verdict}]")
    else:
        print("  (no battery samples)")

    print(f"\n{B}2. Doze / launcher wakes{X}")
    print(f"  doze exits: {dz['doze_exit_count']}   "
          f"attributable to launcher location wakes: {dz['doze_exits_near_launcher_wake']}")
    print(f"  launcher location wakes: {dz['launcher_location_wakes']}   "
          f"of which GPS fallback: {dz['gps_fallback_wakes']}")
    if dz["gps_fallback_wakes"]:
        print(f"  ⚠ {dz['gps_fallback_wakes']} background GPS fallback(s) — each breaks Doze + spins the GPS chip")

    print(f"\n{B}3. kernel suspend{X}")
    if susp:
        print(f"  success={susp.get('success')}  fail={susp.get('fail')}  "
              f"fail_rate={susp.get('fail_pct')}%   last_failed_dev={susp.get('last_failed_dev')}")
        for reason, n in susp.get("top_aborts", []):
            print(f"    {n:>5}  Abort: {reason}")
    else:
        print("  (no suspend_stats / history)")

    print(f"\n{B}4. radio on-time (event counts){X}")
    if radio:
        print(f"  mobile_radio={radio['mobile_radio_on_events']}   "
              f"wifi_radio={radio['wifi_radio_on_events']}   "
              f"wifi_scan={radio['wifi_scan_events']}   "
              f"cell_high_tx={radio['cellular_high_tx_power_events']}")
        if radio["mobile_radio_on_events"] > 5 * max(radio["wifi_radio_on_events"], 1):
            print("  → running mostly on CELLULAR — modem keepalives likely a top suspend-blocker")
    else:
        print("  (no history)")

    print(f"\n{B}5. top wakeup sources (by wakeup_count){X}")
    for cnt, name in wsrc:
        print(f"    {cnt:>6}  {name}")

    print()

    if want_json:
        out = {
            "path": os.path.abspath(root),
            "counts": {"samples": len(samples), "events": len(events)},
            "current_by_state": by_state,
            "doze": {k: v for k, v in dz.items() if k != "doze_transitions"},
            "suspend": susp,
            "radio": radio,
            "top_wakeup_sources": wsrc,
        }
        outp = os.path.join(os.path.abspath(root), "drain_summary.json")
        with open(outp, "w") as f:
            json.dump(out, f, indent=2)
        print(f"wrote {outp}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
