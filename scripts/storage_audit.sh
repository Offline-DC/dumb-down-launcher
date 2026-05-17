#!/usr/bin/env bash
# storage_audit.sh — Mac-side storage audit for a connected dumb phone.
#
# Run it directly on your Mac with a phone plugged in via USB:
#   ./scripts/storage_audit.sh
#
# It does not push anything to the device. Every command is sent inline via
# `adb shell su -c sh` and stdout streams straight back into a log file on
# the Mac. The audit is strictly read-only.
#
# Requires:
#   - adb on PATH                 brew install android-platform-tools
#   - phone connected, USB debug authorised
#   - phone has root (Magisk superuser must be enabled)
#
# Output: ./storage_audit-<model>-<serial>-<timestamp>.txt in the current dir.

set -euo pipefail

# ---- preflight ----
if ! command -v adb >/dev/null 2>&1; then
  cat >&2 <<'EOF'
error: adb is not on your PATH.
  install:  brew install android-platform-tools
  verify:   adb version
EOF
  exit 1
fi

mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [[ ${#DEVICES[@]} -eq 0 ]]; then
  echo "error: no devices connected. plug the phone in, accept the USB-debug prompt, then re-run." >&2
  adb devices >&2
  exit 1
fi
if [[ ${#DEVICES[@]} -gt 1 ]]; then
  if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    echo "error: multiple devices connected; set ANDROID_SERIAL=<serial> and re-run." >&2
    adb devices >&2
    exit 1
  fi
  SERIAL="$ANDROID_SERIAL"
else
  SERIAL="${DEVICES[0]}"
fi
export ANDROID_SERIAL="$SERIAL"

if ! adb shell 'su -c id' 2>/dev/null | grep -q 'uid=0'; then
  cat >&2 <<'EOF'
error: 'su -c id' did not return uid=0 on the device.
The audit needs root (the launcher already uses root via Magisk).
Open Magisk on the phone, make sure Superuser is enabled, then re-run.
EOF
  exit 1
fi

MODEL="$(adb shell getprop ro.product.model | tr -d '\r' | tr ' /' '__')"
FP="$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
TS="$(date +%Y%m%d-%H%M%S)"
LOG="./storage_audit-${MODEL}-${SERIAL}-${TS}.txt"
: >"$LOG"

# ---- helpers ----
# section <title>  — reads a shell snippet from stdin, runs it on the phone
# as root, and appends labelled output to $LOG. Progress messages go to stderr.
section() {
  local title="$1"
  {
    echo
    echo "================================================================"
    echo "$title"
    echo "================================================================"
  } >>"$LOG"
  echo "  $title" >&2
  # `su -c sh` reads a shell script from stdin and executes it as root.
  # Quoting heredoc tag with single quotes prevents Mac-side $-expansion.
  adb shell "su -c sh" >>"$LOG" 2>&1
}

# ---- header in the log ----
{
  echo "storage_audit.sh — $(date)"
  echo "host:    $(uname -a)"
  echo "device:  $SERIAL"
  echo "model:   $MODEL"
  echo "build:   $FP"
} >>"$LOG"

echo "device:   $SERIAL ($MODEL)" >&2
echo "logfile:  $LOG" >&2
echo "running:  this can take 1–3 minutes (du walks). progress below." >&2

# ===============================================================
# audit sections
# ===============================================================

section "1. Filesystem usage (df)" <<'PHONE'
df -h 2>/dev/null || df
PHONE

section "2. /data partition top-level (which dirs are heavy)" <<'PHONE'
du -shx /data/* 2>/dev/null | sort -hr | head -40
PHONE

section "3. /sdcard top-level (user-visible storage)" <<'PHONE'
du -shx /sdcard/* 2>/dev/null | sort -hr | head -40
PHONE

section "4. Top 30 apps by /data/data size (private app storage)" <<'PHONE'
du -shx /data/data/* 2>/dev/null | sort -hr | head -30
PHONE

section "5. Top 30 apps by /data/media/0/Android/data (scoped external)" <<'PHONE'
du -shx /data/media/0/Android/data/* 2>/dev/null | sort -hr | head -30
PHONE

section "6. Top 30 dirs under /data/media/0/Android/media (media-scoped external)" <<'PHONE'
du -shx /data/media/0/Android/media/* 2>/dev/null | sort -hr | head -30
PHONE

section "7. WhatsApp" <<'PHONE'
echo "---- private /data/data/com.whatsapp ----"
du -shx /data/data/com.whatsapp 2>/dev/null
du -shx /data/data/com.whatsapp/* 2>/dev/null | sort -hr | head -20

echo
echo "---- any WhatsApp folder on /sdcard or /data/media/0 ----"
find /sdcard /data/media/0 -maxdepth 6 -type d -iname "WhatsApp*" 2>/dev/null | while read d; do
  du -shx "$d" 2>/dev/null
done

echo
WA_MEDIA="/sdcard/Android/media/com.whatsapp/WhatsApp/Media"
if [ -d "$WA_MEDIA" ]; then
  echo "---- per-mediatype breakdown ($WA_MEDIA) ----"
  du -shx "$WA_MEDIA"/* 2>/dev/null | sort -hr
  echo
  echo "file counts older than N days under $WA_MEDIA:"
  for d in 1 3 7; do
    c=$(find "$WA_MEDIA" -type f -mtime +$d 2>/dev/null | wc -l)
    s=$(find "$WA_MEDIA" -type f -mtime +$d -exec du -cb {} + 2>/dev/null | tail -1 | awk '{print $1}')
    echo "  > $d days: $c files, $s bytes"
  done
fi
PHONE

section "8. OpenBubbles" <<'PHONE'
for pkg in com.openbubbles.messaging co.openbubbles.messaging com.openbubbles app.openbubbles; do
  d="/data/data/$pkg"
  if [ -d "$d" ]; then
    echo "---- $d ----"
    du -shx "$d" 2>/dev/null
    du -shx "$d"/* 2>/dev/null | sort -hr | head -20
    if [ -d "$d/app_flutter/attachments" ]; then
      echo "attachments tree size:"
      du -shx "$d/app_flutter/attachments" 2>/dev/null
      echo "attachments file count: $(find "$d/app_flutter/attachments" -type f 2>/dev/null | wc -l)"
    fi
  fi
done
PHONE

section "9. Spotify" <<'PHONE'
for pkg in com.spotify.music com.bnyro.spotify; do
  d="/data/data/$pkg"
  if [ -d "$d" ]; then
    echo "---- $d ----"
    du -shx "$d" 2>/dev/null
    du -shx "$d"/* 2>/dev/null | sort -hr | head -15
    find "$d" -maxdepth 4 -type d \( -iname "*cache*" -o -iname "*offline*" -o -iname "*storage*" \) 2>/dev/null | while read sd; do
      du -shx "$sd" 2>/dev/null
    done
  fi
done
echo
echo "---- Spotify on external storage ----"
find /sdcard /data/media/0 -maxdepth 6 -type d -iname "*spotify*" 2>/dev/null | while read d; do
  du -shx "$d" 2>/dev/null
done
PHONE

section "10. Apple Music" <<'PHONE'
for pkg in com.apple.android.music com.apple.music; do
  d="/data/data/$pkg"
  if [ -d "$d" ]; then
    echo "---- $d ----"
    du -shx "$d" 2>/dev/null
    du -shx "$d"/* 2>/dev/null | sort -hr | head -15
    find "$d" -maxdepth 5 -type d \( -iname "*cache*" -o -iname "*download*" -o -iname "*media*" -o -iname "*subscription*" \) 2>/dev/null | while read sd; do
      du -shx "$sd" 2>/dev/null
    done
  fi
done
PHONE

section "11. AntennaPod" <<'PHONE'
for pkg in de.danoeh.antennapod de.danoeh.antennapod.debug; do
  d="/data/data/$pkg"
  if [ -d "$d" ]; then
    echo "---- $d (private) ----"
    du -shx "$d" 2>/dev/null
    du -shx "$d"/* 2>/dev/null | sort -hr | head -10
  fi
done
echo
echo "---- AntennaPod external (episodes usually live here) ----"
find /sdcard /data/media/0 -maxdepth 6 -type d \( -iname "*antennapod*" -o -iname "*podcast*" \) 2>/dev/null | while read d; do
  du -shx "$d" 2>/dev/null
done
PHONE

section "12. Chrome + WebView + other browsers" <<'PHONE'
for pkg in com.android.chrome com.google.android.webview com.android.webview org.chromium.webview_shell com.brave.browser org.mozilla.firefox; do
  d="/data/data/$pkg"
  if [ -d "$d" ]; then
    echo "---- $d ----"
    du -shx "$d" 2>/dev/null
    du -shx "$d/cache" "$d/code_cache" "$d/app_chrome" "$d/app_webview" 2>/dev/null
  fi
done
PHONE

section "13. System caches & logs" <<'PHONE'
echo "---- total app cache (sum of /data/data/*/cache and /data/data/*/code_cache) ----"
total=0
for d in /data/data/*/cache /data/data/*/code_cache; do
  [ -d "$d" ] || continue
  s=$(du -sx "$d" 2>/dev/null | awk '{print $1}')
  total=$((total + s))
done
echo "total app cache: ${total} KB"

echo
echo "---- biggest individual app caches ----"
for d in /data/data/*/cache /data/data/*/code_cache; do
  [ -d "$d" ] || continue
  du -shx "$d" 2>/dev/null
done | sort -hr | head -20

echo
echo "---- dalvik / ART cache + /data/app ----"
du -shx /data/dalvik-cache 2>/dev/null
du -shx /data/app/* 2>/dev/null | sort -hr | head -10

echo
echo "---- logs, tombstones, ANR, dropbox ----"
du -shx /data/anr /data/tombstones /data/system/dropbox /data/log /data/misc/logd 2>/dev/null

echo
echo "---- common user dirs ----"
for d in /sdcard/Download /sdcard/DCIM /sdcard/Movies /sdcard/Music /sdcard/Pictures /sdcard/Podcasts; do
  [ -d "$d" ] && du -shx "$d" 2>/dev/null
done
PHONE

section "14. Swap (planning to remove this)" <<'PHONE'
ls -lh /data/swapfile 2>/dev/null
cat /proc/swaps 2>/dev/null
free -h 2>/dev/null || cat /proc/meminfo 2>/dev/null | head -10
PHONE

section "15. 30 biggest individual files on the device" <<'PHONE'
find /data /sdcard -xdev -type f -size +20M 2>/dev/null | while read f; do
  du -sh "$f" 2>/dev/null
done | sort -hr | head -30
PHONE

section "16. Installed user packages" <<'PHONE'
pm list packages -3 2>/dev/null | sort
PHONE

section "17. Installed system packages relevant to media/messaging" <<'PHONE'
pm list packages 2>/dev/null | grep -iE "whatsapp|openbubbles|spotify|apple|antenna|chrome|webview|messaging|music|photo|gallery|drive|dropbox|telegram|signal|messenger" | sort
PHONE

section "18. dumb-down-launcher state" <<'PHONE'
LAUNCHER=/data/data/com.offlineinc.dumbdownlauncher
if [ -d "$LAUNCHER" ]; then
  du -shx "$LAUNCHER" 2>/dev/null
  du -shx "$LAUNCHER"/* 2>/dev/null | sort -hr
  echo
  echo "---- migrations prefs ----"
  cat "$LAUNCHER/shared_prefs/migrations.xml" 2>/dev/null
fi
PHONE

echo "" >>"$LOG"
echo "DONE — $(date)" >>"$LOG"

LINES=$(wc -l <"$LOG" | tr -d ' ')
BYTES=$(wc -c <"$LOG" | tr -d ' ')
echo >&2
echo "wrote:    $LOG  ($LINES lines, $BYTES bytes)" >&2
echo "Paste that file's contents back to Claude." >&2
