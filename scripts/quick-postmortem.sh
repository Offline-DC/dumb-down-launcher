#!/usr/bin/env bash
#
# Grab whatever post-mortem evidence Android still has on disk
# IMMEDIATELY after a random reboot. Run this from your laptop with
# the phone connected via USB debugging — no diag build required, but
# the device DOES need Magisk root (Flip 2 betas already have it).
#
# What this captures, in order of usefulness for "why did it reboot":
#
#   1. sys.boot.reason.last + ro.boot.bootreason — bootloader's
#      one-line answer. Often the only thing you need
#      (kernel_panic / watchdog / thermal / hw_reset / etc.).
#   2. /sys/fs/pstore/* — kernel's last ring buffer from the previous
#      boot, preserved in reserved RAM across the reboot. This is
#      where "last kmsg" lives on Android 11+. If a kernel panic
#      crashed the device, the panic banner is here.
#   3. /data/system/dropbox/ — last 24h of system_server crashes,
#      watchdog timeouts, ANRs, native tombstones, kernel_panic
#      drops. Filenames are <tag>@<ms>.<ext>.
#   4. dmesg -T — current boot's kernel log; the very first lines
#      often name the previous boot's cause.
#   5. logcat -b crash/events/system -d — current boot only (the
#      reboot wiped the rest), but useful if the cause is recurring.
#
# Usage:
#   ./quick-postmortem.sh                     # first connected device
#   ./quick-postmortem.sh <adb-serial>        # explicit device
#
# Output goes into ./postmortem-<serial>-<stamp>/ on your laptop.
# Total runtime ~5-10 seconds. Bug reports are 2+ minutes and overkill
# for the "I just want to see what happened" case.

set -euo pipefail

SERIAL="${1:-}"
if [[ -z "$SERIAL" ]]; then
    SERIAL=$(adb devices | awk 'NR>1 && $2=="device" { print $1; exit }')
    if [[ -z "$SERIAL" ]]; then
        echo "no device connected" >&2
        exit 1
    fi
fi

STAMP=$(date +%Y%m%d-%H%M%S)
OUT="postmortem-${SERIAL}-${STAMP}"
mkdir -p "${OUT}"
echo "Capturing into ${OUT}/"

# 1. Bootloader / framework reboot reasons — read these FIRST.
echo "  -> boot reason..."
{
    for p in sys.boot.reason sys.boot.reason.last ro.boot.bootreason \
             ro.boot.alarmboot ro.boottime.zygote ro.boottime.init \
             ro.runtime.firstboot ro.build.fingerprint; do
        printf '%s=%s\n' "$p" "$(adb -s "${SERIAL}" shell getprop "$p")"
    done
    echo
    echo "# --- /proc/uptime ---"
    adb -s "${SERIAL}" shell cat /proc/uptime
    echo
    echo "# --- /proc/sys/kernel/random/boot_id (this boot) ---"
    adb -s "${SERIAL}" shell cat /proc/sys/kernel/random/boot_id
} > "${OUT}/bootreason.txt" 2>&1

# 2. Pstore — the previous boot's last kernel log. The single most
#    useful artifact for kernel-side reboot causes.
echo "  -> pstore (previous boot's kernel log)..."
{
    adb -s "${SERIAL}" shell "su -c 'ls -la /sys/fs/pstore/ 2>/dev/null'"
    echo '---'
    adb -s "${SERIAL}" shell "su -c 'for f in /sys/fs/pstore/*; do
        [ -f \"\$f\" ] || continue
        echo \"=== \$f ===\"
        cat \"\$f\" 2>/dev/null
        echo
    done'"
} > "${OUT}/pstore.txt" 2>&1

# 3. Dropbox — system_server crashes, watchdog, ANRs, kernel_panic
#    drops from the last 24h. Each entry is a separate small file.
echo "  -> dropbox tombstones..."
mkdir -p "${OUT}/dropbox"
SINCE_MS=$(( $(date +%s) * 1000 - 24 * 60 * 60 * 1000 ))
adb -s "${SERIAL}" shell "su -c 'for tag in SYSTEM_BOOT SYSTEM_RESTART SYSTEM_TOMBSTONE \
    SYSTEM_LAST_KMSG SYSTEM_RECOVERY_LOG system_server_watchdog system_server_anr \
    system_server_crash system_server_lowmem system_app_anr system_app_crash \
    system_app_native_crash system_app_wtf data_app_anr data_app_crash \
    data_app_native_crash data_app_wtf kernel_panic; do
    for f in /data/system/dropbox/\${tag}@*; do
        [ -e \"\$f\" ] || continue
        ms=\$(echo \$(basename \"\$f\") | sed \"s/^\${tag}@//;s/\\..*//\")
        if [ \"\$ms\" -ge ${SINCE_MS} ] 2>/dev/null; then
            echo \"=== \$f ===\"
            cat \"\$f\"
            echo
            echo
        fi
    done
done'" > "${OUT}/dropbox-all.txt" 2>&1
adb -s "${SERIAL}" shell "su -c 'dumpsys dropbox --print'" > "${OUT}/dumpsys-dropbox.txt" 2>&1

# 4. Current-boot kernel log. The bootloader often emits a "previous
#    boot ended in X" line in the first ~50 lines.
echo "  -> dmesg (current boot)..."
adb -s "${SERIAL}" shell "su -c 'dmesg -T'" > "${OUT}/dmesg-current.txt" 2>&1

# 5. Current-boot logcat. Only useful if the cause is RECURRING
#    in this new boot (which a lot of system_server crash loops are).
echo "  -> logcat (current boot only)..."
adb -s "${SERIAL}" shell logcat -b crash  -d -v threadtime > "${OUT}/logcat-crash.txt"  2>&1 || true
adb -s "${SERIAL}" shell logcat -b events -d -v threadtime > "${OUT}/logcat-events.txt" 2>&1 || true
adb -s "${SERIAL}" shell logcat -b system -d -v threadtime > "${OUT}/logcat-system.txt" 2>&1 || true
adb -s "${SERIAL}" shell logcat -b main   -d -v threadtime > "${OUT}/logcat-main.txt"   2>&1 || true

# 6. The headline — print the boot reason inline so you don't have
#    to open any files to see if it was an easy answer.
echo
echo "── boot reason summary ────────────────────────"
grep -E "^(sys\.boot\.reason|sys\.boot\.reason\.last|ro\.boot\.bootreason)=" \
    "${OUT}/bootreason.txt" || true
echo "──────────────────────────────────────────────"
echo
echo "Done. ${OUT}/"
echo
echo "Read in this order:"
echo "  1. ${OUT}/bootreason.txt          # one-line reboot reason"
echo "  2. ${OUT}/dropbox-all.txt         # last 24h of system crashes"
echo "  3. ${OUT}/pstore.txt              # previous boot's kernel log"
echo "  4. ${OUT}/dmesg-current.txt       # current boot, look at top"
