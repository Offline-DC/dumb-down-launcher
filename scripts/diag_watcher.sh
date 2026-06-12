#!/usr/bin/env bash
#
# diag_watcher.sh
#
# Continuous health check for the battery diagnostics module. Polls the
# connected device every N seconds and prints a one-line status report;
# alerts loudly if anything drifts. Designed to run for as long as you
# leave the phone plugged in on a desk.
#
# Usage:
#   ./scripts/diag_watcher.sh                     # poll every 60s, run forever
#   ./scripts/diag_watcher.sh --interval 30       # poll every 30s
#   ./scripts/diag_watcher.sh --duration 1h       # stop after 1 hour
#   ./scripts/diag_watcher.sh --duration 24h      # overnight watch
#   ./scripts/diag_watcher.sh --interval 30 --duration 2h
#   ./scripts/diag_watcher.sh -h
#
# What it checks each poll:
#   - device still connected
#   - phone still plugged in (and reports current charge state)
#   - DiagnosticsService still running and foreground
#   - samples file is still growing (cadence is healthy)
#   - events file is still growing or stable (events are bursty)
#   - dumpsys snapshot directory has a recent (≤70 min old) file
#   - no new AndroidRuntime crashes for the package since watcher start
#
# Exit on Ctrl-C prints a final summary: duration, counts of new
# samples/events/snapshots, alerts triggered, observed sample cadence.

set -u

PKG="com.offlineinc.dumbdownlauncher"
SERVICE="${PKG}/.diagnostics.DiagnosticsService"
MIRROR_DIR="/sdcard/Android/data/${PKG}/files/diag"

# ── arg parsing ────────────────────────────────────────────────────────

INTERVAL=60
DURATION_S=0   # 0 = forever
QUIET=0

while [ $# -gt 0 ]; do
    case "$1" in
        --interval)  INTERVAL="$2"; shift 2 ;;
        --duration)
            raw="$2"; shift 2
            # parse 30s / 5m / 2h / 90 (seconds)
            case "$raw" in
                *s) DURATION_S=$(( ${raw%s} )) ;;
                *m) DURATION_S=$(( ${raw%m} * 60 )) ;;
                *h) DURATION_S=$(( ${raw%h} * 3600 )) ;;
                *)  DURATION_S=$(( raw )) ;;
            esac
            ;;
        --quiet) QUIET=1; shift ;;
        -h|--help)
            sed -n '3,28p' "$0"
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
    G=$'\033[0;32m'; R=$'\033[0;31m'; Y=$'\033[0;33m'; B=$'\033[1m'; D=$'\033[0;90m'; X=$'\033[0m'
else
    G=""; R=""; Y=""; B=""; D=""; X=""
fi

# ── helpers ────────────────────────────────────────────────────────────

adb_sh() { adb shell "$@" 2>/dev/null | tr -d '\r'; }
ts()     { date +%H:%M:%S; }

ALERTS=0
alert() {
    echo "${R}${B}[$(ts)] ⚠ $1${X}"
    ALERTS=$((ALERTS+1))
}

# ── pre-flight ─────────────────────────────────────────────────────────

if ! command -v adb >/dev/null; then
    echo "${R}adb not on PATH — install platform-tools${X}" >&2
    exit 1
fi

SERIAL=$(adb get-serialno 2>/dev/null | tr -d '\r')
if [ -z "$SERIAL" ] || [ "$SERIAL" = "unknown" ]; then
    echo "${R}no device connected${X}" >&2
    exit 1
fi

if ! adb_sh "pm path $PKG" | grep -q "^package:"; then
    echo "${R}package $PKG not installed${X}" >&2
    exit 1
fi

SVC_DUMP=$(adb_sh "dumpsys activity services $SERVICE")
if ! echo "$SVC_DUMP" | grep -q "ServiceRecord{"; then
    echo "${R}DiagnosticsService is not running — open launcher, long-press 'quack', enable${X}" >&2
    exit 1
fi
if ! echo "$SVC_DUMP" | grep -q "isForeground=true"; then
    echo "${R}DiagnosticsService is not foreground${X}" >&2
    exit 1
fi

# Probe initial battery state — also verifies the phone is plugged in.
BATT=$(adb_sh "dumpsys battery")
PLUGGED_INITIAL=$(echo "$BATT" | awk -F: '/^  AC powered|^  USB powered|^  Wireless powered/ {gsub(/ /,"",$2); if($2=="true") print "yes"}')
LEVEL_INITIAL=$(echo "$BATT" | awk -F: '/^  level:/ {gsub(/ /,"",$2); print $2}')
STATUS_INITIAL=$(echo "$BATT" | awk -F: '/^  status:/ {gsub(/ /,"",$2); print $2}')

if [ "$PLUGGED_INITIAL" != "yes" ]; then
    echo "${Y}⚠ phone is NOT plugged in (level=${LEVEL_INITIAL}%, status=${STATUS_INITIAL})${X}"
    echo "  This watcher is designed for plugged-in development monitoring."
    echo "  For overnight drain captures, you want the phone unplugged."
    echo
fi

EVENTS_FILE_TODAY="$MIRROR_DIR/events-$(date -u +%Y-%m-%d).jsonl"
SAMPLES_FILE_TODAY="$MIRROR_DIR/samples-$(date -u +%Y-%m-%d).jsonl"

count_lines() {
    # file may not exist yet at the very start of a fresh day; that's OK
    adb_sh "wc -l $1 2>/dev/null" | awk '{print $1+0}'
}
file_bytes() {
    adb_sh "stat -c %s $1 2>/dev/null" | awk '{print $1+0}'
}
newest_dumpsys_mtime() {
    # Returns unix epoch seconds of the newest file under dumpsys/, or 0.
    adb_sh "ls -t --time-style=+%s $MIRROR_DIR/dumpsys/ 2>/dev/null | head -1; \
            stat -c %Y \$(ls -t $MIRROR_DIR/dumpsys/* 2>/dev/null | head -1) 2>/dev/null" \
        | tail -1 | awk '{print $1+0}'
}

SAMPLES_START=$(count_lines "$SAMPLES_FILE_TODAY")
EVENTS_START=$(count_lines "$EVENTS_FILE_TODAY")
SNAP_MTIME_START=$(newest_dumpsys_mtime)

START_EPOCH=$(date +%s)
LAST_SAMPLE_BUMP_EPOCH=$START_EPOCH

# Watch crashes that appear AFTER watcher start. Easiest way: snapshot
# the current logcat crash-line count, then compare each iteration.
crash_count() {
    adb_sh "logcat -d -v brief" \
        | grep -cE "AndroidRuntime.*$PKG|FATAL EXCEPTION.*$PKG"
}
CRASH_COUNT_START=$(crash_count)

# ── header ─────────────────────────────────────────────────────────────

cat <<EOF
${B}── diagnostics watcher ──${X}
  device:    $SERIAL
  package:   $PKG
  interval:  ${INTERVAL}s
  duration:  $( [ $DURATION_S -eq 0 ] && echo forever || echo "${DURATION_S}s" )
  plugged:   $PLUGGED_INITIAL (level=${LEVEL_INITIAL}%, status=${STATUS_INITIAL})
  start:     samples=$SAMPLES_START  events=$EVENTS_START  newest_snap_age=$(( $(date +%s) - SNAP_MTIME_START ))s
  ctrl-c to stop and see summary

EOF

# ── main loop ──────────────────────────────────────────────────────────

trap 'on_exit' INT TERM

on_exit() {
    NOW=$(date +%s)
    DUR=$((NOW - START_EPOCH))
    SAMPLES_END=$(count_lines "$SAMPLES_FILE_TODAY")
    EVENTS_END=$(count_lines "$EVENTS_FILE_TODAY")
    NEW_SAMPLES=$((SAMPLES_END - SAMPLES_START))
    NEW_EVENTS=$((EVENTS_END - EVENTS_START))
    SNAP_END=$(newest_dumpsys_mtime)
    NEW_SNAPS=$( [ "$SNAP_END" -gt "$SNAP_MTIME_START" ] && \
                 adb_sh "find $MIRROR_DIR/dumpsys -type f -newer $MIRROR_DIR/dumpsys/\$(ls -tr $MIRROR_DIR/dumpsys 2>/dev/null | head -1) 2>/dev/null | wc -l" || echo 0 )
    CADENCE_OBS=$( [ "$NEW_SAMPLES" -gt 1 ] && echo "scale=1; $DUR / $NEW_SAMPLES" | bc 2>/dev/null || echo "n/a" )

    echo
    echo "${B}── summary ──${X}"
    echo "  duration:           ${DUR}s"
    echo "  samples added:      $NEW_SAMPLES  (observed cadence: ${CADENCE_OBS}s, target 60s)"
    echo "  events added:       $NEW_EVENTS"
    echo "  dumpsys snapshots:  $NEW_SNAPS new file(s) under $MIRROR_DIR/dumpsys/"
    echo "  alerts raised:      $ALERTS"
    if [ "$ALERTS" -eq 0 ] && [ "$NEW_SAMPLES" -gt 0 ]; then
        echo "  ${G}all healthy throughout the watch${X}"
    elif [ "$ALERTS" -gt 0 ]; then
        echo "  ${R}${ALERTS} alert(s) raised — see lines above marked ⚠${X}"
    fi
    exit 0
}

SAMPLES_PREV=$SAMPLES_START
EVENTS_PREV=$EVENTS_START
SNAP_MTIME_PREV=$SNAP_MTIME_START

while true; do
    sleep "$INTERVAL"

    NOW_EPOCH=$(date +%s)
    if [ "$DURATION_S" -gt 0 ] && [ $((NOW_EPOCH - START_EPOCH)) -ge "$DURATION_S" ]; then
        on_exit
    fi

    # Recompute the daily file paths each iteration so a UTC midnight
    # rollover during a long watch is handled gracefully.
    EVENTS_FILE_TODAY="$MIRROR_DIR/events-$(date -u +%Y-%m-%d).jsonl"
    SAMPLES_FILE_TODAY="$MIRROR_DIR/samples-$(date -u +%Y-%m-%d).jsonl"

    SAMPLES_NOW=$(count_lines "$SAMPLES_FILE_TODAY")
    EVENTS_NOW=$(count_lines "$EVENTS_FILE_TODAY")
    SNAP_MTIME_NOW=$(newest_dumpsys_mtime)

    DELTA_SAMPLES=$((SAMPLES_NOW - SAMPLES_PREV))
    DELTA_EVENTS=$((EVENTS_NOW - EVENTS_PREV))
    SNAP_AGE_S=$((NOW_EPOCH - SNAP_MTIME_NOW))

    # Re-probe battery and service health.
    BATT=$(adb_sh "dumpsys battery")
    PLUGGED=$(echo "$BATT" | awk -F: '/^  AC powered|^  USB powered|^  Wireless powered/ {gsub(/ /,"",$2); if($2=="true") print "yes"}')
    LEVEL=$(echo "$BATT"   | awk -F: '/^  level:/  {gsub(/ /,"",$2); print $2}')
    STATUS=$(echo "$BATT"  | awk -F: '/^  status:/ {gsub(/ /,"",$2); print $2}')
    [ -z "$PLUGGED" ] && PLUGGED=no

    SVC_FG=$(adb_sh "dumpsys activity services $SERVICE" | grep -c "isForeground=true")
    LID=$(adb_sh "tail -1 $SAMPLES_FILE_TODAY 2>/dev/null" | python3 -c '
import sys, json
try:
    line = sys.stdin.read().strip()
    if line: print(json.loads(line).get("lid_state","?"))
    else: print("?")
except Exception:
    print("?")
' 2>/dev/null)

    # One-line status (suppress if --quiet AND nothing alarming).
    if [ $QUIET -eq 0 ]; then
        printf "${D}[%s]${X} +%d samples, +%d events, snap_age=%ds, plugged=%s, level=%s%%, status=%s, lid=%s, fg=%s\n" \
            "$(ts)" "$DELTA_SAMPLES" "$DELTA_EVENTS" "$SNAP_AGE_S" "$PLUGGED" "$LEVEL" "$STATUS" "$LID" \
            "$( [ "$SVC_FG" -gt 0 ] && echo yes || echo NO )"
    fi

    # ── alerts ──
    if [ "$DELTA_SAMPLES" -gt 0 ]; then
        LAST_SAMPLE_BUMP_EPOCH=$NOW_EPOCH
    else
        SECS_SINCE_BUMP=$((NOW_EPOCH - LAST_SAMPLE_BUMP_EPOCH))
        # Expect a new sample every 60s — alert if nothing in 90s+.
        if [ "$SECS_SINCE_BUMP" -gt 90 ]; then
            alert "no new samples in ${SECS_SINCE_BUMP}s — sampling loop may have stalled"
        fi
    fi

    # Dumpsys runs hourly; allow some grace.
    if [ "$SNAP_MTIME_NOW" -gt 0 ] && [ "$SNAP_AGE_S" -gt 4200 ]; then
        alert "no dumpsys snapshot in ${SNAP_AGE_S}s (expected ≤3600s + buffer)"
    fi

    if [ "$SVC_FG" -eq 0 ]; then
        alert "DiagnosticsService no longer foreground — may have been killed"
    fi

    if [ "$PLUGGED_INITIAL" = "yes" ] && [ "$PLUGGED" != "yes" ]; then
        alert "phone unplugged mid-watch (was plugged at start)"
        PLUGGED_INITIAL=no  # don't keep nagging
    fi

    CRASH_NOW=$(crash_count)
    if [ "$CRASH_NOW" -gt "$CRASH_COUNT_START" ]; then
        NEW_CRASHES=$((CRASH_NOW - CRASH_COUNT_START))
        alert "$NEW_CRASHES new crash line(s) in logcat for $PKG"
        # Print one matching line for context.
        adb_sh "logcat -d -v brief" \
            | grep -E "AndroidRuntime.*$PKG|FATAL EXCEPTION.*$PKG" \
            | tail -1 | sed 's/^/      /'
        CRASH_COUNT_START=$CRASH_NOW  # don't re-alert on the same crash
    fi

    SAMPLES_PREV=$SAMPLES_NOW
    EVENTS_PREV=$EVENTS_NOW
    SNAP_MTIME_PREV=$SNAP_MTIME_NOW
done
