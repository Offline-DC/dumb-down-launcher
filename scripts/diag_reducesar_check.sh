#!/usr/bin/env bash
#
# diag_reducesar_check.sh
#
# Single-shot verification that the launcher's `disable_reducesar_v2`
# migration actually achieved the goal on a connected device:
#
#   1.  reducesar package is installed (otherwise this device isn't a
#       Verizon variant and the migration is a no-op anyway).
#   2.  MtkCommonSarService is recorded as disabled in PackageManager.
#   3.  No services are currently running inside reducesar's process.
#   4.  No GnssStatus listener is registered for reducesar.
#   5.  Deep doze actually engaged at some point — `deep-idle` shows up
#       in `dumpsys deviceidle` Idling history. Best measured after a
#       few hours unplugged + lid closed.
#   6.  Launcher migration flag `disable_reducesar_v2` is set.
#   7.  Any stale v1 Magisk module under /data/adb/modules/disable_reducesar
#       has been cleaned up.
#
# Captures everything raw into ./reducesar_checks/<serial>_<utc-ts>/
# and prints a pass/fail summary inline so you can read the verdict
# without opening any files.
#
# Usage:
#   ./scripts/diag_reducesar_check.sh                 # default
#   ./scripts/diag_reducesar_check.sh --serial XYZ    # pick a device
#   ./scripts/diag_reducesar_check.sh --out ~/Desktop # custom dest
#   ./scripts/diag_reducesar_check.sh -h
#
# Designed to be readable by a human or pasted into a chat for review.

set -u

PKG="com.tct.reducesar"
COMPONENT_SHORT=".MtkCommonSarService"
COMPONENT_FQDN="com.tct.reducesar.MtkCommonSarService"
MIGRATION_KEY="disable_reducesar_v2"
LAUNCHER_PKG="com.offlineinc.dumbdownlauncher"
V1_MODULE_DIR="/data/adb/modules/disable_reducesar"

# ── arg parsing ────────────────────────────────────────────────────────

OUT_ROOT="./reducesar_checks"
SERIAL_ARG=""

while [ $# -gt 0 ]; do
    case "$1" in
        --serial) SERIAL_ARG="$2"; shift 2 ;;
        --out)    OUT_ROOT="$2";   shift 2 ;;
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

# ── results tracking ───────────────────────────────────────────────────

PASS=()  # checks that confirmed the desired state
FAIL=()  # checks that showed the fix isn't fully in place
INFO=()  # neutral observations worth surfacing but not pass/fail

pass() { printf "  ${G}✅${X} %s\n" "$1"; PASS+=("$1"); }
fail() { printf "  ${R}❌${X} %s\n" "$1"; FAIL+=("$1"); }
info() { printf "  ${Y}ℹ${X}  %s\n" "$1"; INFO+=("$1"); }
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

UTC_TS=$(date -u +%Y-%m-%dT%H-%M-%SZ)
SAFE_SERIAL=$(printf '%s' "$SERIAL" | tr -c 'A-Za-z0-9._-' '_')
OUT="$OUT_ROOT/${SAFE_SERIAL}_${UTC_TS}"
mkdir -p "$OUT" || { echo "${R}cannot create $OUT${X}" >&2; exit 1; }

cat <<EOF
${B}── reducesar fix verification ──${X}
  device:    $SERIAL  ($MODEL, Android $ANDROID_VER)
  out:       $OUT

EOF

# ── 1. is reducesar installed at all? ──────────────────────────────────

sect "1. package presence"

PATH_OUT=$(adb_sh "pm path $PKG")
echo "$PATH_OUT" > "$OUT/01_pm_path.txt"

if [ -z "$PATH_OUT" ] || ! echo "$PATH_OUT" | grep -q "^package:"; then
    info "$PKG is NOT installed on this device"
    info "this is normal for non-Verizon variants (4058W, etc.)"
    info "the launcher migration is a clean no-op on this device"
    cat <<EOF

${B}verdict:${X} ${G}nothing to fix — device is not affected${X}
  raw:  $OUT
EOF
    exit 0
fi
pass "$PKG installed ($(echo "$PATH_OUT" | sed 's|package:||'))"

# ── 2. is the component recorded as disabled? ──────────────────────────

sect "2. component disable (PackageManager state)"

PKG_DUMP=$(adb_sh "dumpsys package $PKG")
echo "$PKG_DUMP" > "$OUT/02_dumpsys_package.txt"

if echo "$PKG_DUMP" | grep -qE "disabledComponents:" && \
   echo "$PKG_DUMP" | awk '/disabledComponents:/,/^[[:space:]]*$/' | grep -q "$COMPONENT_FQDN"; then
    pass "$COMPONENT_FQDN listed in disabledComponents"
else
    fail "$COMPONENT_FQDN is NOT in disabledComponents — disable hasn't been applied"
fi

# ── 3. is the service actually NOT running right now? ──────────────────

sect "3. runtime services"

SVC_DUMP=$(adb_sh "dumpsys activity services $PKG")
echo "$SVC_DUMP" > "$OUT/03_activity_services.txt"

# "User 0 active services:\n  (nothing)" is the win line.
if echo "$SVC_DUMP" | awk '/User 0 active services:/,/^[A-Z]/' | grep -q "(nothing)"; then
    pass "no services running inside $PKG (zombie process, no work)"
elif echo "$SVC_DUMP" | grep -q "ServiceRecord.*$PKG"; then
    fail "a ServiceRecord for $PKG is currently active"
    echo "$SVC_DUMP" | grep -E "ServiceRecord|intent=" | head -4 | sed 's/^/    /'
else
    info "no User-0-active-services block found for $PKG (probably fine)"
fi

# ── 4. GnssStatus listener gone? ───────────────────────────────────────

sect "4. GPS subsystem"

LOC_DUMP=$(adb_sh "dumpsys location")
echo "$LOC_DUMP" > "$OUT/04_dumpsys_location.txt"

# We want the "GnssStatus Listeners:" section to NOT contain reducesar.
LISTENERS_BLOCK=$(echo "$LOC_DUMP" | awk '/GnssStatus Listeners:/,/^[[:space:]]*$/')
if echo "$LISTENERS_BLOCK" | grep -q "$PKG"; then
    fail "$PKG STILL appears under GnssStatus Listeners"
    echo "$LISTENERS_BLOCK" | grep "$PKG" | sed 's/^/    /'
else
    pass "no $PKG entry under GnssStatus Listeners (GPS subsystem is left alone)"
fi

# ── 5. did deep doze actually engage? ──────────────────────────────────

sect "5. deep doze (the actual battery payoff)"

IDLE_DUMP=$(adb_sh "dumpsys deviceidle")
echo "$IDLE_DUMP" > "$OUT/05_dumpsys_deviceidle.txt"

# mState is currently ACTIVE because the user just plugged in. What
# matters is whether deep-idle ever appeared in the Idling history
# (which is a rolling buffer of the last ~few hours).
HISTORY_BLOCK=$(echo "$IDLE_DUMP" | awk '/Idling history:/,/Whitelist/' | head -60)
echo "$HISTORY_BLOCK" > "$OUT/05a_idling_history.txt"

DEEP_IDLE_HITS=$(echo "$HISTORY_BLOCK" | grep -c "deep-idle" || true)
DEEP_MAINT_HITS=$(echo "$HISTORY_BLOCK" | grep -c "deep-maint" || true)
LIGHT_HITS=$(echo "$HISTORY_BLOCK" | grep -c "light-idle" || true)

if [ "$DEEP_IDLE_HITS" -gt 0 ]; then
    pass "deep-idle appears $DEEP_IDLE_HITS time(s) in Idling history (fix is paying off)"
    # show the deep-idle lines so the human can sanity-check timing
    echo "    ${D}deep-idle transitions:${X}"
    echo "$HISTORY_BLOCK" | grep -E "deep-idle|deep-maint" | tail -8 | sed 's/^/      /'
else
    if [ "$LIGHT_HITS" -gt 0 ]; then
        fail "no deep-idle in Idling history (only $LIGHT_HITS light-idle entries) — phone still isn't reaching deep doze"
    else
        info "Idling history is empty or short — phone may have just rebooted; rerun after a long unplugged stretch"
    fi
fi

# Also surface mState for context.
M_STATE=$(echo "$IDLE_DUMP" | grep -m1 "^  mState=" | awk -F= '{print $2}' | awk '{print $1}')
M_LIGHT=$(echo "$IDLE_DUMP" | grep -m1 "^  mLightState=" | awk -F= '{print $2}' | awk '{print $1}')
info "current mState=$M_STATE  mLightState=$M_LIGHT  ${D}(ACTIVE is expected when plugged in)${X}"

# ── 6. migration flag committed? ───────────────────────────────────────

sect "6. launcher migration artifacts"

# run-as only works on debuggable builds — on production builds this
# silently errors. That's fine; the in-system effects above are the
# binding evidence. Try it but treat absence as neutral.
MIG_XML=$(adb_sh "run-as $LAUNCHER_PKG cat shared_prefs/migrations.xml 2>/dev/null")
echo "$MIG_XML" > "$OUT/06_migrations_xml.txt"

if [ -n "$MIG_XML" ]; then
    if echo "$MIG_XML" | grep -q "$MIGRATION_KEY.*value=\"true\""; then
        pass "migration flag $MIGRATION_KEY committed (launcher ran v2 successfully)"
    elif echo "$MIG_XML" | grep -q "$MIGRATION_KEY"; then
        fail "migration $MIGRATION_KEY appears in prefs but is not set to true"
    else
        info "$MIGRATION_KEY not in migrations.xml — has the v2 launcher build been installed?"
    fi
else
    info "migrations.xml unreadable (production build — run-as is gated). System-level checks above are the binding evidence."
fi

# ── 7. stale v1 Magisk module cleaned up? ──────────────────────────────

sect "7. v1 Magisk module cleanup"

V1_LS=$(adb_sh "su -c 'ls -la $V1_MODULE_DIR 2>/dev/null'")
echo "$V1_LS" > "$OUT/07_v1_magisk_module_state.txt"

if [ -z "$V1_LS" ]; then
    pass "no $V1_MODULE_DIR present (clean device or cleanup succeeded)"
else
    fail "stale v1 Magisk module is still present at $V1_MODULE_DIR"
    echo "$V1_LS" | head -5 | sed 's/^/    /'
fi

# ── verdict ────────────────────────────────────────────────────────────

sect "verdict"

# The four binding checks: 2 (disabled), 3 (no services), 4 (no listener),
# and 5 (deep-idle). 6 and 7 are nice-to-have for hygiene.
# The "${arr[@]:-}" form expands to nothing when arr is unset/empty,
# which is what we want under `set -u` — bare ${arr[@]} would throw
# "unbound variable" on a fully-clean run with zero failures.
BINDING_FAILS=0
for f in "${FAIL[@]:-}"; do
    [ -z "$f" ] && continue
    case "$f" in
        *disabledComponents*|*ServiceRecord*|*GnssStatus*|*deep-idle*|*deep\ doze*)
            BINDING_FAILS=$((BINDING_FAILS + 1)) ;;
    esac
done

printf "  passed:  %d\n" "${#PASS[@]}"
printf "  failed:  %d  ${D}(of which binding: %d)${X}\n" "${#FAIL[@]}" "$BINDING_FAILS"
printf "  notes:   %d\n" "${#INFO[@]}"
echo

if [ "$BINDING_FAILS" -eq 0 ]; then
    if [ "${#FAIL[@]}" -eq 0 ]; then
        echo "  ${G}${B}✅ the fix is fully in place${X}"
    else
        echo "  ${Y}${B}🟡 the fix is working but ${#FAIL[@]} non-binding hygiene check(s) failed${X}"
        for f in "${FAIL[@]:-}"; do
            [ -z "$f" ] && continue
            printf "      - %s\n" "$f"
        done
    fi
else
    echo "  ${R}${B}❌ the fix is NOT fully applied — $BINDING_FAILS binding check(s) failed:${X}"
    for f in "${FAIL[@]:-}"; do
        [ -z "$f" ] && continue
        printf "      - %s\n" "$f"
    done
fi

cat <<EOF

  raw dumps for full review:
    $OUT
EOF
