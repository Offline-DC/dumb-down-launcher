#!/usr/bin/env bash
#
# diag_pull.sh
#
# Pulls the battery diagnostics tree off a connected TCL Flip 2 (or any
# other device with the launcher's diagnostics module enabled) into a
# timestamped local folder. Verifies the expected files arrived, prints a
# summary, and optionally tars the result for handing to whoever is doing
# analysis.
#
# Where the data lives on device:
#   /sdcard/Android/data/com.offlineinc.dumbdownlauncher/files/diag/
#     manifest.json
#     samples-YYYY-MM-DD.jsonl   (one per UTC day)
#     events-YYYY-MM-DD.jsonl    (one per UTC day)
#     dumpsys/*.txt              (hourly + screen-on/off snapshots)
#     logcat-*.txt               (rotated tail snapshots, if present)
#
# Where it lands locally:
#   ./diag_pulls/<device-serial>_<utc-timestamp>/
#     diag/...                   (full tree, mirror of on-device layout)
#     pull_meta.json             (serial, model, build, time, sizes, line counts)
#     logcat_fresh.txt           (optional, with --logcat)
#   ./diag_pulls/<device-serial>_<utc-timestamp>.tar.gz   (optional, with --bundle)
#
# Usage:
#   ./battery_analysis/diag_pull.sh                       # pull into ./diag_pulls/<...>/
#   ./battery_analysis/diag_pull.sh --out ~/Desktop       # custom destination root
#   ./battery_analysis/diag_pull.sh --logcat              # also grab a fresh logcat tail
#   ./battery_analysis/diag_pull.sh --bundle              # tar.gz the pull dir at the end
#   ./battery_analysis/diag_pull.sh --serial R5CN123      # pick a specific device
#   ./battery_analysis/diag_pull.sh --clear               # delete on-device diag/ after pulling
#   ./battery_analysis/diag_pull.sh -h
#
# The pull leaves the on-device files in place by default so the service
# can keep appending. Use --clear ONLY after you've sanity-checked the
# local copy (line counts in the summary should match your expectations).

set -u

PKG="com.offlineinc.dumbdownlauncher"
MIRROR_DIR="/sdcard/Android/data/${PKG}/files/diag"

# ── arg parsing ────────────────────────────────────────────────────────

OUT_ROOT="./diag_pulls"
SERIAL_ARG=""
WANT_LOGCAT=0
WANT_BUNDLE=0
CLEAR_AFTER=0

while [ $# -gt 0 ]; do
    case "$1" in
        --out)     OUT_ROOT="$2"; shift 2 ;;
        --serial)  SERIAL_ARG="$2"; shift 2 ;;
        --logcat)  WANT_LOGCAT=1; shift ;;
        --bundle)  WANT_BUNDLE=1; shift ;;
        --clear)   CLEAR_AFTER=1; shift ;;
        -h|--help)
            sed -n '3,40p' "$0"
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

ADB() {
    if [ -n "$SERIAL_ARG" ]; then
        adb -s "$SERIAL_ARG" "$@"
    else
        adb "$@"
    fi
}
adb_sh() { ADB shell "$@" 2>/dev/null | tr -d '\r'; }

human_bytes() {
    # Portable across mac/linux without numfmt.
    awk -v b="$1" 'BEGIN {
        split("B KB MB GB TB", u);
        i = 1;
        while (b >= 1024 && i < 5) { b /= 1024; i++ }
        printf (i == 1 ? "%d %s" : "%.1f %s"), b, u[i];
    }'
}

# ── pre-flight ─────────────────────────────────────────────────────────

if ! command -v adb >/dev/null; then
    echo "${R}adb not on PATH — install Android platform-tools${X}" >&2
    exit 1
fi

# adb get-serialno returns the literal string "unknown" if multiple devices
# are attached without -s; handle that.
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

if ! adb_sh "pm path $PKG" | grep -q "^package:"; then
    echo "${R}package $PKG not installed on $SERIAL${X}" >&2
    exit 1
fi

# Does the mirror dir even exist? It's created lazily by the service the
# first time diagnostics are enabled, so the most common failure here is
# "diagnostics never turned on for this user".
if ! adb_sh "[ -d $MIRROR_DIR ] && echo yes" | grep -q yes; then
    echo "${R}no diag/ folder on device${X}" >&2
    echo "  expected: $MIRROR_DIR"
    echo "  the user probably never opted in. Open the launcher, long-press"
    echo "  the 'quack' app, toggle diagnostics on, wait a minute, retry."
    exit 1
fi

# Capture identifying device metadata before we touch anything.
MODEL=$(adb_sh "getprop ro.product.model")
BUILD=$(adb_sh "getprop ro.build.fingerprint")
ANDROID_VER=$(adb_sh "getprop ro.build.version.release")

# ── plan the destination ───────────────────────────────────────────────

UTC_TS=$(date -u +%Y-%m-%dT%H-%M-%SZ)
SAFE_SERIAL=$(printf '%s' "$SERIAL" | tr -c 'A-Za-z0-9._-' '_')
PULL_DIR="$OUT_ROOT/${SAFE_SERIAL}_${UTC_TS}"
mkdir -p "$PULL_DIR" || { echo "${R}cannot create $PULL_DIR${X}" >&2; exit 1; }

cat <<EOF
${B}── diag_pull ──${X}
  device:   $SERIAL
  model:    $MODEL  (Android $ANDROID_VER)
  source:   $MIRROR_DIR
  dest:     $PULL_DIR

EOF

# ── show what's about to be pulled ─────────────────────────────────────

ON_DEV_BYTES=$(adb_sh "du -sb $MIRROR_DIR 2>/dev/null" | awk '{print $1+0}')
ON_DEV_FILES=$(adb_sh "find $MIRROR_DIR -type f 2>/dev/null | wc -l" | awk '{print $1+0}')

echo "  on-device:  $ON_DEV_FILES files, $(human_bytes "$ON_DEV_BYTES")"
if [ "$ON_DEV_FILES" -eq 0 ]; then
    echo "${R}  diag/ is empty — nothing to pull${X}" >&2
    rm -rf "$PULL_DIR"
    exit 1
fi

# ── pull ───────────────────────────────────────────────────────────────

# `adb pull <src>/` with the trailing slash mirrors the source dir under
# the destination. We get $PULL_DIR/diag/ as a result, which matches the
# on-device layout exactly. -a preserves mtimes so the analysis bundle
# tool can reason about timing.
echo "  ${D}adb pull -a $MIRROR_DIR ...${X}"
if ! ADB pull -a "$MIRROR_DIR" "$PULL_DIR/" >/dev/null 2>&1; then
    echo "${R}adb pull failed — is USB still up? Re-plug and rerun.${X}" >&2
    exit 1
fi

# Optionally grab a fresh logcat too. This catches AndroidRuntime crashes
# that haven't yet been rotated into the on-device logcat-*.txt snapshots.
if [ "$WANT_LOGCAT" -eq 1 ]; then
    echo "  ${D}capturing fresh logcat tail ...${X}"
    adb_sh "logcat -d -v threadtime" > "$PULL_DIR/logcat_fresh.txt" 2>/dev/null || true
fi

# ── verify + summarize ─────────────────────────────────────────────────

LOCAL_DIAG="$PULL_DIR/diag"
if [ ! -d "$LOCAL_DIAG" ]; then
    echo "${R}pull completed but $LOCAL_DIAG missing — adb behaved oddly${X}" >&2
    exit 1
fi

# Counts we care about for the summary.
LOCAL_FILES=$(find "$LOCAL_DIAG" -type f | wc -l | awk '{print $1+0}')
DUMPSYS_FILES=$(find "$LOCAL_DIAG/dumpsys" -type f 2>/dev/null | wc -l | awk '{print $1+0}')
SAMPLE_FILES=$(find "$LOCAL_DIAG" -maxdepth 1 -name 'samples-*.jsonl' | wc -l | awk '{print $1+0}')
EVENT_FILES=$(find "$LOCAL_DIAG" -maxdepth 1 -name 'events-*.jsonl' | wc -l | awk '{print $1+0}')

# Total sample/event line counts across all day-rotated files.
sum_lines() {
    local pat="$1" total=0 n=0
    while IFS= read -r f; do
        n=$(wc -l < "$f" 2>/dev/null | awk '{print $1+0}')
        total=$(( total + n ))
    done < <(find "$LOCAL_DIAG" -maxdepth 1 -name "$pat" 2>/dev/null)
    echo "$total"
}
SAMPLE_LINES=$(sum_lines 'samples-*.jsonl')
EVENT_LINES=$(sum_lines 'events-*.jsonl')

LOCAL_BYTES=$(find "$LOCAL_DIAG" -type f -print0 \
    | xargs -0 wc -c 2>/dev/null \
    | tail -1 \
    | awk '{print $1+0}')

# Time range of the samples (first and last ts_iso in the oldest/newest
# day file). Cheap proxy that doesn't need jq.
TIME_FIRST=""
TIME_LAST=""
OLDEST_SAMPLE=$(find "$LOCAL_DIAG" -maxdepth 1 -name 'samples-*.jsonl' | sort | head -1)
NEWEST_SAMPLE=$(find "$LOCAL_DIAG" -maxdepth 1 -name 'samples-*.jsonl' | sort | tail -1)
if [ -n "$OLDEST_SAMPLE" ]; then
    TIME_FIRST=$(head -1 "$OLDEST_SAMPLE" | sed -n 's/.*"ts_iso":"\([^"]*\)".*/\1/p')
fi
if [ -n "$NEWEST_SAMPLE" ]; then
    TIME_LAST=$(tail -1 "$NEWEST_SAMPLE" | sed -n 's/.*"ts_iso":"\([^"]*\)".*/\1/p')
fi

# Crash counts in any pulled logcat tails (rotated + fresh).
CRASH_LINES=$(grep -hcE "AndroidRuntime.*$PKG|FATAL EXCEPTION.*$PKG" \
    "$LOCAL_DIAG"/logcat-*.txt "$PULL_DIR/logcat_fresh.txt" 2>/dev/null \
    | awk '{s+=$1} END {print s+0}')

# Write a small pull_meta.json so the analysis tool can attribute the
# bundle to the right device without us hand-editing anything.
cat > "$PULL_DIR/pull_meta.json" <<EOF
{
  "schema": 1,
  "serial": "$SERIAL",
  "model": "$MODEL",
  "android_release": "$ANDROID_VER",
  "build_fingerprint": "$BUILD",
  "package": "$PKG",
  "pulled_at_utc": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "on_device_files": $ON_DEV_FILES,
  "on_device_bytes": $ON_DEV_BYTES,
  "local_files": $LOCAL_FILES,
  "local_bytes": $LOCAL_BYTES,
  "sample_files": $SAMPLE_FILES,
  "event_files": $EVENT_FILES,
  "dumpsys_files": $DUMPSYS_FILES,
  "sample_lines": $SAMPLE_LINES,
  "event_lines": $EVENT_LINES,
  "first_sample_ts": "${TIME_FIRST:-}",
  "last_sample_ts": "${TIME_LAST:-}",
  "crashes_in_logcat": $CRASH_LINES
}
EOF

# Sanity checks. None of these are fatal — we still leave the data on
# disk for inspection — but we flag them clearly.
WARN=0
warn() { echo "${Y}  ⚠ $1${X}"; WARN=$((WARN+1)); }

[ "$LOCAL_FILES" -lt "$ON_DEV_FILES" ] && \
    warn "pulled $LOCAL_FILES of $ON_DEV_FILES files — pull may be partial"
[ "$SAMPLE_FILES" -eq 0 ] && \
    warn "no samples-*.jsonl files found — service may not have written any samples"
[ "$DUMPSYS_FILES" -eq 0 ] && \
    warn "no dumpsys/*.txt snapshots — privileged scheduler never ran (su denied?)"
[ "$CRASH_LINES" -gt 0 ] && \
    warn "$CRASH_LINES AndroidRuntime crash line(s) for $PKG in logcat"

# ── print summary ──────────────────────────────────────────────────────

echo
echo "${B}── summary ──${X}"
printf "  files:        %d  (%s)\n"    "$LOCAL_FILES"   "$(human_bytes "$LOCAL_BYTES")"
printf "  samples:      %d lines across %d day-file(s)\n" "$SAMPLE_LINES" "$SAMPLE_FILES"
printf "  events:       %d lines across %d day-file(s)\n" "$EVENT_LINES"  "$EVENT_FILES"
printf "  dumpsys:      %d snapshot file(s)\n"  "$DUMPSYS_FILES"
if [ -n "$TIME_FIRST$TIME_LAST" ]; then
    printf "  time range:   %s  →  %s\n" "${TIME_FIRST:-?}" "${TIME_LAST:-?}"
fi
printf "  crashes:      %d\n" "$CRASH_LINES"
echo
echo "  pulled into:  $PULL_DIR"
[ "$WARN" -gt 0 ] && echo "  ${Y}warnings:    $WARN  (see above)${X}"

# ── optional: tar the result for handing off ───────────────────────────

if [ "$WANT_BUNDLE" -eq 1 ]; then
    BUNDLE="${PULL_DIR}.tar.gz"
    echo
    echo "  ${D}bundling → $BUNDLE${X}"
    # -C so the tarball doesn't carry the OUT_ROOT prefix.
    if tar -C "$OUT_ROOT" -czf "$BUNDLE" "$(basename "$PULL_DIR")"; then
        BUNDLE_BYTES=$(wc -c < "$BUNDLE" | awk '{print $1+0}')
        echo "  bundle:       $BUNDLE  ($(human_bytes "$BUNDLE_BYTES"))"
    else
        echo "${R}  tar failed${X}" >&2
    fi
fi

# ── optional: clear on-device after a successful, complete pull ────────

if [ "$CLEAR_AFTER" -eq 1 ]; then
    if [ "$LOCAL_FILES" -lt "$ON_DEV_FILES" ]; then
        echo
        echo "${Y}  --clear requested but pull looks partial — skipping delete${X}"
    else
        echo
        echo "  ${D}clearing on-device diag/ ...${X}"
        # Leave the dir itself in place so the service doesn't have to
        # recreate it; nuke the contents only.
        adb_sh "rm -rf $MIRROR_DIR/dumpsys $MIRROR_DIR/*.jsonl $MIRROR_DIR/*.json $MIRROR_DIR/logcat-*.txt"
        echo "  on-device diag/ contents removed"
    fi
fi

# ── next steps ─────────────────────────────────────────────────────────

cat <<EOF

${B}next:${X}
  build the analysis bundle:
    python3 tools/build-analysis-bundle.py --pull-dir "$PULL_DIR"

  inspect raw data:
    ls "$LOCAL_DIAG"
    head -1 "$LOCAL_DIAG"/samples-*.jsonl
    head    "$LOCAL_DIAG"/events-*.jsonl
EOF
