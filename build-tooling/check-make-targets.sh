#!/usr/bin/env bash
# ----------------------------------------------------------------------
# Verify that every make target the CI workflows invoke still resolves.
#
# The Makefile is the front door: .github/workflows/main.yml drives the build
# entirely through make targets. Renaming or deleting one of those targets breaks
# CI, and the only signal today is a failed job several minutes into a run.
#
# This discovers the target names from the workflows rather than hardcoding them,
# so the list cannot drift, then asks make to resolve each one. "make -n" prints
# recipes without running them, so this is inert and finishes in about a second.
#
# It is a name-resolution check, not a build. A target that exists but is broken
# will still pass here.
# ----------------------------------------------------------------------
set -euo pipefail

usage() {
  cat <<'EOF'
usage: build-tooling/check-make-targets.sh [--list] [workflow-glob ...]

Checks that every "make <target>" referenced by the CI workflows resolves.

  --list    print the discovered targets and exit without checking
  -h        show this help

With no glob, .github/workflows/*.yml is used.

Exit status is 0 when every target resolves, 1 otherwise.
EOF
}

LIST_ONLY=0
declare -a GLOBS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --list) LIST_ONLY=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) GLOBS+=("$1"); shift ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [ "${#GLOBS[@]}" -eq 0 ]; then
  GLOBS=(.github/workflows/*.yml)
fi

# Target names must start with a letter, which keeps flags such as "make -n" out.
# A read loop rather than mapfile, because macOS still ships bash 3.2 and this has
# to run locally as well as on CI.
declare -a TARGETS=()
while IFS= read -r target; do
  [ -n "$target" ] && TARGETS+=("$target")
done < <(
  grep -ohE 'make +[a-z][a-z0-9_.-]*' "${GLOBS[@]}" 2>/dev/null \
    | sed -E 's/^make +//' \
    | sort -u
)

if [ "${#TARGETS[@]}" -eq 0 ]; then
  echo "No 'make <target>' invocations found in: ${GLOBS[*]}" >&2
  echo "That is almost certainly a bug in this script's discovery, not a clean result." >&2
  exit 1
fi

if [ "$LIST_ONLY" -eq 1 ]; then
  printf '%s\n' "${TARGETS[@]}"
  exit 0
fi

echo "Checking ${#TARGETS[@]} make targets referenced by CI"

declare -a MISSING=()
for target in "${TARGETS[@]}"; do
  if make -n "$target" >/dev/null 2>&1; then
    printf '  ok      %s\n' "$target"
  else
    printf '  MISSING %s\n' "$target"
    MISSING+=("$target")
  fi
done

if [ "${#MISSING[@]}" -gt 0 ]; then
  echo ""
  echo "${#MISSING[@]} target(s) referenced by CI do not resolve:" >&2
  printf '  %s\n' "${MISSING[@]}" >&2
  echo "" >&2
  echo "Either restore the target name or update the workflow that calls it." >&2
  exit 1
fi

echo ""
echo "All ${#TARGETS[@]} targets resolve."
