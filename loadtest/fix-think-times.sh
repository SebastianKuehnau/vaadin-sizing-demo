#!/usr/bin/env bash
# Works around a bug in testbench-converter-plugin 25.3.0: the think time after a page load is
# inserted into the error branch of the preceding check, right after fail(), so it never runs and
# the virtual users fire their requests without pauses. This moves each such sleep() behind the
# closing brace of the branch. Safe to run repeatedly; already fixed scripts stay unchanged.
#
#   loadtest/fix-think-times.sh [directory with the recorded k6 scripts]
set -euo pipefail

dir="${1:-src/test/k6/recordings}"
shopt -s nullglob
for script in "$dir"/*.js; do
    case "$script" in
        *-generated.js | */combined-scenarios.js) continue ;;
    esac
    perl -0pi -e 's{(\n[ \t]*fail\(`[^\n]*\n)\n(// Think time:[^\n]*\nsleep\([^\n]*\);\n)([ \t]*\}\n)}{$1$3\n$2}g' "$script"
    misplaced=$(perl -0ne 'my $n = () = /fail\(`[^\n]*\n\n\/\/ Think time:/g; print $n' "$script")
    if [[ "$misplaced" != 0 ]]; then
        echo "WARNING: $script still has $misplaced think time(s) inside an error branch" >&2
    fi
done
