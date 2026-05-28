#!/usr/bin/env bash
#
# battery_analysis/analyze.sh
#
# Run the analyzer on every diag_pulls/*.tar.gz (or a subset specified
# positionally) and write per-tester bundles into battery_analysis/.
#
# Usage:
#   ./battery_analysis/analyze.sh                   # process every tarball
#   ./battery_analysis/analyze.sh marco             # just marco.tar.gz
#   ./battery_analysis/analyze.sh marco control     # two specific tarballs
#   ./battery_analysis/analyze.sh --force marco     # overwrite existing bundle
#
# Reads from:   diag_pulls/<name>.tar.gz
# Writes into:  battery_analysis/<name>/
#                 launcher-diag/             # hoisted from inside the tarball
#                 device-info-overrides.json # tester_label = <name>
#                 _bundle/                   # the analyzer output
#
# The analyzer is tools/build-analysis-bundle.py. We invoke it with the
# per-tester staging dir; it picks up launcher-diag/ and emits
# _bundle/summary.json + per-section files.

set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
ANALYZER="$REPO/tools/build-analysis-bundle.py"
PULLS="$REPO/diag_pulls"

FORCE=0
NAMES=()

while [ $# -gt 0 ]; do
    case "$1" in
        --force|-f) FORCE=1; shift ;;
        -h|--help)
            sed -n '3,22p' "$0"
            exit 0
            ;;
        *) NAMES+=("$1"); shift ;;
    esac
done

if [ ! -x "$(command -v python3)" ]; then
    echo "python3 is required" >&2; exit 1
fi
if [ ! -f "$ANALYZER" ]; then
    echo "analyzer missing: $ANALYZER" >&2; exit 1
fi
if [ ! -d "$PULLS" ]; then
    echo "no diag_pulls/ dir at $PULLS" >&2; exit 1
fi

# Default to every tarball if none specified.
if [ "${#NAMES[@]}" -eq 0 ]; then
    while IFS= read -r f; do
        NAMES+=("$(basename "$f" .tar.gz)")
    done < <(ls "$PULLS"/*.tar.gz 2>/dev/null)
fi

if [ "${#NAMES[@]}" -eq 0 ]; then
    echo "no tarballs to process" >&2; exit 1
fi

OK=0; FAIL=0
for name in "${NAMES[@]}"; do
    tarball="$PULLS/$name.tar.gz"
    stage="$HERE/$name"

    echo
    echo "── $name ────────────────────────────────────────"

    if [ ! -f "$tarball" ]; then
        echo "  ✘ no tarball at $tarball — skipping"
        FAIL=$((FAIL+1))
        continue
    fi

    if [ -d "$stage" ] && [ "$FORCE" -eq 0 ]; then
        echo "  ✔ already analyzed at $stage  (rerun with --force to redo)"
        OK=$((OK+1))
        continue
    fi

    rm -rf "$stage"
    mkdir -p "$stage"

    # Extract.
    tar -xzf "$tarball" -C "$stage" 2>/dev/null || {
        echo "  ✘ extract failed"; FAIL=$((FAIL+1)); continue;
    }

    # Strip macOS junk that breaks JSONL parsers.
    find "$stage" -name '._*' -delete 2>/dev/null
    find "$stage" -name '.DS_Store' -delete 2>/dev/null

    # Find the inner pull dir and hoist its diag/ subtree to launcher-diag/.
    inner_diag=$(find "$stage" -mindepth 2 -maxdepth 3 -type d -name diag 2>/dev/null | head -1)
    if [ -z "$inner_diag" ]; then
        echo "  ✘ no diag/ inside the tarball"; FAIL=$((FAIL+1)); continue;
    fi
    mv "$inner_diag" "$stage/launcher-diag"

    # Move pull_meta.json out alongside the staging dir so device-info pickup works.
    pull_meta=$(find "$stage" -maxdepth 3 -name pull_meta.json 2>/dev/null | head -1)
    [ -n "$pull_meta" ] && cp "$pull_meta" "$stage/pull_meta.json"
    logcat_fresh=$(find "$stage" -maxdepth 3 -name logcat_fresh.txt 2>/dev/null | head -1)
    [ -n "$logcat_fresh" ] && cp "$logcat_fresh" "$stage/logcat_fresh.txt"

    # Clean up the now-empty inner dir.
    inner_top=$(find "$stage" -mindepth 1 -maxdepth 1 -type d ! -name launcher-diag 2>/dev/null | head -1)
    [ -n "$inner_top" ] && rm -rf "$inner_top"

    # Attribute the bundle.
    cat > "$stage/device-info-overrides.json" <<EOF
{
  "tester_label": "$name",
  "source_tarball": "$(basename "$tarball")"
}
EOF

    # Run analyzer.
    echo "  running analyzer …"
    if python3 "$ANALYZER" "$stage" >/tmp/analyzer_$name.log 2>&1; then
        echo "  ✔ bundle at $stage/_bundle"
        # surface a one-line summary
        if [ -f "$stage/_bundle/summary.json" ]; then
            python3 - <<PY 2>/dev/null
import json, pathlib
s = json.loads(pathlib.Path("$stage/_bundle/summary.json").read_text())
print(f"     samples={s.get('samples_count','?')} events={s.get('events_count','?')} window={s.get('capture_window_hours','?')}h baseline_drain={s.get('baseline_drain_rate_pct_per_hour','?')}%/h")
PY
        fi
        OK=$((OK+1))
    else
        echo "  ✘ analyzer failed (see /tmp/analyzer_$name.log)"
        tail -5 /tmp/analyzer_$name.log | sed 's/^/      /'
        FAIL=$((FAIL+1))
    fi
done

echo
echo "── done ──────────────────────────────────────────"
echo "  ok=$OK  fail=$FAIL"
