#!/usr/bin/env bash
#
# diag_reducesar_reset.sh
#
# Restore an affected device to its "original broken" state so the
# launcher's `disable_reducesar_v2` migration can be tested end-to-end.
# Use case: you have a phone where the fix already applied (component
# disabled, deep doze engaging) and you want to verify a new launcher
# build still reapplies the fix correctly on a fresh user.
#
# What it does, in order:
#   1.  Re-enables `com.tct.reducesar/.MtkCommonSarService` so the bad
#       service will start on the next boot.
#   2.  Removes any /data/adb/modules/disable_reducesar/ Magisk overlay
#       module from the historical v1 fix (no-op if it was never
#       installed or already cleaned up).
#   3.  Force-stops the launcher so its in-memory SharedPreferences
#       cache is dropped.
#   4.  Removes the launcher's migrations.xml so the v2 migration runs
#       again from scratch on next launcher start. Other migration
#       flags get cleared as a side effect — that's fine; the rest are
#       idempotent disable/grant-style ops that no-op on the second
#       run.
#   5.  Reboots the phone so reducesar respawns clean and the launcher
#       Application class re-runs from a known-clean state.
#
# After the phone is back up:
#   ./scripts/diag_drain_probe.sh   # OPTIONAL — confirms reducesar IS
#                                   # holding GPS again (i.e. broken
#                                   # state restored)
#   adb install -r app/build/outputs/apk/debug/app-debug.apk   # or
#                                   # whatever path your new build is
#   adb logcat | grep DumbDownApp   # watch for the migration line:
#                                   # "✅ Disabled com.tct.reducesar/
#                                   # .MtkCommonSarService"
#   adb reboot                      # apply the disable
#   # …unplug + lid closed + 45 min…
#   ./scripts/diag_reducesar_check.sh   # confirm the fix took
#
# Usage:
#   ./scripts/diag_reducesar_reset.sh                 # default
#   ./scripts/diag_reducesar_reset.sh --serial XYZ    # pick a device
#   ./scripts/diag_reducesar_reset.sh --no-reboot     # skip the final
#                                                     # reboot (you'll
#                                                     # have to reboot
#                                                     # manually later)
#   ./scripts/diag_reducesar_reset.sh -h

set -u

PKG="com.tct.reducesar"
COMPONENT="com.tct.reducesar/.MtkCommonSarService"
V1_MODULE_DIR="/data/adb/modules/disable_reducesar"
LAUNCHER_PKG="com.offlineinc.dumbdownlauncher"
LAUNCHER_PREFS="/data/data/${LAUNCHER_PKG}/shared_prefs/migrations.xml"

# ── arg parsing ────────────────────────────────────────────────────────

SERIAL_ARG=""
WANT_REBOOT=1

while [ $# -gt 0 ]; do
    case "$1" in
        --serial)    SERIAL_ARG="$2"; shift 2 ;;
        --no-reboot) WANT_REBOOT=0;   shift ;;
        -h|--help)
            sed -n '3,46p' "$0"
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

ok()   { printf "  ${G}✓${X} %s\n" "$1"; }
warn() { printf "  ${Y}⚠${X} %s\n" "$1"; }
err()  { printf "  ${R}✗${X} %s\n" "$1"; }
sect() { printf "\n${B}— %s —${X}\n" "$1"; }

# ── adb helpers ────────────────────────────────────────────────────────

ADB() {
    if [ -n "$SERIAL_ARG" ]; then
        adb -s "$SERIAL_ARG" "$@"
    else
        adb "$@"
    fi
}
adb_sh() { ADB shell "$@" 2>/dev/null | tr -d '\r'; }

# ── pre-flight ─────────────────────────────────────────────────────────

if ! command -v adb >/dev/null; then
    echo "${R}adb not on PATH — install Android platform-tools${X}" >&2
    exit 1
fi

SERIAL=$(ADB get-serialno 2>/dev/null | tr -d '\r')
if [ -z "$SERIAL" ] || [ "$SERIAL" = "unknown" ]; then
    if [ -z "$SERIAL_ARG" ]; then
        echo "${R}no single device connected — pass --serial <id>${X}" >&2
        adb devices -l >&2
        exit 1
    fi
    SERIAL="$SERIAL_ARG"
fi

MODEL=$(adb_sh "getprop ro.product.model")
ANDROID_VER=$(adb_sh "getprop ro.build.version.release")

cat <<EOF
${B}── reducesar reset ──${X}
  device:    $SERIAL  ($MODEL, Android $ANDROID_VER)
  intent:    restore broken state so the launcher v2 migration can be
             re-tested end-to-end

EOF

# ── 1. re-enable the component ─────────────────────────────────────────

sect "1. re-enable MtkCommonSarService"

ENABLE_OUT=$(adb_sh "su -c 'pm enable $COMPONENT'")
echo "    ${D}$ENABLE_OUT${X}"
if echo "$ENABLE_OUT" | grep -q "new state: enabled\|new state: default"; then
    ok "$COMPONENT enabled"
elif echo "$ENABLE_OUT" | grep -qi "already"; then
    ok "$COMPONENT was already enabled"
else
    # pm enable may print only its result without "new state:" depending
    # on the firmware. Treat exit code 0 (which adb_sh swallows) as
    # success and surface the raw output above.
    warn "pm enable returned unexpected output — check manually if needed"
fi

# ── 2. remove the v1 Magisk module if present ──────────────────────────

sect "2. clean up v1 Magisk module"

LS_OUT=$(adb_sh "su -c 'ls -la $V1_MODULE_DIR 2>/dev/null'")
if [ -z "$LS_OUT" ]; then
    ok "no Magisk module at $V1_MODULE_DIR (already clean)"
else
    RM_OUT=$(adb_sh "su -c 'rm -rf $V1_MODULE_DIR'")
    # verify
    LS_AFTER=$(adb_sh "su -c 'ls -la $V1_MODULE_DIR 2>/dev/null'")
    if [ -z "$LS_AFTER" ]; then
        ok "removed Magisk module at $V1_MODULE_DIR"
    else
        err "rm appeared to fail — $V1_MODULE_DIR still present"
        echo "$LS_AFTER" | head -3 | sed 's/^/      /'
    fi
fi

# ── 3. force-stop the launcher ─────────────────────────────────────────

sect "3. force-stop launcher"

ADB shell am force-stop "$LAUNCHER_PKG" >/dev/null 2>&1
# am force-stop returns no useful output; just confirm by checking the
# process is no longer in the activity stack.
if adb_sh "ps -A 2>/dev/null | grep -q '$LAUNCHER_PKG\$'" ; then
    warn "launcher process may still be running — force-stop sometimes silent-fails"
else
    ok "launcher force-stopped"
fi

# ── 4. clear the migration prefs ───────────────────────────────────────

sect "4. clear migration prefs"

PREFS_BEFORE=$(adb_sh "su -c 'cat $LAUNCHER_PREFS 2>/dev/null'")
if [ -z "$PREFS_BEFORE" ]; then
    ok "no migrations.xml present (already in clean state)"
else
    HAD_V2=$(echo "$PREFS_BEFORE" | grep -c "disable_reducesar_v2" || true)
    HAD_V1=$(echo "$PREFS_BEFORE" | grep -c "disable_reducesar_v1" || true)

    # Wipe the file. All migrations re-run on next launcher start; they're
    # idempotent so the others (disable_tcl_fota etc.) will no-op cleanly.
    adb_sh "su -c 'rm -f $LAUNCHER_PREFS'" >/dev/null
    AFTER=$(adb_sh "su -c 'ls $LAUNCHER_PREFS 2>/dev/null'")
    if [ -z "$AFTER" ]; then
        ok "removed $LAUNCHER_PREFS  ${D}(had_v2=$HAD_V2 had_v1=$HAD_V1)${X}"
    else
        err "could not remove $LAUNCHER_PREFS"
    fi
fi

# ── 5. (optional) reboot ───────────────────────────────────────────────

sect "5. reboot"

if [ "$WANT_REBOOT" -eq 1 ]; then
    ADB reboot
    ok "reboot issued — wait ~30s for the phone to come back"
    cat <<EOF

${B}what to do next:${X}
  1. ${D}# wait for the phone to come back, then optionally confirm the
     # broken state has been restored:${X}
     adb -s $SERIAL shell dumpsys location | grep -A3 "GnssStatus Listeners"
     ${D}# you should see "<pid>/com.tct.reducesar" under the header${X}

  2. ${D}# install the launcher build you want to test:${X}
     adb -s $SERIAL install -r app/build/outputs/apk/debug/app-debug.apk

  3. ${D}# watch logcat for the migration line (fires ~30s after launcher
     # process starts):${X}
     adb -s $SERIAL logcat -c
     adb -s $SERIAL logcat | grep DumbDownApp
     ${D}# look for: "✅ Disabled com.tct.reducesar/.MtkCommonSarService"${X}

  4. ${D}# reboot to actually apply the disable, then unplug + close lid +
     # leave alone for at least 45 min:${X}
     adb -s $SERIAL reboot

  5. ${D}# after the wait, plug back in and verify the fix engaged:${X}
     ./scripts/diag_reducesar_check.sh --serial $SERIAL
EOF
else
    warn "skipping reboot per --no-reboot"
    echo
    echo "  The component re-enable + Magisk-module removal + prefs wipe"
    echo "  are all in place, but the bad service won't restart cleanly"
    echo "  until the next reboot. Run 'adb reboot' before deploying the"
    echo "  new launcher build."
fi
