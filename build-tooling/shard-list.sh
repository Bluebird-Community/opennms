#!/usr/bin/env bash
# ----------------------------------------------------------------------
# Select this shard's slice of a test class list and print it comma separated.
#
# Replaces five copies of the same pipeline in the Makefile:
#
#   grep -Fxv -f <skip> <list> | awk "NR%$(MAVEN_SHARDS)==$(MAVEN_SHARD_IDX)" | paste -s -d, -
#
# Fixes #206 as a side effect. When a shard's slice came out empty the Makefile
# passed -Dtest="" to Maven, and surefire treats an empty filter as no filter, so
# that shard silently ran every test in scope instead of none. Measured on a scoped
# build: shard 0 was assigned nothing and ran the whole module, duplicating the work
# of shards 1 and 2.
#
# Instead of an empty string this prints a sentinel that matches no class. unit-tests
# and integration-tests already pass -Dsurefire.failIfNoSpecifiedTests=false and
# -Dfailsafe.failIfNoSpecifiedTests=false, so they run nothing and succeed, which is
# what an empty shard should do. The e2e targets do not pass those flags, so an empty
# e2e slice still fails loudly. That is deliberate: with 134 classes over 8 shards an
# empty e2e slice means the sharding is misconfigured, and it should be visible.
#
# Slicing happens after the skip filter, matching the original pipeline exactly, so
# shard assignment is unchanged. Input order is preserved and never re-sorted:
# find-tests.py already writes these lists sorted, and stable ordering is what makes
# the slicing shard safe in the first place.
# ----------------------------------------------------------------------
set -euo pipefail

SENTINEL='__NO_TESTS_ASSIGNED_TO_THIS_SHARD__'

usage() {
  cat <<'EOF'
usage: build-tooling/shard-list.sh --file <list> --shards <n> --index <i> [--skip <file>]

Prints the comma separated slice of <list> belonging to shard <i> of <n>.

  --file <list>    file of test class names, one per line
  --shards <n>     total number of shards (>= 1)
  --index <i>      this shard's index (0 <= i < n)
  --skip <file>    optional file of class names to exclude, matched whole line
  --sentinel <s>   text to print when the slice is empty
  -h               show this help

Prints a sentinel that matches no test class when the slice is empty, rather than
an empty string. See the comment at the top of this script and issue #206.
EOF
}

LIST=''; SHARDS=''; INDEX=''; SKIP=''
while [ $# -gt 0 ]; do
  case "$1" in
    --file)     LIST="$2"; shift 2 ;;
    --shards)   SHARDS="$2"; shift 2 ;;
    --index)    INDEX="$2"; shift 2 ;;
    --skip)     SKIP="$2"; shift 2 ;;
    --sentinel) SENTINEL="$2"; shift 2 ;;
    -h|--help)  usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

for required in LIST SHARDS INDEX; do
  eval "value=\${$required}"
  if [ -z "$value" ]; then
    echo "missing required argument for $required" >&2
    usage >&2
    exit 2
  fi
done

if [ ! -f "$LIST" ]; then
  echo "test list not found: $LIST" >&2
  exit 1
fi

case "$SHARDS" in ''|*[!0-9]*) echo "--shards must be a positive integer, got '$SHARDS'" >&2; exit 2 ;; esac
case "$INDEX"  in ''|*[!0-9]*) echo "--index must be a non-negative integer, got '$INDEX'" >&2; exit 2 ;; esac
[ "$SHARDS" -ge 1 ] || { echo "--shards must be >= 1" >&2; exit 2; }
[ "$INDEX" -lt "$SHARDS" ] || { echo "--index $INDEX is out of range for $SHARDS shards" >&2; exit 2; }

# grep -v exits 1 when it selects no lines, which set -e would treat as fatal, so
# tolerate that and let the emptiness check below handle it.
if [ -n "$SKIP" ]; then
  [ -f "$SKIP" ] || { echo "skip list not found: $SKIP" >&2; exit 1; }
  candidates="$(grep -Fxv -f "$SKIP" "$LIST" || true)"
else
  candidates="$(cat "$LIST")"
fi

slice="$(printf '%s\n' "$candidates" \
  | awk -v n="$SHARDS" -v i="$INDEX" 'NF && NR % n == i' \
  | paste -s -d, - \
  || true)"

if [ -z "$slice" ]; then
  printf '%s\n' "$SENTINEL"
else
  printf '%s\n' "$slice"
fi
