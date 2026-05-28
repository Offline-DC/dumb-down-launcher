#!/usr/bin/env bash
#
# diag_drain_probe.sh
#
# Run-on-demand follow-up probes for the live device. Built specifically
# for the case where battery_analysis/_compare.py has flagged a phone
# (currently Marco's) as drawing too much idle current.
#
# Captures the dumpsys outputs that tell us *which subsystem* is keeping
# the phone awake right now — location requests, recent wakeup reasons,
# scheduled jobs, network bytes-per-uid, and crash/ANR history.
#
# Everything is written into ./drain_probes/<serial>_<utc-ts>/ as raw
# .txt files for later grep'ing, and the script also prints a short
# inline summary so you can read the smoking gun without opening any
# files.
#
# Usage:
#   ./scripts/diag_drain_probe.sh                  # default suspect set
#   ./scripts/diag_drain_probe.sh --serial XYZ     # pick a specific device
#   ./scripts/diag_drain_probe.sh --out ~/Desktop  # custom destination
#   ./scripts/diag_drain_probe.sh --extra-pkg com.foo,com.bar
#                                                  # add packages to the
#                                                  # jobscheduler + netstats
#                                                  # probes (default set:
#                                                  # launcher + openbubbles)
#   ./scripts/diag_drain_probe.sh --no-history     # skip the slow history
#                                                  # dump (saves ~5s)
#   ./scripts/diag_drain_probe.sh -h
#
# Output layout:
#   drain_probes/<serial>_<utc-ts>/
#     00_probe_meta.json
#     01_location_requests.txt
#     02_batterystats_history.txt
#     03_jobscheduler_<pkg>.txt
#     04_netstats_detail.txt
#     05_dropbox_recent.txt
#     06_power_wakelocks.txt
#     07_deviceidle_state.txt
#     08_top_processes.txt

set -u

PKG="com.offlineinc.dumbdownlauncher"
DEFAULT_SUSPECTS=("$PKG" "com.openbubbles.messaging")

# ── arg parsing ────────────────────────────────────────────────────────

OUT_ROOT="./drain_probes"
SERIAL_ARG=""
EXTRA_PKGS=""
SKIP_HISTORY=0

while [ $# -gt 0 ]; do
    case "$1" in
        --serial)     SERIAL_ARG="$2"; shift 2 ;;
        --out)        OUT_ROOT="$2";  shift 2 ;;
        --extra-pkg)  EXTRA_PKGS="$2"; shift 2 ;;
        --no-history) SKIP_HISTORY=1;  shift ;;
        -h|--help)
            sed -n '3,32p' "$0"
            exit 0
            ;;
        *)
            echo "Unknown arg: $1" >&2
            exit 2
            ;;
    esac
done

# ── ANSI ───────────────────────────────────────────────────────────────

if [ -t 1 ]; then
    G=$'\033[0;32m'; R=$'\033[0;31m'; Y=$'\033[0;33m'
    B=$'\033[1m'; D=$'\033[0;90m'; X=$'\033[0m'
else
    G=""; R=""; Y=""; B=""; D=""; X=""
fi

# ── helpers ────────────────────────────────────────────────────────────

ADB() {
    if [ -n "$SERIAL_ARG" ]; then
        adb -s "$SERIAL_ARG" "$@"
    else
        adb "$@"
    fi
}
adb_sh() { ADB shell "$@" 2>/dev/null | tr -d '\r'; }

# Compose the suspect-package list.
SUSPECTS=("${DEFAULT_SUSPECTS[@]}")
if [ -n "$EXTRA_PKGS" ]; then
    IFS=',' read -ra EXTRA <<< "$EXTRA_PKGS"
    SUSPECTS+=("${EXTRA[@]}")
fi

# ── pre-flight ─────────────────────────────────────────────────────────

if ! command -v adb >/dev/null; then
    echo "${R}adb not on PATH — install Android platform-tools${X}" >&2
    exit 1
fi

SERIAL=$(ADB get-serialno 2>/dev/null | tr -d '\r')
if [ -z "$SERIAL" ] || [ "$SERIAL" = "unknown" ]; then
    if [ -z "$SERIAL_ARG" ]; then
        echo "${R}no single device connected — pass --serial <id>${X}" >&2
        echo
        adb devices -l >&2
        exit 1
    fi
    SERIAL="$SERIAL_ARG"
fi

MODEL=$(adb_sh "getprop ro.product.model")
ANDROID_VER=$(adb_sh "getprop ro.build.version.release")

UTC_TS=$(date -u +%Y-%m-%dT%H-%M-%SZ)
SAFE_SERIAL=$(printf '%s' "$SERIAL" | tr -c 'A-Za-z0-9._-' '_')
OUT="$OUT_ROOT/${SAFE_SERIAL}_${UTC_TS}"
mkdir -p "$OUT" || { echo "${R}cannot create $OUT${X}" >&2; exit 1; }

cat <<EOF
${B}── drain probe ──${X}
  device:    $SERIAL  ($MODEL, Android $ANDROID_VER)
  suspects:  ${SUSPECTS[*]}
  out:       $OUT

EOF

# Probe metadata (so we can attribute the dump later).
cat > "$OUT/00_probe_meta.json" <<EOF
{
  "schema": 1,
  "serial": "$SERIAL",
  "model": "$MODEL",
  "android_release": "$ANDROID_VER",
  "package_under_test": "$PKG",
  "suspects": [$(printf '"%s",' "${SUSPECTS[@]}" | sed 's/,$//')],
  "captured_at_utc": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF

# ── 1. who's holding location? ─────────────────────────────────────────

echo "${B}1. location requests${X}"
adb_sh "dumpsys location" > "$OUT/01_location_requests.txt"

# The "Active Requests" block lists every app currently subscribed to
# location updates. On TCL Flip 2 (Android 11) the section header is
# usually "Active Requests:" — but on some MediaTek builds it's
# "Records by Provider:" instead. Capture both.
echo "  ${D}from dumpsys location → Active Requests:${X}"
awk '/^[ \t]*Active Requests/,/^[ \t]*[A-Z][^:]*:$/' "$OUT/01_location_requests.txt" \
    | sed '/^[ \t]*[A-Z][^:]*:$/d' | sed 's/^/    /' | head -30

# Fallback / extra: list location providers and their current state.
ACTIVE_REQ_LINES=$(grep -cE "Active Requests|^\\s*Request" "$OUT/01_location_requests.txt")
if [ "$ACTIVE_REQ_LINES" -lt 2 ]; then
    echo "  ${D}(no Active Requests section found — falling back to provider state)${X}"
    grep -E "Provider:|^\\s*request.*pkg=|listener=" "$OUT/01_location_requests.txt" \
        | head -15 | sed 's/^/    /'
fi
echo

# ── 2. recent wakeup reasons ───────────────────────────────────────────

if [ "$SKIP_HISTORY" -eq 0 ]; then
    echo "${B}2. battery history (last 200 lines)${X}"
    adb_sh "dumpsys batterystats --history" > "$OUT/02_batterystats_history.txt"
    HIST_LINES=$(wc -l < "$OUT/02_batterystats_history.txt" | awk '{print $1+0}')
    echo "  ${D}captured $HIST_LINES history lines → $OUT/02_batterystats_history.txt${X}"
    # Surface wakeup-cause lines specifically.
    echo "  ${D}wakeup-reason lines (most recent 15):${X}"
    grep -E "wake_reason|wr=|wakeup" "$OUT/02_batterystats_history.txt" \
        | tail -15 | sed 's/^/    /'
    echo "  ${D}power-state transitions (most recent 10):${X}"
    grep -E "screen=|power_state|charging=|deep_idle|light_idle" "$OUT/02_batterystats_history.txt" \
        | tail -10 | sed 's/^/    /'
else
    echo "${B}2. battery history${X}  ${D}(skipped: --no-history)${X}"
fi
echo

# ── 3. jobs scheduled by each suspect ──────────────────────────────────

echo "${B}3. jobscheduler per-package state${X}"
for s in "${SUSPECTS[@]}"; do
    safe=$(printf '%s' "$s" | tr -c 'A-Za-z0-9._-' '_')
    out="$OUT/03_jobscheduler_${safe}.txt"
    # `cmd jobscheduler get-jobstate <pkg> <job>` requires a numeric job
    # id, which we don't have ahead of time. The simpler dump that
    # actually enumerates all jobs by package is `dumpsys jobscheduler`
    # filtered down — we capture the relevant block per suspect.
    adb_sh "dumpsys jobscheduler" \
        | awk -v pkg="$s" '
            /^[ \t]*JOB [0-9]+:/      {block = $0 "\n"; in_block=1; next}
            in_block && /^[ \t]*JOB /   {if (block ~ pkg) print block; block = $0 "\n"; next}
            in_block                  {block = block $0 "\n"}
            END                       {if (block ~ pkg) print block}
        ' > "$out"
    n=$(grep -c "^[ \t]*JOB " "$out" 2>/dev/null | awk '{print $1+0}')
    if [ "$n" -gt 0 ]; then
        echo "  ${Y}$s${X}: ${B}$n${X} job(s) scheduled  ${D}→ $out${X}"
        # Show the first 3 jobs in summary form.
        grep -E "^[ \t]*JOB |^[ \t]*Tag:|^[ \t]*Network type:|Periodic.*|periodic=|Run time:|Earliest run time:" "$out" \
            | head -15 | sed 's/^/    /'
    else
        echo "  $s: ${G}no jobs${X}"
    fi
done
echo

# ── 4. netstats per-uid (radio time + bytes) ───────────────────────────

echo "${B}4. netstats per-uid (suspect packages)${X}"
adb_sh "dumpsys netstats detail" > "$OUT/04_netstats_detail.txt"
for s in "${SUSPECTS[@]}"; do
    uid=$(adb_sh "dumpsys package $s" | awk -F= '/^[ \t]*userId/ {gsub(/ /,"",$2); print $2; exit}')
    if [ -z "$uid" ]; then
        echo "  $s: ${D}uid not found${X}"
        continue
    fi
    # Each per-uid block starts with "uid=<n>" or "UID <n>" depending on
    # OEM build. Surface both forms; show a 6-line head for context.
    echo "  ${Y}$s${X} (uid=$uid):"
    awk -v u="uid=$uid" -v U="UID $uid" '
        /uid=[0-9]+/ || /^UID [0-9]+/ { in_block = ($0 ~ u || $0 ~ U) ? 1 : 0 }
        in_block { print }
    ' "$OUT/04_netstats_detail.txt" | head -8 | sed 's/^/    /'
done
echo

# ── 5. system crash / ANR history ──────────────────────────────────────

echo "${B}5. dropbox: recent crashes / ANRs / native crashes${X}"
adb_sh "dumpsys dropbox --print" > "$OUT/05_dropbox_recent.txt"

# dropbox dumps are long; filter to the entry headers + their first 4
# context lines so we can see what crashed without scrolling.
CRASH_LINES=$(grep -cE "_crash|_anr|_lowmem|_kernel_panic" "$OUT/05_dropbox_recent.txt")
if [ "$CRASH_LINES" -gt 0 ]; then
    echo "  ${R}$CRASH_LINES dropbox entries matching crash/ANR/lowmem/panic${X}"
    grep -E "_crash|_anr|_lowmem|_kernel_panic" "$OUT/05_dropbox_recent.txt" \
        | tail -10 | sed 's/^/    /'
else
    echo "  ${G}no crashes / ANRs in dropbox${X}"
fi
echo

# ── 6. wakelocks held *right now* ──────────────────────────────────────

echo "${B}6. wakelocks currently held${X}"
adb_sh "dumpsys power" > "$OUT/06_power_wakelocks.txt"

# The currently-held PARTIAL wakelocks block is what blocks doze. List
# them and try to attribute by owner uid where possible.
WAKELOCK_LINES=$(awk '/Wake Locks:/,/^[A-Z][a-z]/' "$OUT/06_power_wakelocks.txt" \
                | grep -cE "PARTIAL|FULL|SCREEN")
if [ "$WAKELOCK_LINES" -gt 0 ]; then
    echo "  ${Y}$WAKELOCK_LINES active wakelock line(s)${X}"
    awk '/Wake Locks:/,/^[A-Z][a-z]/' "$OUT/06_power_wakelocks.txt" \
        | grep -E "PARTIAL|FULL|SCREEN" \
        | head -15 | sed 's/^/    /'
else
    echo "  ${G}no PARTIAL/FULL/SCREEN wakelocks held${X}"
fi
echo

# ── 7. why doze isn't entering ─────────────────────────────────────────

echo "${B}7. deviceidle state${X}"
adb_sh "dumpsys deviceidle" > "$OUT/07_deviceidle_state.txt"

# Two key lines from this dumpsys:
#   mState=ACTIVE / IDLE_PENDING / SENSING / LOCATING / IDLE / IDLE_MAINT
#   mLightState=ACTIVE / INACTIVE / PRE_IDLE / IDLE / WAITING_FOR_NETWORK
# If mState stays ACTIVE forever, doze isn't entering.
DEEP_STATE=$(grep -m1 "^  mState=" "$OUT/07_deviceidle_state.txt" | awk -F= '{print $2}')
LIGHT_STATE=$(grep -m1 "^  mLightState=" "$OUT/07_deviceidle_state.txt" | awk -F= '{print $2}')
LAST_DOZE=$(grep -m1 "Last deep idle exit" "$OUT/07_deviceidle_state.txt" \
            | sed 's/.*=//')
SINCE_DOZE=$(grep -m1 "Since boot" "$OUT/07_deviceidle_state.txt")

echo "  mState         = ${DEEP_STATE:-?}"
echo "  mLightState    = ${LIGHT_STATE:-?}"
[ -n "$LAST_DOZE" ] && echo "  last deep idle exit: $LAST_DOZE"
case "$DEEP_STATE" in
    IDLE|IDLE_MAINT)  echo "  ${G}deep doze is engaged — drain should be minimal${X}" ;;
    LOCATING|SENSING) echo "  ${Y}phone is on the way to doze (sensing)${X}" ;;
    ACTIVE)           echo "  ${R}phone is fully ACTIVE — not entering doze right now${X}" ;;
    *)                echo "  ${D}deep state '$DEEP_STATE' — interpretation unclear${X}" ;;
esac
echo

# ── 8. live process snapshot ───────────────────────────────────────────

echo "${B}8. top processes (oom + cpu)${X}"
{
    echo "── adb shell top -n 1 -m 15 -b ──"
    adb_sh "top -n 1 -m 15 -b 2>/dev/null"
    echo
    echo "── adb shell dumpsys activity oom ──"
    adb_sh "dumpsys activity oom"
} > "$OUT/08_top_processes.txt"

# Surface the launcher's oom-adj + cpu line for the report.
echo "  ${D}top CPU consumers right now:${X}"
adb_sh "top -n 1 -m 8 -b -o PID,USER,%CPU,%MEM,RES,ARGS 2>/dev/null" \
    | tail -8 | sed 's/^/    /'
echo
echo "  ${D}launcher's oom state (should be FGS/foreground for the diag service):${X}"
grep -B1 -A2 "$PKG" "$OUT/08_top_processes.txt" \
    | grep -E "adj=|oom_adj|procState=|$PKG" \
    | head -6 | sed 's/^/    /'

# ── done ───────────────────────────────────────────────────────────────

cat <<EOF

${B}── done ──${X}
  raw dumps:    $OUT
  next:
    less $OUT/01_location_requests.txt
    less $OUT/02_batterystats_history.txt
    less $OUT/07_deviceidle_state.txt

  rerun later with --extra-pkg com.foo,com.bar to add packages to the
  jobscheduler + netstats probes once you have a suspect.
EOF
