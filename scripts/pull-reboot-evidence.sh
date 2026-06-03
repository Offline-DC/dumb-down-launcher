#!/usr/bin/env bash
#
# Pull the rolling-logcat bundle off the one beta device and stitch
# it into a single chronological log so the support engineer reads
# one file instead of unzipping a directory of segments.
#
# Pairs with the on-device RollingLogcatTail + RebootLoggingService.
#
# Usage:
#   ./pull-reboot-evidence.sh                    # first connected device
#   ./pull-reboot-evidence.sh <serial>           # explicit ADB serial
#   ./pull-reboot-evidence.sh -o /tmp/out        # custom output dir
#
# Output layout:
#   reboot-logs-<serial>-<stamp>/
#     manifest.txt              ← `adb shell getprop` at pull time
#     pulled/                   ← exact mirror of the device-side dir
#       current.log             ← in-progress segment at pull time
#       segment-*.log.gz        ← rotated, gzipped segments
#       segment-pre-*.log       ← survivor of a prior process run (i.e.
#                                 "the log from before the crash")
#     timeline.log              ← all segments concatenated in order

set -euo pipefail

PKG="com.offlineinc.dumbdownlauncher"
DEVICE_DIR="/sdcard/Android/data/${PKG}/files/diag/rolling-logcat"

OUT_BASE=""
SERIAL=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        -o|--out) OUT_BASE="$2"; shift 2 ;;
        -h|--help)
            sed -n '2,30p' "$0"
            exit 0
            ;;
        *) SERIAL="$1"; shift ;;
    esac
done

if [[ -z "$SERIAL" ]]; then
    SERIAL=$(adb devices | awk 'NR>1 && $2=="device" { print $1; exit }')
    if [[ -z "$SERIAL" ]]; then
        echo "no device connected" >&2
        exit 1
    fi
fi

STAMP=$(date +%Y%m%d-%H%M%S)
OUT_BASE="${OUT_BASE:-.}"
OUT="${OUT_BASE}/reboot-logs-${SERIAL}-${STAMP}"
mkdir -p "${OUT}/pulled"

echo "Device: ${SERIAL}"
echo "Output: ${OUT}"
echo

# 1. Manifest — useful even if the device-side dir is missing.
{
    echo "# pulled $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "# adb serial: ${SERIAL}"
    echo
    echo "# --- ro.build.fingerprint ---"
    adb -s "${SERIAL}" shell getprop ro.build.fingerprint
    echo "# --- sys.boot.reason ---"
    adb -s "${SERIAL}" shell getprop sys.boot.reason
    echo "# --- sys.boot.reason.last ---"
    adb -s "${SERIAL}" shell getprop sys.boot.reason.last
    echo "# --- ro.boot.bootreason ---"
    adb -s "${SERIAL}" shell getprop ro.boot.bootreason
    echo "# --- uptime ---"
    adb -s "${SERIAL}" shell cat /proc/uptime
} > "${OUT}/manifest.txt" 2>&1 || true

# 2. The actual rolling-logcat dir. The /sdcard mirror is the canonical
#    pull source — does not require root.
if adb -s "${SERIAL}" shell "[ -d ${DEVICE_DIR} ]" 2>/dev/null; then
    echo "Pulling ${DEVICE_DIR} -> ${OUT}/pulled/"
    adb -s "${SERIAL}" pull "${DEVICE_DIR}/." "${OUT}/pulled/" >/dev/null
    echo
    echo "Segments pulled:"
    ls -la "${OUT}/pulled" || true
else
    echo "WARNING: ${DEVICE_DIR} does not exist on the device." >&2
    echo "Check: is REBOOT_LOGGING_ENABLED true in the installed build?" >&2
    echo "Check: did the user enable the in-app opt-in?" >&2
    exit 1
fi

# 3. Stitch every segment into a single chronological timeline.
#    Sort by filename (which embeds the rotation timestamp) so the
#    order is correct regardless of mtime drift.
echo
echo "Stitching timeline.log…"
{
    # current.log is the in-progress segment — append after the
    # rotated ones so it's last. The pre-* files are out-of-band
    # survivors of an earlier process run; put them first so the
    # whole timeline reads chronologically.
    for f in $(ls "${OUT}/pulled"/segment-pre-* 2>/dev/null | sort); do
        echo "===== $(basename "$f") ====="
        case "$f" in *.gz) zcat "$f" ;; *) cat "$f" ;; esac
    done
    for f in $(ls "${OUT}/pulled"/segment-2* 2>/dev/null | sort); do
        echo "===== $(basename "$f") ====="
        case "$f" in *.gz) zcat "$f" ;; *) cat "$f" ;; esac
    done
    if [[ -f "${OUT}/pulled/current.log" ]]; then
        echo "===== current.log ====="
        cat "${OUT}/pulled/current.log"
    fi
} > "${OUT}/timeline.log"

LINES=$(wc -l < "${OUT}/timeline.log" 2>/dev/null || echo 0)
echo
echo "Done. ${LINES} log lines stitched into:"
echo "  ${OUT}/timeline.log"
echo
echo "Look for the trailing lines just before the device went down —"
echo "AndroidRuntime crashes, system_server WTF, BUG:, kernel watchdog."
