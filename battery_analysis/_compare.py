#!/usr/bin/env python3
"""
Per-device synthesis: break each samples.jsonl into discharge SESSIONS
(plug→unplug→plug edges), classify each session's state, and compute
real %/hour rates so we can compare phones honestly.

A "discharge session" is a contiguous window where charging=false.
For each session we report:
  - duration
  - battery drop
  - drain rate (%/h)
  - fraction of time screen-on / screen-off
  - lid_closed time
  - in_doze fraction
  - any session_end markers (service died inside the session)

Then we print a cross-phone leaderboard sorted by worst-overnight rate.
"""
import json, math, pathlib, sys
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).parent
NAMES = ["marco", "control", "danny", "liv"]

def load_jsonl(p):
    out = []
    if not p.exists(): return out
    with p.open() as f:
        for line in f:
            line = line.strip()
            if not line: continue
            try: out.append(json.loads(line))
            except Exception: pass
    return out

def parse_iso(s):
    return datetime.fromisoformat(s.replace("Z","+00:00"))

def split_sessions(samples):
    """A session = contiguous run where charging stays the same."""
    sessions = []
    cur = None
    for s in samples:
        ch = bool(s.get("charging"))
        if cur is None or cur["charging"] != ch:
            if cur and cur["samples"]:
                sessions.append(cur)
            cur = {"charging": ch, "samples": []}
        cur["samples"].append(s)
    if cur and cur["samples"]: sessions.append(cur)
    return sessions

def summarize_session(sess):
    s_first, s_last = sess["samples"][0], sess["samples"][-1]
    t0 = parse_iso(s_first["ts_iso"])
    t1 = parse_iso(s_last["ts_iso"])
    dur_h = (t1 - t0).total_seconds() / 3600
    lv0 = s_first.get("battery_level_pct") or 0
    lv1 = s_last.get("battery_level_pct") or 0
    drop = lv0 - lv1  # discharge sessions: positive
    rate = drop / dur_h if dur_h > 0 else None

    # Per-state breakdown by counting consecutive 60s buckets.
    on_min = off_active_min = off_lid_closed_min = doze_min = 0
    for a, b in zip(sess["samples"], sess["samples"][1:]):
        dt = (parse_iso(b["ts_iso"]) - parse_iso(a["ts_iso"])).total_seconds() / 60
        if dt > 5: continue  # gap (service was killed)
        state = a.get("screen_state")
        lid = a.get("lid_state")
        if state == "on":
            on_min += dt
        else:
            if a.get("in_doze"):
                doze_min += dt
            if lid == "closed":
                off_lid_closed_min += dt
            else:
                off_active_min += dt
    return dict(
        t0=s_first["ts_iso"], t1=s_last["ts_iso"],
        dur_h=round(dur_h, 2),
        lv0=lv0, lv1=lv1, drop_pct=drop,
        rate_pct_per_h=round(rate, 2) if rate is not None else None,
        screen_on_h=round(on_min/60, 2),
        screen_off_active_h=round(off_active_min/60, 2),
        screen_off_lid_closed_h=round(off_lid_closed_min/60, 2),
        in_doze_h=round(doze_min/60, 2),
        sample_count=len(sess["samples"]),
    )

def count_events(events_path):
    out = {"session_end": 0, "screen_on": 0, "screen_off": 0,
           "power_connected": 0, "power_disconnected": 0,
           "doze_changed": 0, "lid_opened": 0, "lid_closed": 0,
           "lid_bounce": 0}
    for e in load_jsonl(events_path):
        t = e.get("type")
        if t in out: out[t] += 1
    return out

ALL = {}
for name in NAMES:
    bundle = ROOT / name / "_bundle"
    samples = load_jsonl(bundle / "samples.jsonl")
    events = count_events(bundle / "events.jsonl")
    if not samples:
        print(f"!! {name}: no samples"); continue
    sessions = split_sessions(samples)
    discharge = [s for s in sessions if not s["charging"]]
    summarized = [summarize_session(s) for s in discharge]
    ALL[name] = dict(events=events, discharge=summarized,
                     sample_count=len(samples), session_count=len(sessions))

# ── Per-phone discharge sessions ────────────────────────────────
for name, d in ALL.items():
    print(f"\n══ {name.upper()} ═════════════════════════════════════════════")
    print(f"  total samples: {d['sample_count']}   "
          f"events: session_end={d['events']['session_end']} "
          f"power_disc={d['events']['power_disconnected']} "
          f"power_conn={d['events']['power_connected']} "
          f"lid_closed={d['events']['lid_closed']} "
          f"lid_bounce={d['events']['lid_bounce']}")
    print(f"  discharge sessions: {len(d['discharge'])}")
    # show top 5 longest discharge sessions, sorted by drain rate desc
    interesting = [s for s in d["discharge"] if s["dur_h"] >= 0.25]
    interesting.sort(key=lambda x: (x["rate_pct_per_h"] or 0), reverse=True)
    for i, s in enumerate(interesting[:6]):
        flag = "  ⚠" if (s["rate_pct_per_h"] or 0) >= 3 else "   "
        print(f"{flag} #{i+1} {s['t0'][:16]}→{s['t1'][11:16]}  "
              f"dur={s['dur_h']:>5}h  drop={s['drop_pct']:>3}% ({s['lv0']}→{s['lv1']})  "
              f"rate={(s['rate_pct_per_h'] or 0):>5}%/h  "
              f"on={s['screen_on_h']}h  off_lid={s['screen_off_lid_closed_h']}h  "
              f"off_active={s['screen_off_active_h']}h")

# ── Cross-phone leaderboard: worst overnight session ────────────
print("\n══ LEADERBOARD: worst overnight-style session (≥4h, drain>0) ══════")
rows = []
for name, d in ALL.items():
    overnight = [s for s in d["discharge"] if s["dur_h"] >= 4 and (s["drop_pct"] or 0) > 0]
    if not overnight:
        rows.append((name, "—", None, None, None, None))
        continue
    overnight.sort(key=lambda x: (x["rate_pct_per_h"] or 0), reverse=True)
    s = overnight[0]
    rows.append((name, s["t0"][:10], s["dur_h"], s["drop_pct"], s["rate_pct_per_h"],
                 s["screen_off_lid_closed_h"]))
rows.sort(key=lambda r: (r[4] or 0), reverse=True)
print(f"  {'tester':<10}{'date':<12}{'dur_h':>6}{'drop%':>7}{'%/h':>8}{'lid_closed_h':>15}")
for r in rows:
    print(f"  {r[0]:<10}{str(r[1]):<12}{str(r[2]):>6}{str(r[3]):>7}{str(r[4]):>8}{str(r[5]):>15}")
