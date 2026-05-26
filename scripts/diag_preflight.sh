#!/usr/bin/env bash
#
# diag_preflight.sh
#
# Before-bed sanity check for the battery diagnostics module. Verifies
# that the OS *will leave the DiagnosticsService alone overnight* on the
# attached device — the thing that bit Marco last time, where TCL's
# aggressive task killer + Android doze silently killed the foreground
# service ~2 hours into a planned 20-hour capture.
#
# Run with the phone plugged into the laptop. Script connects over adb,
# runs ~14 checks, then prints either "safe to leave overnight" or a
# numbered fix list with the exact taps to do on the phone.
#
# Usage:
#   ./scripts/diag_preflight.sh                 # full check, ~3 min (incl. soak)
#   ./scripts/diag_preflight.sh --soak 60       # shorter soak (default 180s)
#   ./scripts/diag_preflight.sh --no-soak       # skip the live-sample soak
#   ./scripts/diag_preflight.sh --serial XYZ    # pick a specific device
#   ./scripts/diag_preflight.sh -h
#
# What it checks (in order, fastest to slowest):
#   1.  adb connection + single device
#   2.  package installed
#   3.  diagnostics build flag on (proxy: mirror dir exists)
#   4.  diagnostics opt-in ON (proxy: recent samples file present)
#   5.  DiagnosticsService is running
#   6.  DiagnosticsService is foreground
#   7.  RUN_ANY_IN_BACKGROUND appop = allow   (== "Unrestricted")
#   8.  App standby bucket is ACTIVE/WORKING_SET/FREQUENT  (not RESTRICTED)
#   9.  Doze whitelist contains the package
#   10. Foreground notification is currently posted
#   11. No new AndroidRuntime crash lines in logcat
#   12. Free space on /sdcard ≥ 50 MB
#   13. Soak: samples file grows ≥1 line per 60s for `--soak` seconds
#   14. Service still foreground at end of soak
#
# Exit code:
#   0 if every check passes
#   1 if any check fails (overall not-safe-overnight)
#
# Tone: ✅ pass, ⚠ warning (continue but call out), ❌ fail (blocks overnight)

set -u

PKG="com.offlineinc.dumbdownlauncher"
SERVICE="${PKG}/.diagnostics.DiagnosticsService"
MIRROR_DIR="/sdcard/Android/data/${PKG}/files/diag"
NOTIF_CHANNEL_ID="dumbdown.diagnostics"

# ── arg parsing ────────────────────────────────────────────────────────

SOAK_S=180
SERIAL_ARG=""

while [ $# -gt 0 ]; do
    case "$1" in
        --soak)     SOAK_S="$2"; shift 2 ;;
        --no-soak)  SOAK_S=0; shift ;;
        --serial)   SERIAL_ARG="$2"; shift 2 ;;
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
    G=$'\033[0;32m'; R=$'\033[0;31m'; Y=$'\033[0;33m'
    B=$'\033[1m'; D=$'\033[0;90m'; X=$'\033[0m'
else
    G=""; R=""; Y=""; B=""; D=""; X=""
fi

# ── result tracking ────────────────────────────────────────────────────

FAILS=()      # things that block an overnight capture
WARNS=()      # things to call out but not block

pass() { printf "  ${G}✅${X} %s\n" "$1"; }
warn() { printf "  ${Y}⚠${X}  %s\n" "$1"; WARNS+=("$2"); }
fail() { printf "  ${R}❌${X} %s\n" "$1"; FAILS+=("$2"); }
note() { printf "  ${D}…${X}  %s\n" "$1"; }
sect() { printf "\n${B}— %s —${X}\n" "$1"; }

# ── helpers ────────────────────────────────────────────────────────────

ADB() {
    if [ -n "$SERIAL_ARG" ]; then
        adb -s "$SERIAL_ARG" "$@"
    else
        adb "$@"
    fi
}
adb_sh() { ADB shell "$@" 2>/dev/null | tr -d '\r'; }

# ── 1+2: connection + package ──────────────────────────────────────────

sect "device"

if ! command -v adb >/dev/null; then
    echo "${R}adb not on PATH — install Android platform-tools${X}" >&2
    exit 1
fi

SERIAL=$(ADB get-serialno 2>/dev/null | tr -d '\r')
if [ -z "$SERIAL" ] || [ "$SERIAL" = "unknown" ]; then
    if [ -z "$SERIAL_ARG" ]; then
        echo "${R}❌ no single device connected — pass --serial <id>${X}" >&2
        echo
        adb devices -l >&2
        exit 1
    fi
    SERIAL="$SERIAL_ARG"
fi
pass "adb sees device  ${D}($SERIAL)${X}"

MODEL=$(adb_sh "getprop ro.product.model")
ANDROID_VER=$(adb_sh "getprop ro.build.version.release")
pass "device:  $MODEL  (Android $ANDROID_VER)"

if adb_sh "pm path $PKG" | grep -q "^package:"; then
    pass "package $PKG installed"
else
    fail "package $PKG NOT installed" \
         "Install the diagnostics build of the launcher on the device first."
    # rest of the script depends on the package — bail
    printf "\n${R}aborting — install the launcher and rerun${X}\n"
    exit 1
fi

# ── 3+4: diagnostics module + opt-in ───────────────────────────────────

sect "diagnostics enabled"

if adb_sh "[ -d $MIRROR_DIR ] && echo yes" | grep -q yes; then
    pass "diag mirror dir exists  ${D}($MIRROR_DIR)${X}"
else
    fail "diag mirror dir is missing on the phone" \
         "Diagnostics has never been opted in.  Open the launcher → long-press the 'quack' app → toggle Battery Diagnostics ON → confirm a persistent notification appears.  Then rerun this preflight."
    printf "\n${R}aborting — opt in first${X}\n"
    exit 1
fi

SAMPLES_TODAY="$MIRROR_DIR/samples-$(date -u +%Y-%m-%d).jsonl"
SAMPLES_LINES_NOW=$(adb_sh "wc -l $SAMPLES_TODAY 2>/dev/null" | awk '{print $1+0}')
if [ "$SAMPLES_LINES_NOW" -gt 0 ]; then
    pass "today's samples file has $SAMPLES_LINES_NOW line(s)"
else
    warn "today's samples file is empty or missing" \
         "Samples haven't started yet today.  Confirm the toggle is ON; the soak below will tell us for sure."
fi

# ── 5+6: service running and foreground ────────────────────────────────

sect "DiagnosticsService"

SVC_DUMP=$(adb_sh "dumpsys activity services $SERVICE")
if echo "$SVC_DUMP" | grep -q "ServiceRecord{"; then
    pass "service is running"
else
    fail "DiagnosticsService is NOT running" \
         "Open the launcher → long-press 'quack' → toggle Battery Diagnostics ON.  Confirm the persistent notification appears."
fi

if echo "$SVC_DUMP" | grep -q "isForeground=true"; then
    pass "service is foreground  ${D}(survives doze and low-memory kills)${X}"
else
    if echo "$SVC_DUMP" | grep -q "ServiceRecord{"; then
        fail "service is running but NOT foreground — will be killed in doze" \
             "Toggle Battery Diagnostics OFF then ON again from the launcher; if it still doesn't promote to foreground, ping Jack — likely a permission regression."
    fi
fi

# ── 7: RUN_ANY_IN_BACKGROUND appop (Unrestricted) ──────────────────────

sect "battery restrictions"

# Output forms we have to tolerate on Android 11:
#   "RUN_ANY_IN_BACKGROUND: allow; time=+5d3h ago"
#   "RUN_ANY_IN_BACKGROUND: ignore"
#   "Uid mode: allow"
#   "No operations."                     (== system default, which is allow)
# Pull the first allow/ignore/deny/default token regardless of position.
BG_RAW=$(adb_sh "cmd appops get $PKG RUN_ANY_IN_BACKGROUND" 2>/dev/null)
BG_OP=$(printf '%s' "$BG_RAW" \
        | grep -oiE '\b(allow|ignore|deny|default)\b' \
        | head -1 \
        | tr '[:upper:]' '[:lower:]')
# If appops printed "No operations." we get an empty BG_OP — that means
# the user hasn't overridden the default, which on Android 11 == allow.
if [ -z "$BG_OP" ] && printf '%s' "$BG_RAW" | grep -qi 'No operations'; then
    BG_OP="default"
fi
case "$BG_OP" in
    allow|default|"")
        pass "RUN_ANY_IN_BACKGROUND = ${BG_OP:-default}  ${D}(== Unrestricted)${X}"
        ;;
    ignore|deny)
        fail "RUN_ANY_IN_BACKGROUND = $BG_OP — the OS WILL kill the service in the background" \
             "On the phone: Settings → Apps → 'dumb down launcher' → Battery → set to Unrestricted (NOT Optimized, NOT Restricted)."
        ;;
    *)
        warn "RUN_ANY_IN_BACKGROUND = $BG_OP (unrecognized)" \
             "Manually confirm Settings → Apps → 'dumb down launcher' → Battery is set to Unrestricted."
        ;;
esac

# ── 8: app standby bucket ──────────────────────────────────────────────

BUCKET=$(adb_sh "am get-standby-bucket $PKG" | awk '{print $1+0}')
# 10=ACTIVE 20=WORKING_SET 30=FREQUENT 40=RARE 45=RESTRICTED 50=NEVER
BUCKET_NAME="?"
case "$BUCKET" in
    10) BUCKET_NAME="ACTIVE" ;;
    20) BUCKET_NAME="WORKING_SET" ;;
    30) BUCKET_NAME="FREQUENT" ;;
    40) BUCKET_NAME="RARE" ;;
    45) BUCKET_NAME="RESTRICTED" ;;
    50) BUCKET_NAME="NEVER" ;;
esac

if [ "$BUCKET" -le 30 ] 2>/dev/null && [ "$BUCKET" -gt 0 ] 2>/dev/null; then
    pass "app standby bucket = $BUCKET_NAME ($BUCKET)"
elif [ "$BUCKET" -ge 40 ] 2>/dev/null; then
    fail "app standby bucket = $BUCKET_NAME ($BUCKET) — doze will throttle wakeups" \
         "Use the app a few times today (open the launcher, open quack) so the bucket drops back to ACTIVE.  Or as adb root override: adb shell am set-standby-bucket $PKG active"
else
    warn "app standby bucket could not be read (output: '$BUCKET')" \
         "Manually confirm by running: adb shell am get-standby-bucket $PKG"
fi

# ── 9: doze whitelist ──────────────────────────────────────────────────

# whitelist lines look like: "system,com.android.foo,uid=10123" or
# "user,com.bar,uid=10456" — match the package as a whole comma-separated
# field so we don't false-positive on a substring.
WHITELIST=$(adb_sh "dumpsys deviceidle whitelist")
if echo "$WHITELIST" | grep -qE "(^|,)${PKG//./\\.}(,|$)"; then
    pass "package is on the doze whitelist  ${D}(power-save exempt)${X}"
else
    fail "package NOT on doze whitelist — doze will suspend the service during deep idle" \
         "On the phone: Settings → Apps → 'dumb down launcher' → Battery → Unrestricted.  (That single toggle puts it on the whitelist too on most Android 11 builds.)  If the whitelist still doesn't pick it up after re-toggling, fall back to: adb shell dumpsys deviceidle whitelist +$PKG"
fi

# ── 10: foreground notification posted ─────────────────────────────────

sect "notification"

# dumpsys notification format varies across OEM builds, so we try the
# verbose form first and fall back to the plain form. The real binding
# contract (isForeground=true) is checked above; this is a secondary
# signal that the notification is actually visible to the user, so a
# miss here is a warning rather than a fail.
NOTIF_DUMP=$(adb_sh "dumpsys notification --noredact" 2>/dev/null)
if [ -z "$NOTIF_DUMP" ]; then
    NOTIF_DUMP=$(adb_sh "dumpsys notification" 2>/dev/null)
fi
if echo "$NOTIF_DUMP" | grep -q "pkg=$PKG"; then
    if echo "$NOTIF_DUMP" | grep -q "$NOTIF_CHANNEL_ID"; then
        pass "diagnostics notification is currently posted  ${D}(channel: $NOTIF_CHANNEL_ID)${X}"
    else
        warn "notification for $PKG is posted but the diagnostics channel id was not seen" \
             "Probably a dumpsys formatting quirk — the foreground-service check above is the binding test.  If the notification isn't visible on the phone, toggle Battery Diagnostics OFF/ON."
    fi
else
    # If the service is isForeground=true (already verified) but no
    # notification record shows up, the channel is likely user-blocked.
    warn "no notification record visible for $PKG in dumpsys" \
         "On Android 13+: grant Notifications permission for the launcher.  On Android 11 (this device): the channel may be blocked — Settings → Apps → 'dumb down launcher' → Notifications → enable the 'Battery diagnostics' channel."
fi

# ── 11: recent crashes ─────────────────────────────────────────────────

sect "stability"

CRASHES=$(adb_sh "logcat -d -v brief" \
          | grep -cE "AndroidRuntime.*$PKG|FATAL EXCEPTION.*$PKG" \
          | awk '{print $1+0}')
if [ "$CRASHES" -eq 0 ]; then
    pass "no AndroidRuntime crashes in current logcat"
else
    warn "$CRASHES crash line(s) in current logcat for $PKG" \
         "Inspect with: adb logcat -d | grep -E 'AndroidRuntime|FATAL EXCEPTION' | grep $PKG.  A recent crash may indicate the service has been restarting in a loop."
fi

# ── 12: disk space ─────────────────────────────────────────────────────

# df output: "Filesystem 1K-blocks Used Available Use% Mounted on"
SDCARD_FREE_KB=$(adb_sh "df /sdcard 2>/dev/null | tail -1" | awk '{print $4+0}')
SDCARD_FREE_MB=$(( SDCARD_FREE_KB / 1024 ))
if [ "$SDCARD_FREE_MB" -ge 50 ]; then
    pass "/sdcard has ${SDCARD_FREE_MB} MB free  ${D}(≥50 MB needed for ~20h capture)${X}"
else
    fail "/sdcard only has ${SDCARD_FREE_MB} MB free" \
         "Free up space on the device.  ~20h of diagnostics writes ~5-10 MB but the OS panics well before zero."
fi

# ── 13+14: soak test ───────────────────────────────────────────────────

if [ "$SOAK_S" -gt 0 ]; then
    sect "live soak ($SOAK_S s)"

    LINES_START=$(adb_sh "wc -l $SAMPLES_TODAY 2>/dev/null" | awk '{print $1+0}')
    note "starting line count: $LINES_START"
    note "watching for new samples for ${SOAK_S}s …  ${D}(Ctrl-C aborts the soak but not the script)${X}"

    SOAK_START=$(date +%s)
    LAST_LINES=$LINES_START
    LAST_BUMP_EPOCH=$SOAK_START

    while :; do
        sleep 15
        NOW=$(date +%s)
        ELAPSED=$((NOW - SOAK_START))
        LINES_NOW=$(adb_sh "wc -l $SAMPLES_TODAY 2>/dev/null" | awk '{print $1+0}')
        if [ "$LINES_NOW" -gt "$LAST_LINES" ]; then
            DELTA=$((LINES_NOW - LAST_LINES))
            printf "    ${D}[%3ds] +%d sample(s)  →  total %d${X}\n" \
                "$ELAPSED" "$DELTA" "$LINES_NOW"
            LAST_LINES=$LINES_NOW
            LAST_BUMP_EPOCH=$NOW
        fi
        if [ "$ELAPSED" -ge "$SOAK_S" ]; then break; fi
    done

    LINES_END=$(adb_sh "wc -l $SAMPLES_TODAY 2>/dev/null" | awk '{print $1+0}')
    NEW_LINES=$((LINES_END - LINES_START))
    # Cadence is one sample per 60s, so expect floor(SOAK_S / 60) ± 1.
    EXPECTED=$(( SOAK_S / 60 ))
    [ "$EXPECTED" -lt 1 ] && EXPECTED=1

    if [ "$NEW_LINES" -ge "$EXPECTED" ]; then
        pass "$NEW_LINES new sample(s) in ${SOAK_S}s  ${D}(expected ≥$EXPECTED)${X}"
    elif [ "$NEW_LINES" -gt 0 ]; then
        warn "only $NEW_LINES new sample(s) in ${SOAK_S}s, expected ≥$EXPECTED" \
             "Sampling is happening but slower than the 60s cadence — usually means the executor is fine but the phone briefly slept.  Tolerable but worth flagging."
    else
        fail "zero new samples in ${SOAK_S}s — sampling loop is dead" \
             "Toggle Battery Diagnostics OFF then ON again.  If it still doesn't tick, check logcat for the executor name 'DiagnosticsService-sched'."
    fi

    # Re-check service is still foreground after the soak.
    SVC_DUMP_END=$(adb_sh "dumpsys activity services $SERVICE")
    if echo "$SVC_DUMP_END" | grep -q "isForeground=true"; then
        pass "service still foreground after soak"
    else
        fail "service lost foreground status during the soak" \
             "Re-toggle Battery Diagnostics.  If it keeps falling out of foreground, the notification channel is likely blocked — see the Notification fix above."
    fi
else
    sect "live soak"
    note "skipped (--no-soak)"
fi

# ── verdict ────────────────────────────────────────────────────────────

sect "verdict"

if [ "${#FAILS[@]}" -eq 0 ]; then
    if [ "${#WARNS[@]}" -eq 0 ]; then
        echo "  ${G}${B}✅ safe to leave overnight${X}"
        echo
        echo "  $MODEL is good for an unattended capture.  Steps:"
        echo "    1.  unplug the phone"
        echo "    2.  close the lid"
        echo "    3.  put it flat and don't touch it until morning"
        echo "    4.  in the morning, run:"
        echo "        ./scripts/diag_pull.sh --logcat --bundle"
        exit 0
    else
        echo "  ${Y}${B}🟡 safe to leave overnight, with ${#WARNS[@]} warning(s):${X}"
        i=1
        for w in "${WARNS[@]}"; do
            printf "    ${Y}%d.${X} %s\n" "$i" "$w"
            i=$((i+1))
        done
        exit 0
    fi
else
    echo "  ${R}${B}❌ NOT safe overnight — fix these first:${X}"
    i=1
    for f in "${FAILS[@]}"; do
        printf "    ${R}%d.${X} %s\n\n" "$i" "$f"
        i=$((i+1))
    done
    if [ "${#WARNS[@]}" -gt 0 ]; then
        echo "  ${Y}also worth fixing (won't block, but worth attention):${X}"
        i=1
        for w in "${WARNS[@]}"; do
            printf "    ${Y}%d.${X} %s\n" "$i" "$w"
            i=$((i+1))
        done
    fi
    echo
    echo "  rerun ${B}./scripts/diag_preflight.sh${X} after each fix."
    exit 1
fi
