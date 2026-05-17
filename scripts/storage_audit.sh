#!/system/bin/sh
# storage_audit.sh — diagnostic dump for figuring out where the 8 GB went.
#
# How to run:
#   adb push storage_audit.sh /data/local/tmp/
#   adb shell "su -c 'sh /data/local/tmp/storage_audit.sh' > /sdcard/storage_audit.txt 2>&1"
#   adb pull /sdcard/storage_audit.txt ./
#
# Or, from a connected phone with adb root:
#   adb shell "sh /data/local/tmp/storage_audit.sh" | tee storage_audit.txt
#
# Output is plain text. Paste the whole file back to Claude.
# Nothing here writes or deletes anything — read-only audit.

set -u
PATH=/system/bin:/system/xbin:/sbin:/vendor/bin:$PATH

hr() { echo; echo "================================================================"; echo "$1"; echo "================================================================"; }
sub() { echo; echo "---- $1 ----"; }

echo "storage_audit.sh — $(date) on $(getprop ro.product.model) ($(getprop ro.build.fingerprint))"
echo "user: $(id 2>/dev/null)"

# -------- 1. Big picture --------
hr "1. Filesystem usage (df)"
df -h 2>/dev/null || df

hr "2. /data partition top-level (which dirs are heavy)"
du -shx /data/* 2>/dev/null | sort -hr | head -40

hr "3. /sdcard top-level (user-visible storage)"
du -shx /sdcard/* 2>/dev/null | sort -hr | head -40

# -------- 2. Per-app /data/data sizes --------
hr "4. Top 30 apps by /data/data size (private app storage)"
du -shx /data/data/* 2>/dev/null | sort -hr | head -30

hr "5. Top 30 apps by /data/media/0/Android/data (scoped external)"
du -shx /data/media/0/Android/data/* 2>/dev/null | sort -hr | head -30

hr "6. Top 30 dirs under /data/media/0/Android/media (media-scoped external)"
du -shx /data/media/0/Android/media/* 2>/dev/null | sort -hr | head -30

# -------- 3. Known suspects: WhatsApp --------
hr "7. WhatsApp"
sub "Private /data/data/com.whatsapp"
du -shx /data/data/com.whatsapp 2>/dev/null
du -shx /data/data/com.whatsapp/* 2>/dev/null | sort -hr | head -20
sub "Media on /sdcard (any WhatsApp folder anywhere)"
find /sdcard /data/media/0 -maxdepth 6 -type d -iname "WhatsApp*" 2>/dev/null | while read d; do
  du -shx "$d" 2>/dev/null
done
sub "Per-mediatype breakdown (scoped path used by current WhatsApp)"
WA_MEDIA="/sdcard/Android/media/com.whatsapp/WhatsApp/Media"
if [ -d "$WA_MEDIA" ]; then
  du -shx "$WA_MEDIA"/* 2>/dev/null | sort -hr
  echo
  echo "File counts older than 1 / 3 / 7 days under $WA_MEDIA:"
  for d in 1 3 7; do
    c=$(find "$WA_MEDIA" -type f -mtime +$d 2>/dev/null | wc -l)
    s=$(find "$WA_MEDIA" -type f -mtime +$d -exec du -cb {} + 2>/dev/null | tail -1 | awk '{print $1}')
    echo "  > $d days: $c files, $s bytes"
  done
fi

# -------- 4. OpenBubbles --------
hr "8. OpenBubbles"
for pkg in com.openbubbles.messaging co.openbubbles.messaging com.openbubbles app.openbubbles; do
  d="/data/data/$pkg"
  if [ -d "$d" ]; then
    sub "$d"
    du -shx "$d" 2>/dev/null
    du -shx "$d"/* 2>/dev/null | sort -hr | head -20
    if [ -d "$d/app_flutter/attachments" ]; then
      echo "attachments tree size:"
      du -shx "$d/app_flutter/attachments" 2>/dev/null
      echo "attachments file count: $(find "$d/app_flutter/attachments" -type f 2>/dev/null | wc -l)"
    fi
  fi
done

# -------- 5. Spotify --------
hr "9. Spotify"
for pkg in com.spotify.music com.bnyro.spotify; do
  d="/data/data/$pkg"
  if [ -d "$d" ]; then
    sub "$d"
    du -shx "$d" 2>/dev/null
    du -shx "$d"/* 2>/dev/null | sort -hr | head -15
    # Spotify offline cache typically under files/spotifycache or cache
    find "$d" -maxdepth 4 -type d \( -iname "*cache*" -o -iname "*offline*" -o -iname "*storage*" \) 2>/dev/null | while read sd; do
      du -shx "$sd" 2>/dev/null
    done
  fi
done
sub "Spotify on external storage"
find /sdcard /data/media/0 -maxdepth 6 -type d -iname "*spotify*" 2>/dev/null | while read d; do
  du -shx "$d" 2>/dev/null
done

# -------- 6. Apple Music --------
hr "10. Apple Music"
for pkg in com.apple.android.music com.apple.music; do
  d="/data/data/$pkg"
  if [ -d "$d" ]; then
    sub "$d"
    du -shx "$d" 2>/dev/null
    du -shx "$d"/* 2>/dev/null | sort -hr | head -15
    find "$d" -maxdepth 5 -type d \( -iname "*cache*" -o -iname "*download*" -o -iname "*media*" -o -iname "*subscription*" \) 2>/dev/null | while read sd; do
      du -shx "$sd" 2>/dev/null
    done
  fi
done

# -------- 7. AntennaPod --------
hr "11. AntennaPod"
for pkg in de.danoeh.antennapod de.danoeh.antennapod.debug; do
  d="/data/data/$pkg"
  if [ -d "$d" ]; then
    sub "$d (private)"
    du -shx "$d" 2>/dev/null
    du -shx "$d"/* 2>/dev/null | sort -hr | head -10
  fi
done
sub "AntennaPod external (episodes usually live here)"
find /sdcard /data/media/0 -maxdepth 6 -type d -iname "*antennapod*" -o -iname "*podcast*" 2>/dev/null | while read d; do
  du -shx "$d" 2>/dev/null
done

# -------- 8. Chrome / WebView / browsers --------
hr "12. Chrome + WebView + other browsers"
for pkg in com.android.chrome com.google.android.webview com.android.webview org.chromium.webview_shell com.brave.browser org.mozilla.firefox; do
  d="/data/data/$pkg"
  if [ -d "$d" ]; then
    sub "$d"
    du -shx "$d" 2>/dev/null
    du -shx "$d/cache" "$d/code_cache" "$d/app_chrome" "$d/app_webview" 2>/dev/null
  fi
done

# -------- 9. System / OS-level --------
hr "13. System caches & logs (often forgotten)"
sub "Per-app cache total under /data/data/*/cache + code_cache"
total=0
for d in /data/data/*/cache /data/data/*/code_cache; do
  [ -d "$d" ] || continue
  s=$(du -sx "$d" 2>/dev/null | awk '{print $1}')
  total=$((total + s))
done
echo "total app cache: ${total} KB"
sub "Biggest individual app caches"
for d in /data/data/*/cache /data/data/*/code_cache; do
  [ -d "$d" ] || continue
  du -shx "$d" 2>/dev/null
done | sort -hr | head -20
sub "Dalvik / ART cache"
du -shx /data/dalvik-cache 2>/dev/null
du -shx /data/app/* 2>/dev/null | sort -hr | head -10
sub "Logs, tombstones, anr, dropbox"
du -shx /data/anr /data/tombstones /data/system/dropbox /data/log /data/misc/logd 2>/dev/null
sub "Downloads + DCIM + Movies + Music + Pictures"
for d in /sdcard/Download /sdcard/DCIM /sdcard/Movies /sdcard/Music /sdcard/Pictures /sdcard/Podcasts; do
  [ -d "$d" ] && du -shx "$d" 2>/dev/null
done

# -------- 10. Swap --------
hr "14. Swap (planning to remove this)"
ls -lh /data/swapfile 2>/dev/null
cat /proc/swaps 2>/dev/null
free -h 2>/dev/null || cat /proc/meminfo 2>/dev/null | head -10

# -------- 11. Largest files anywhere --------
hr "15. 30 biggest individual files on the device"
find /data /sdcard -xdev -type f -size +20M 2>/dev/null | xargs -I{} du -sh "{}" 2>/dev/null | sort -hr | head -30

# -------- 12. Installed packages (helps Claude know what's actually here) --------
hr "16. Installed user packages"
pm list packages -3 2>/dev/null | sort

hr "17. Installed system packages relevant to media/messaging"
pm list packages 2>/dev/null | grep -iE "whatsapp|openbubbles|spotify|apple|antenna|chrome|webview|messaging|music|photo|gallery|drive|dropbox|telegram|signal|messenger" | sort

# -------- 13. The launcher's own state --------
hr "18. dumb-down-launcher state"
LAUNCHER=/data/data/com.offlineinc.dumbdownlauncher
if [ -d "$LAUNCHER" ]; then
  du -shx "$LAUNCHER" 2>/dev/null
  du -shx "$LAUNCHER"/* 2>/dev/null | sort -hr
  echo "migrations prefs:"
  cat "$LAUNCHER/shared_prefs/migrations.xml" 2>/dev/null
fi

hr "DONE"
echo "Run finished $(date)."
