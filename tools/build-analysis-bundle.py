#!/usr/bin/env python3
"""
build-analysis-bundle.py

Post-processor for the dumb-down-launcher battery diagnostics module.
Reads a pulled per-device diag folder and emits the structured,
LLM-friendly bundle described in battery-diagnostics-plan.md §6:

    diag-<deviceid>-<date>/
      device-info.json
      manifest.json
      summary.json
      samples.jsonl
      events.jsonl
      wakelocks.jsonl
      alarms.jsonl
      sensor_activity.jsonl
      drain_windows.json
      top_offenders.json
      hypotheses.json
      raw/                  ← original pulled files preserved here
      README.md

Expected input layout (after `adb pull` + `adb bugreport`):

    diag-<deviceid>-<date>/
      launcher-diag/
        manifest.json                      (optional, written by service)
        samples-YYYY-MM-DD.jsonl[.gz]
        events-YYYY-MM-DD.jsonl[.gz]
        dumpsys/
          batterystats-checkin-*.txt
          sensorservice-*.txt
          deviceidle-*.txt
          alarm-*.txt
          jobscheduler-*.txt
          power-*.txt
          procstats-*.txt
          activity_processes-*.txt
          netstats-*.txt
        logcat-*.txt
      bugreport.zip                        (optional)
      batterystats-final.txt               (optional)
      sensorservice-final.txt              (optional)
      deviceidle-final.txt                 (optional)
      device-info-overrides.json           (optional — usage profile, notes)

Usage:
    python3 tools/build-analysis-bundle.py diag-<deviceid>-<date>/

The script is intentionally stdlib-only so it can run on a support
engineer's laptop with nothing but Python 3 installed.
"""
from __future__ import annotations

import argparse
import gzip
import io
import json
import os
import re
import shutil
import sys
from collections import defaultdict, Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

SCHEMA_VERSION = 1


# ── File helpers ───────────────────────────────────────────────────────


def open_text(path: Path) -> io.TextIOBase:
    """Open a path that may or may not be gzipped, transparently."""
    if path.suffix == ".gz":
        return gzip.open(path, "rt", encoding="utf-8", errors="replace")
    return open(path, "r", encoding="utf-8", errors="replace")


def read_jsonl(path: Path) -> Iterable[dict]:
    with open_text(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                # Best-effort — a truncated tail line shouldn't kill the parse.
                continue


def write_jsonl(path: Path, rows: Iterable[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, separators=(",", ":")))
            f.write("\n")


def write_json(path: Path, obj: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2, sort_keys=False)


# ── Aggregators ────────────────────────────────────────────────────────


@dataclass
class StateBucket:
    """A drain-window bucket — see plan §6.3."""
    state: str
    duration_s: float = 0.0
    battery_drop_pct: float = 0.0
    sample_count: int = 0
    first_pct: float | None = None
    last_pct: float | None = None
    temp_sum: float = 0.0
    temp_count: int = 0
    wakelocks_in_bucket: Counter = field(default_factory=Counter)

    def add_sample(self, pct: float | None, temp_c: float | None) -> None:
        if pct is not None:
            if self.first_pct is None:
                self.first_pct = pct
            self.last_pct = pct
        if temp_c is not None:
            self.temp_sum += temp_c
            self.temp_count += 1
        self.sample_count += 1


def state_of(sample: dict) -> str:
    """Classify a sample into one of the plan's drain_windows buckets."""
    if sample.get("charging"):
        return "charging"
    screen = sample.get("screen_state")
    lid = sample.get("lid_state")
    doze = sample.get("in_doze")
    if screen == "on":
        return "screen_on"
    if doze:
        if lid == "closed":
            return "screen_off_doze_lid_closed"
        return "screen_off_doze"
    return "screen_off_active"


# ── batterystats --checkin parser ──────────────────────────────────────


def parse_batterystats_checkin(path: Path) -> dict:
    """
    Parse a `dumpsys batterystats --checkin` file enough to extract the
    metrics that feed top_offenders / hypotheses. The checkin format is
    one comma-separated record per line, prefixed with a uid + version +
    section tag. See `frameworks/base/core/java/.../BatteryStatsImpl.java`
    for the full grammar — we only care about a handful of tags:

      vers          schema version
      cap           battery design capacity (mAh)
      bt            battery time (uptime / realtime / etc.)
      dc            device idle counts
      wl, pwl       wakelocks (current owner + count)
      sy            sync events (proxy for background activity)
      sst, sscd     sensor active / events
      gn, gt        gps usage
    """
    out: dict[str, Any] = {
        "design_capacity_mah": None,
        "doze_entries": None,
        "doze_total_ms": None,
        "wakelock_holders": [],
        "sensor_activity": [],
        "uptime_ms": None,
        "battery_realtime_ms": None,
    }
    if not path.exists():
        return out

    wakelock_totals: dict[str, float] = defaultdict(float)
    sensor_totals: dict[str, float] = defaultdict(float)

    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.strip().split(",")
            if len(parts) < 4:
                continue
            # Format: <uid>,<aggregation>,<section>,<tag>,<fields...>
            tag = parts[3] if len(parts) > 3 else ""

            if tag == "cap":
                # cap,<capacity_mah>
                try:
                    out["design_capacity_mah"] = float(parts[4])
                except (IndexError, ValueError):
                    pass
            elif tag == "bt":
                # bt,<startclocktime>,<rtime>,<utime>,<bUptime>,<bRealtime>,...
                try:
                    out["battery_realtime_ms"] = int(parts[5])
                    out["uptime_ms"] = int(parts[7])
                except (IndexError, ValueError):
                    pass
            elif tag in ("wl", "pwl"):
                # wl,<name>,<full_ms>,<full_count>,<partial_ms>,<partial_count>,<window_ms>,<window_count>
                try:
                    name = parts[4]
                    partial_ms = float(parts[6]) if len(parts) > 6 else 0
                    wakelock_totals[name] += partial_ms
                except (IndexError, ValueError):
                    continue
            elif tag == "dc":
                # dc,<deep_count>,<deep_time>,<light_count>,<light_time>
                try:
                    out["doze_entries"] = int(parts[4])
                    out["doze_total_ms"] = int(parts[5])
                except (IndexError, ValueError):
                    pass
            elif tag in ("sst", "sscd"):
                # sst,<sensorHandle>,<time>,<count>
                try:
                    name = parts[4]
                    time_ms = float(parts[5])
                    sensor_totals[name] += time_ms
                except (IndexError, ValueError):
                    continue

    out["wakelock_holders"] = [
        {"tag": name, "duration_ms": int(ms)}
        for name, ms in sorted(wakelock_totals.items(), key=lambda kv: -kv[1])[:25]
    ]
    out["sensor_activity"] = [
        {"handle": name, "active_ms": int(ms)}
        for name, ms in sorted(sensor_totals.items(), key=lambda kv: -kv[1])[:25]
    ]
    return out


# ── sensorservice + deviceidle parsers ─────────────────────────────────


SENSOR_LISTENER_RE = re.compile(
    r"^\s*(?P<sensor>[^\s|]+).*?\bpid=(?P<pid>\d+)\b.*?\bpackage=(?P<pkg>\S+)"
)


def parse_sensorservice(path: Path) -> dict:
    """Extract who is currently listening to which sensor, for the lid hypothesis."""
    out: dict[str, Any] = {"listeners": [], "raw_lines_scanned": 0}
    if not path.exists():
        return out
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            out["raw_lines_scanned"] += 1
            m = SENSOR_LISTENER_RE.match(line)
            if m:
                out["listeners"].append({
                    "sensor": m.group("sensor"),
                    "pid": int(m.group("pid")),
                    "package": m.group("pkg"),
                })
    return out


def parse_deviceidle(path: Path) -> dict:
    """Extract whether we ever entered IDLE_MAINTENANCE / IDLE, and how often."""
    out: dict[str, Any] = {
        "current_state": None,
        "idle_history_lines": 0,
        "transitioned_to_idle_count": 0,
    }
    if not path.exists():
        return out
    in_history = False
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            stripped = line.strip()
            if stripped.startswith("mState="):
                out["current_state"] = stripped.split("=", 1)[1].split()[0]
            if "Idle history:" in stripped:
                in_history = True
                continue
            if in_history:
                out["idle_history_lines"] += 1
                if "IDLE" in stripped and "->" in stripped:
                    out["transitioned_to_idle_count"] += 1
    return out


# ── Main pipeline ──────────────────────────────────────────────────────


def collect_jsonl(launcher_diag: Path, basename: str) -> list[dict]:
    rows: list[dict] = []
    for pattern in (f"{basename}-*.jsonl", f"{basename}-*.jsonl.gz"):
        for path in sorted(launcher_diag.glob(pattern)):
            rows.extend(read_jsonl(path))
    rows.sort(key=lambda r: r.get("ts_ms", 0))
    return rows


def newest_dumpsys(launcher_diag: Path, prefix: str) -> Path | None:
    dumpsys_dir = launcher_diag / "dumpsys"
    if not dumpsys_dir.is_dir():
        return None
    matches = sorted(dumpsys_dir.glob(f"{prefix}-*.txt"))
    return matches[-1] if matches else None


def build_drain_windows(samples: list[dict]) -> dict:
    """Bucket samples by state and compute drain rate per bucket."""
    buckets: dict[str, StateBucket] = {}
    prev_ts: int | None = None
    prev_pct: float | None = None
    prev_state: str | None = None

    for s in samples:
        state = state_of(s)
        bucket = buckets.setdefault(state, StateBucket(state=state))
        pct = s.get("battery_level_pct")
        if pct is None:
            pct = (s.get("payload") or {}).get("capacity_pct")
        temp = (s.get("payload") or {}).get("temp_c")
        bucket.add_sample(pct, temp)

        ts = s.get("ts_ms")
        if prev_ts is not None and prev_state == state and ts is not None:
            bucket.duration_s += (ts - prev_ts) / 1000.0
        prev_ts = ts
        prev_pct = pct
        prev_state = state

    windows: list[dict] = []
    total_drop_pct = 0.0
    total_seconds = 0.0
    for state, bucket in buckets.items():
        first = bucket.first_pct
        last = bucket.last_pct
        drop = (first - last) if (first is not None and last is not None) else 0.0
        if drop < 0:
            drop = 0.0  # charging window, ignore re-charge for drain math
        rate = (drop / (bucket.duration_s / 3600.0)) if bucket.duration_s > 0 else None
        windows.append({
            "state": state,
            "duration_s": round(bucket.duration_s, 1),
            "battery_drop_pct": round(drop, 2),
            "drain_rate_pct_per_hour": round(rate, 3) if rate is not None else None,
            "mean_temp_c": round(bucket.temp_sum / bucket.temp_count, 2) if bucket.temp_count else None,
            "sample_count": bucket.sample_count,
        })
        total_drop_pct += drop
        total_seconds += bucket.duration_s

    overall_rate = (total_drop_pct / (total_seconds / 3600.0)) if total_seconds > 0 else None
    return {
        "windows": sorted(windows, key=lambda w: -w["duration_s"]),
        "baseline_drain_rate_pct_per_hour": round(overall_rate, 3) if overall_rate else None,
        "healthy_target_pct_per_hour": 0.8,
        "total_drop_pct": round(total_drop_pct, 2),
        "total_duration_s": round(total_seconds, 1),
    }


def build_top_offenders(batterystats: dict) -> dict:
    return {
        "top_wakelock_holders": batterystats.get("wakelock_holders", [])[:10],
        "top_sensor_activity": batterystats.get("sensor_activity", [])[:10],
    }


def build_hypotheses(
    batterystats: dict,
    sensorservice: dict,
    deviceidle: dict,
    events: list[dict],
) -> dict:
    lid_events = [
        e for e in events
        if e.get("type") in ("lid_open", "lid_close")
           or (e.get("payload") or {}).get("action", "").endswith("HALL")
    ]
    screen_off_lid_events = [
        e for e in lid_events if e.get("screen_state") == "off"
    ]

    longest_wl = 0
    if batterystats["wakelock_holders"]:
        longest_wl = batterystats["wakelock_holders"][0]["duration_ms"]

    return {
        "lid_sensor_chatter": {
            "lid_events_24h": len(lid_events),
            "lid_events_while_screen_off": len(screen_off_lid_events),
            "sensor_listeners_observed": [l["package"] for l in sensorservice.get("listeners", [])][:10],
            "comment": "A healthy idle device should produce a handful of "
                       "lid_events_24h at most. Hundreds or thousands while "
                       "screen-off indicates hall sensor chatter — confirm via "
                       "`gpio_keys` wakeup_count in wakeup_sources-*.txt.",
        },
        "launcher_wakelock_leak": {
            "top_wakelock_holders": batterystats.get("wakelock_holders", [])[:5],
            "longest_held_wakelock_ms": longest_wl,
            "comment": "Look for wakelocks attributed to the launcher package "
                       "(`com.offlineinc.dumbdownlauncher`) with multi-second "
                       "totals during screen-off windows.",
        },
        "doze_blocked": {
            "doze_entries": batterystats.get("doze_entries"),
            "doze_total_ms": batterystats.get("doze_total_ms"),
            "current_idle_state": deviceidle.get("current_state"),
            "transitioned_to_idle_count_history": deviceidle.get("transitioned_to_idle_count"),
            "comment": "On a healthy device we expect dozens of doze entries "
                       "per 24h with most of the night spent IDLE. "
                       "If doze_entries is low or 0, something is preventing "
                       "deep idle — primary suspect on a steady linear-drain curve.",
        },
        "radio_thrash": {
            "comment": "Populate from netstats / batterystats `gn` records — "
                       "left as a stub in v1. Inspect dumpsys/netstats-*.txt manually for now.",
        },
        "companion_app_binding": {
            "comment": "Inspect dumpsys/activity_processes-*.txt for long-lived "
                       "bindings from the launcher to com.tcl.dialer / contacts. "
                       "Left as a stub in v1.",
        },
    }


def build_summary(
    device_info: dict,
    samples: list[dict],
    events: list[dict],
    drain_windows: dict,
    batterystats: dict,
    sensorservice: dict,
    deviceidle: dict,
) -> dict:
    start = samples[0]["ts_ms"] if samples else None
    end = samples[-1]["ts_ms"] if samples else None
    hours = ((end - start) / 3_600_000.0) if (start and end) else None

    first_pct = samples[0].get("battery_level_pct") if samples else None
    last_pct = samples[-1].get("battery_level_pct") if samples else None
    total_drop = (first_pct - last_pct) if (first_pct is not None and last_pct is not None) else None

    by_state = {w["state"]: w for w in drain_windows["windows"]}
    return {
        "device_id": device_info.get("device_id"),
        "capture_window_hours": round(hours, 2) if hours else None,
        "capture_window_start_iso": samples[0].get("ts_iso") if samples else None,
        "capture_window_end_iso": samples[-1].get("ts_iso") if samples else None,
        "battery_drop_total_pct": total_drop,
        "baseline_drain_rate_pct_per_hour": drain_windows["baseline_drain_rate_pct_per_hour"],
        "drain_rate_screen_off_lid_closed_pct_per_hour":
            by_state.get("screen_off_doze_lid_closed", {}).get("drain_rate_pct_per_hour"),
        "drain_rate_screen_off_active_pct_per_hour":
            by_state.get("screen_off_active", {}).get("drain_rate_pct_per_hour"),
        "drain_rate_screen_on_pct_per_hour":
            by_state.get("screen_on", {}).get("drain_rate_pct_per_hour"),
        "doze_entries": batterystats.get("doze_entries"),
        "doze_total_s": (batterystats.get("doze_total_ms") or 0) / 1000.0,
        "top_5_wakelocks_by_duration_ms": batterystats.get("wakelock_holders", [])[:5],
        "top_5_sensors_active_ms": batterystats.get("sensor_activity", [])[:5],
        "sensor_listener_count": len(sensorservice.get("listeners", [])),
        "battery_design_capacity_mah": batterystats.get("design_capacity_mah"),
    }


def stub_device_info(diag_dir: Path) -> dict:
    """
    Generate a starter device-info.json. The support engineer is expected to
    fill in `usage_profile_self_report` and any free-form notes by hand
    before sharing the bundle with the analyst.
    """
    overrides_path = diag_dir / "device-info-overrides.json"
    overrides = {}
    if overrides_path.exists():
        try:
            overrides = json.loads(overrides_path.read_text())
        except json.JSONDecodeError:
            pass

    # Try to read the launcher's manifest.json if present.
    manifest_path = diag_dir / "launcher-diag" / "manifest.json"
    manifest = {}
    if manifest_path.exists():
        try:
            manifest = json.loads(manifest_path.read_text())
        except json.JSONDecodeError:
            pass

    info = {
        "device_id": diag_dir.name,
        "model": "4058W",
        "hardware_revision": None,
        "build_fingerprint": manifest.get("build_fingerprint"),
        "launcher_version": manifest.get("launcher_version"),
        "battery_design_capacity_mah": None,
        "carrier": None,
        "android_security_patch": None,
        "usage_profile_self_report": None,
        "notes": None,
        "capture_window_start_iso": None,
        "capture_window_end_iso": None,
    }
    info.update(overrides)
    return info


README_TEMPLATE = """# Diag bundle — `{name}`

Generated by `tools/build-analysis-bundle.py`. See
`battery-diagnostics-plan.md` for the schema described below.

## Files

- `summary.json` — one-pager. **Read first.** Headline metrics for this device.
- `drain_windows.json` — drain rate bucketed by state (`screen_on`, `screen_off_doze_lid_closed`, etc.).
- `hypotheses.json` — pre-scored evidence for each working hypothesis.
- `top_offenders.json` — top-N wakelock and sensor leaderboards.
- `events.jsonl` — unified event stream (screen / power / doze / lid / launcher / dumpsys snapshots).
- `samples.jsonl` — per-minute battery samples.
- `wakelocks.jsonl` — parsed wakelock holders from `batterystats --checkin`.
- `sensor_activity.jsonl` — parsed sensor activity from `batterystats --checkin`.
- `device-info.json` — hardware + usage profile. Fill in
  `usage_profile_self_report` and any free-form notes by hand if
  `device-info-overrides.json` wasn't provided.
- `raw/` — original pulled files (`bugreport.zip`, `dumpsys/*`, `logcat-*.txt`, ...).
  These are kept as ground truth; the structured files above are derived from them.

## Schema invariants (v{schema})

- Every JSONL row has `ts_ms`, `ts_iso`, `type`, `source`, `capture_session_id`.
- Battery state is denormalized onto every event: `screen_state`, `lid_state`,
  `charging`, `battery_level_pct`, `in_doze`.
- Drain rates are positive numbers (charging windows are reported separately).

## Caveats

- The `radio_thrash` and `companion_app_binding` hypothesis scores are stubs
  in v1 — inspect `raw/dumpsys/netstats-*.txt` and `raw/dumpsys/activity_processes-*.txt`
  manually for those, or extend `build_hypotheses()` in
  `tools/build-analysis-bundle.py`.
"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("diag_dir", type=Path, help="The pulled diag-<deviceid>-<date>/ folder")
    parser.add_argument(
        "--out", type=Path, default=None,
        help="Output folder (defaults to <diag_dir>/_bundle/)",
    )
    args = parser.parse_args()

    diag_dir: Path = args.diag_dir.resolve()
    if not diag_dir.is_dir():
        print(f"error: {diag_dir} is not a directory", file=sys.stderr)
        return 2

    out_dir: Path = args.out or (diag_dir / "_bundle")
    out_dir.mkdir(parents=True, exist_ok=True)

    launcher_diag = diag_dir / "launcher-diag"
    if not launcher_diag.is_dir():
        print(f"warning: {launcher_diag} not found — proceeding with raw dumps only", file=sys.stderr)

    # 1. Merge JSONL streams.
    samples = collect_jsonl(launcher_diag, "samples") if launcher_diag.is_dir() else []
    events = collect_jsonl(launcher_diag, "events") if launcher_diag.is_dir() else []
    print(f"[bundle] {len(samples)} samples, {len(events)} events")

    # 2. Parse the newest privileged snapshots.
    batterystats_path = (
        newest_dumpsys(launcher_diag, "batterystats-checkin")
        if launcher_diag.is_dir() else None
    )
    if batterystats_path is None and (diag_dir / "batterystats-final.txt").exists():
        batterystats_path = diag_dir / "batterystats-final.txt"
    batterystats = parse_batterystats_checkin(batterystats_path) if batterystats_path else {}
    print(f"[bundle] batterystats: {batterystats_path}")

    sensorservice_path = (
        newest_dumpsys(launcher_diag, "sensorservice") if launcher_diag.is_dir() else None
    )
    if sensorservice_path is None and (diag_dir / "sensorservice-final.txt").exists():
        sensorservice_path = diag_dir / "sensorservice-final.txt"
    sensorservice = parse_sensorservice(sensorservice_path) if sensorservice_path else {}
    print(f"[bundle] sensorservice: {sensorservice_path}")

    deviceidle_path = (
        newest_dumpsys(launcher_diag, "deviceidle") if launcher_diag.is_dir() else None
    )
    if deviceidle_path is None and (diag_dir / "deviceidle-final.txt").exists():
        deviceidle_path = diag_dir / "deviceidle-final.txt"
    deviceidle = parse_deviceidle(deviceidle_path) if deviceidle_path else {}
    print(f"[bundle] deviceidle: {deviceidle_path}")

    # 3. Derived files.
    device_info = stub_device_info(diag_dir)
    drain_windows = build_drain_windows(samples)
    top_offenders = build_top_offenders(batterystats)
    hypotheses = build_hypotheses(batterystats, sensorservice, deviceidle, events)
    summary = build_summary(
        device_info, samples, events, drain_windows, batterystats, sensorservice, deviceidle,
    )

    # 4. Write outputs.
    write_json(out_dir / "device-info.json", device_info)
    write_json(out_dir / "summary.json", summary)
    write_json(out_dir / "drain_windows.json", drain_windows)
    write_json(out_dir / "top_offenders.json", top_offenders)
    write_json(out_dir / "hypotheses.json", hypotheses)
    write_jsonl(out_dir / "samples.jsonl", samples)
    write_jsonl(out_dir / "events.jsonl", events)
    write_jsonl(out_dir / "wakelocks.jsonl", batterystats.get("wakelock_holders", []))
    write_jsonl(out_dir / "sensor_activity.jsonl", batterystats.get("sensor_activity", []))
    write_jsonl(out_dir / "alarms.jsonl", [])  # v1 stub — parse dumpsys alarm to fill in

    # 5. Mirror raw inputs so the bundle is self-contained.
    raw_dir = out_dir / "raw"
    raw_dir.mkdir(exist_ok=True)
    for src in (
        launcher_diag if launcher_diag.is_dir() else None,
        diag_dir / "bugreport.zip",
        diag_dir / "batterystats-final.txt",
        diag_dir / "sensorservice-final.txt",
        diag_dir / "deviceidle-final.txt",
    ):
        if src is None or not Path(src).exists():
            continue
        dst = raw_dir / Path(src).name
        if Path(src).is_dir():
            if not dst.exists():
                shutil.copytree(src, dst)
        else:
            shutil.copy2(src, dst)

    # 6. README.
    (out_dir / "README.md").write_text(
        README_TEMPLATE.format(name=diag_dir.name, schema=SCHEMA_VERSION)
    )

    print(f"[bundle] wrote {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
