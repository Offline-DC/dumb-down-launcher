#!/usr/bin/env bash
# storage_audit.sh — Mac-side storage audit for a connected dumb phone.
#
# Run from your Mac with the phone plugged in via USB:
#   ./scripts/storage_audit.sh
#
# Nothing is pushed to the device. Each section pipes a shell snippet into
# `adb shell su -c sh` and streams the output back. Sorting and formatting
# happen on the Mac (the phone's toybox `sort` lacks -h, so we do that here).
#
# Requires:
#   - adb on PATH                 brew install android-platform-tools
#   - phone connected, USB debug authorised
#   - phone has root (Magisk superuser enabled)
#
# Output: ./storage_audit-<model>-<serial>-<timestamp>.txt in the current dir.
# Read-only: no rm, no find -delete, no writes anywhere on device.

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

DEVICES=()
while IFS= read -r _line; do
  [[ -n "$_line" ]] && DEVICES+=("$_line")
done < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')

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
The audit needs root. Open Magisk on the phone, make sure Superuser is enabled, then re-run.
EOF
  exit 1
fi

MODEL="$(adb shell getprop ro.product.model | tr -d '\r' | tr ' /' '__')"
FP="$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
TS="$(date +%Y%m%d-%H%M%S)"
LOG="./storage_audit-${MODEL}-${SERIAL}-${TS}.txt"
: >"$LOG"

# ============================================================================
# helpers
# ============================================================================

# section <title>  — reads a shell snippet from stdin, runs it on the phone
# as root, appends raw output to $LOG.
section() {
  local title="$1"
  {
    echo
    echo "================================================================"
    echo "$title"
    echo "================================================================"
  } >>"$LOG"
  echo "  $title" >&2
  adb shell "su -c sh" >>"$LOG" 2>&1
}

# section_topsize <title> <N>  — reads a shell snippet from stdin that emits
# "<KB><TAB><path>" lines (e.g. `du -kx X`). Sorts on the Mac (so we get
# robust numeric sort), takes top N, formats human-readable, appends to $LOG.
section_topsize() {
  local title="$1"
  local n="$2"
  {
    echo
    echo "================================================================"
    echo "$title"
    echo "================================================================"
  } >>"$LOG"
  echo "  $title" >&2
  adb shell "su -c sh" 2>/dev/null \
    | sort -nrk1 \
    | head -n "$n" \
    | awk '{
        s=$1; $1=""; sub(/^[ \t]+/,"")
        if (s<1024)         printf "%6d K   %s\n", s, $0
        else if (s<1048576) printf "%6.1f M   %s\n", s/1024, $0
        else                printf "%6.2f G   %s\n", s/1048576, $0
      }' >>"$LOG"
}

# ---- log header ----
{
  echo "storage_audit.sh — $(date)"
  echo "host:    $(uname -a)"
  echo "device:  $SERIAL"
  echo "model:   $MODEL"
  echo "build:   $FP"
} >>"$LOG"

echo "device:   $SERIAL ($MODEL)" >&2
echo "logfile:  $LOG" >&2
echo "running:  takes 1–3 minutes." >&2

# ============================================================================
# audit
# ============================================================================

section "1. Filesystem usage (df)" <<'PHONE'
df -h 2>/dev/null || df
PHONE

section_topsize "2. /data top-level dirs" 40 <<'PHONE'
du -kx /data/* 2>/dev/null
PHONE

section_topsize "3. /sdcard top-level dirs" 40 <<'PHONE'
du -kx /sdcard/* 2>/dev/null
PHONE

section_topsize "4. Top 40 apps by /data/data size" 40 <<'PHONE'
du -kx /data/data/* 2>/dev/null
PHONE

section_topsize "5. Top 30 apps by /data/media/0/Android/data (scoped external)" 30 <<'PHONE'
du -kx /data/media/0/Android/data/* 2>/dev/null
PHONE

section_topsize "6. Top 30 dirs under /data/media/0/Android/media" 30 <<'PHONE'
du -kx /data/media/0/Android/media/* 2>/dev/null
PHONE

# -------------- WhatsApp --------------

section "7a. WhatsApp totals" <<'PHONE'
echo "package total:"
du -shx /data/data/com.whatsapp 2>/dev/null
echo
echo "external media root:"
du -shx /data/media/0/Android/media/com.whatsapp 2>/dev/null
PHONE

section_topsize "7b. WhatsApp /data/data subdirs" 30 <<'PHONE'
du -kx /data/data/com.whatsapp/* 2>/dev/null
du -kx /data/data/com.whatsapp/files/* 2>/dev/null
du -kx /data/data/com.whatsapp/databases/* 2>/dev/null
PHONE

section_topsize "7c. WhatsApp Media subdirs" 30 <<'PHONE'
WA_MEDIA="/sdcard/Android/media/com.whatsapp/WhatsApp/Media"
[ -d "$WA_MEDIA" ] && du -kx "$WA_MEDIA"/* 2>/dev/null
PHONE

section "7d. WhatsApp media file age distribution" <<'PHONE'
WA_MEDIA="/sdcard/Android/media/com.whatsapp/WhatsApp/Media"
[ -d "$WA_MEDIA" ] || { echo "(no $WA_MEDIA)"; exit 0; }
echo "file counts and total bytes older than N days:"
for d in 0 1 3 7 30; do
  c=$(find "$WA_MEDIA" -type f -mtime +$d 2>/dev/null | wc -l)
  # toybox du -cb may not work; sum sizes via wc -c
  b=$(find "$WA_MEDIA" -type f -mtime +$d 2>/dev/null -exec wc -c {} + 2>/dev/null | tail -1 | awk '{print $1}')
  echo "  > $d days: $c files, ${b:-?} bytes"
done
PHONE

# -------------- OpenBubbles --------------

section "8a. OpenBubbles totals" <<'PHONE'
for pkg in com.openbubbles.messaging co.openbubbles.messaging com.openbubbles app.openbubbles; do
  d="/data/data/$pkg"
  if [ -d "$d" ]; then
    echo "$pkg:"
    du -shx "$d" 2>/dev/null
    [ -d "$d/app_flutter/attachments" ] && du -shx "$d/app_flutter/attachments" 2>/dev/null
    [ -d "$d/app_flutter" ] && du -shx "$d/app_flutter" 2>/dev/null
    [ -d "$d/cache" ] && du -shx "$d/cache" 2>/dev/null
    [ -d "$d/files" ] && du -shx "$d/files" 2>/dev/null
    [ -d "$d/databases" ] && du -shx "$d/databases" 2>/dev/null
    echo "attachments file count: $(find "$d/app_flutter/attachments" -type f 2>/dev/null | wc -l)"
  fi
done
PHONE

section_topsize "8b. OpenBubbles subdir breakdown" 30 <<'PHONE'
for pkg in com.openbubbles.messaging co.openbubbles.messaging com.openbubbles app.openbubbles; do
  d="/data/data/$pkg"
  [ -d "$d" ] || continue
  du -kx "$d"/* 2>/dev/null
  [ -d "$d/app_flutter" ] && du -kx "$d/app_flutter"/* 2>/dev/null
done
PHONE

# -------------- Spotify --------------

section "9a. Spotify totals" <<'PHONE'
for pkg in com.spotify.music com.bnyro.spotify; do
  d="/data/data/$pkg"
  if [ -d "$d" ]; then
    echo "$pkg:"
    du -shx "$d" 2>/dev/null
  fi
done
echo
echo "external:"
find /sdcard /data/media/0 -maxdepth 6 -type d -iname "*spotify*" 2>/dev/null | while read d; do
  du -shx "$d" 2>/dev/null
done
PHONE

section_topsize "9b. Spotify subdir breakdown" 30 <<'PHONE'
for pkg in com.spotify.music com.bnyro.spotify; do
  d="/data/data/$pkg"
  [ -d "$d" ] || continue
  du -kx "$d"/* 2>/dev/null
  for sub in cache code_cache files; do
    [ -d "$d/$sub" ] && du -kx "$d/$sub"/* 2>/dev/null
  done
done
PHONE

# -------------- Apple Music --------------

section "10a. Apple Music totals" <<'PHONE'
for pkg in com.apple.android.music com.apple.music; do
  d="/data/data/$pkg"
  if [ -d "$d" ]; then
    echo "$pkg:"
    du -shx "$d" 2>/dev/null
  fi
done
PHONE

section_topsize "10b. Apple Music subdir breakdown (drills into the 49M)" 60 <<'PHONE'
for pkg in com.apple.android.music com.apple.music; do
  d="/data/data/$pkg"
  [ -d "$d" ] || continue
  du -kx "$d"/* 2>/dev/null
  for sub in cache code_cache files app_webview no_backup databases shared_prefs; do
    [ -d "$d/$sub" ] && du -kx "$d/$sub"/* 2>/dev/null
  done
done
PHONE

section "10c. Apple Music large files (>1MB anywhere under its dirs)" <<'PHONE'
for pkg in com.apple.android.music com.apple.music; do
  d="/data/data/$pkg"
  [ -d "$d" ] || continue
  find "$d" -type f -size +1M 2>/dev/null | head -50 | while read f; do
    du -sh "$f" 2>/dev/null
  done
done
PHONE

# -------------- AntennaPod --------------

section "11a. AntennaPod totals" <<'PHONE'
for pkg in de.danoeh.antennapod de.danoeh.antennapod.debug; do
  d="/data/data/$pkg"
  if [ -d "$d" ]; then
    echo "$pkg (private):"
    du -shx "$d" 2>/dev/null
  fi
done
echo
echo "external:"
find /sdcard /data/media/0 -maxdepth 6 -type d \( -iname "*antennapod*" -o -iname "*podcast*" \) 2>/dev/null | while read d; do
  du -shx "$d" 2>/dev/null
done
PHONE

section_topsize "11b. AntennaPod subdir breakdown" 40 <<'PHONE'
for pkg in de.danoeh.antennapod de.danoeh.antennapod.debug; do
  d="/data/data/$pkg"
  [ -d "$d" ] || continue
  du -kx "$d"/* 2>/dev/null
  for sub in cache code_cache files no_backup; do
    [ -d "$d/$sub" ] && du -kx "$d/$sub"/* 2>/dev/null
  done
done
# external dir contents
for d in /sdcard/Android/data/de.danoeh.antennapod /data/media/0/Android/data/de.danoeh.antennapod; do
  [ -d "$d" ] && du -kx "$d"/* 2>/dev/null
done
PHONE

section "11c. AntennaPod episode files (.mp3/.opus/.m4a/.ogg over 1MB)" <<'PHONE'
for base in /data/data/de.danoeh.antennapod /data/media/0/Android/data/de.danoeh.antennapod /sdcard/Android/data/de.danoeh.antennapod /sdcard/Podcasts; do
  [ -d "$base" ] || continue
  echo "---- under $base ----"
  find "$base" -type f \( -iname "*.mp3" -o -iname "*.opus" -o -iname "*.m4a" -o -iname "*.ogg" -o -iname "*.aac" \) -size +1M 2>/dev/null | head -30 | while read f; do
    du -sh "$f" 2>/dev/null
  done
done
PHONE

# -------------- Chrome / WebView --------------

section_topsize "12. Chrome + WebView subdir breakdown" 30 <<'PHONE'
for pkg in com.android.chrome com.google.android.webview com.android.webview org.chromium.chrome org.chromium.webview_shell; do
  d="/data/data/$pkg"
  [ -d "$d" ] || continue
  du -kx "$d"/* 2>/dev/null
  for sub in cache code_cache app_chrome app_webview; do
    [ -d "$d/$sub" ] && du -kx "$d/$sub"/* 2>/dev/null
  done
done
PHONE

# -------------- system --------------

section "13a. App cache totals" <<'PHONE'
total=0
for d in /data/data/*/cache /data/data/*/code_cache; do
  [ -d "$d" ] || continue
  s=$(du -sx "$d" 2>/dev/null | awk '{print $1}')
  total=$((total + s))
done
echo "sum of /data/data/*/cache + /data/data/*/code_cache: ${total} KB ($((total / 1024)) MB)"
PHONE

section_topsize "13b. Biggest individual app caches" 30 <<'PHONE'
for d in /data/data/*/cache /data/data/*/code_cache; do
  [ -d "$d" ] || continue
  du -kx "$d" 2>/dev/null
done
PHONE

section "13c. Dalvik + /data/app sizes" <<'PHONE'
du -shx /data/dalvik-cache 2>/dev/null
echo
du -shx /data/app 2>/dev/null
PHONE

section_topsize "13d. Top installed APKs under /data/app" 20 <<'PHONE'
du -kx /data/app/* 2>/dev/null
PHONE

section "13e. Logs, tombstones, ANR, dropbox, common user dirs" <<'PHONE'
echo "---- log/crash dirs ----"
du -shx /data/anr /data/tombstones /data/system/dropbox /data/log /data/misc/logd 2>/dev/null
echo
echo "---- user-facing dirs ----"
for d in /sdcard/Download /sdcard/DCIM /sdcard/Movies /sdcard/Music /sdcard/Pictures /sdcard/Podcasts /sdcard/Documents /sdcard/Recordings; do
  [ -d "$d" ] && du -shx "$d" 2>/dev/null
done
PHONE

# -------------- swap --------------

section "14. Swap (planning to remove /data/swapfile, keep zram)" <<'PHONE'
echo "---- /data/swapfile ----"
ls -lh /data/swapfile 2>/dev/null || echo "(no /data/swapfile)"
echo
echo "---- /proc/swaps ----"
cat /proc/swaps 2>/dev/null
echo
echo "---- memory ----"
free -h 2>/dev/null || cat /proc/meminfo 2>/dev/null | head -10
PHONE

# -------------- biggest individual files anywhere --------------

section_topsize "15. 40 biggest individual files anywhere on /data and /sdcard" 40 <<'PHONE'
find /data /sdcard -xdev -type f -size +10M 2>/dev/null | while read f; do
  du -kx "$f" 2>/dev/null
done
PHONE

# -------------- packages --------------

section "16. Installed user packages" <<'PHONE'
pm list packages -3 2>/dev/null | sort
PHONE

section "17. Installed system packages relevant to media/messaging" <<'PHONE'
pm list packages 2>/dev/null | grep -iE "whatsapp|openbubbles|spotify|apple|antenna|chrome|webview|messaging|music|photo|gallery|drive|dropbox|telegram|signal|messenger|molly|podlp" | sort
PHONE

# -------------- launcher state --------------

section "18a. dumb-down-launcher totals" <<'PHONE'
LAUNCHER=/data/data/com.offlineinc.dumbdownlauncher
if [ -d "$LAUNCHER" ]; then
  du -shx "$LAUNCHER" 2>/dev/null
fi
PHONE

section_topsize "18b. dumb-down-launcher subdir breakdown" 20 <<'PHONE'
LAUNCHER=/data/data/com.offlineinc.dumbdownlauncher
[ -d "$LAUNCHER" ] && du -kx "$LAUNCHER"/* 2>/dev/null
PHONE

section "18c. dumb-down-launcher migrations prefs" <<'PHONE'
LAUNCHER=/data/data/com.offlineinc.dumbdownlauncher
[ -f "$LAUNCHER/shared_prefs/migrations.xml" ] && cat "$LAUNCHER/shared_prefs/migrations.xml"
PHONE

echo "" >>"$LOG"
echo "DONE — $(date)" >>"$LOG"

LINES=$(wc -l <"$LOG" | tr -d ' ')
BYTES=$(wc -c <"$LOG" | tr -d ' ')
echo >&2
echo "wrote:    $LOG  ($LINES lines, $BYTES bytes)" >&2
echo "paste contents back to Claude." >&2
