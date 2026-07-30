#!/usr/bin/env bash
# ----------------------------------------------------------------------
# Collect JaCoCo and JUnit reports, then submit coverage to SonarCloud.
#
# This was a Makefile recipe. Moving it to a script fixes two defects that the
# recipe form caused, so this is a behaviour change and a deliberate one.
#
# 1. Evaluation order. The recipe built six list files and then read them back with
#    $(shell cat ...) in the same recipe. Make expands an entire recipe before
#    running any of its lines, so those reads happened before the writes: the values
#    came from the previous run, or from nothing on a clean tree. A script runs
#    sequentially, so each list is written before it is read.
#
# 2. A missing `cat`. The recipe passed
#      -Dsonar.java.binaries="$(shell $(ARTIFACTS_DIR)/.../class-folders.txt | paste -s -d, -)"
#    which asks the shell to execute the list file rather than read it. The property
#    was therefore always empty and Sonar saw no compiled classes.
#
# Requires sonar-scanner on PATH and the usual SONAR_TOKEN in the environment.
# ----------------------------------------------------------------------
set -euo pipefail

usage() {
  cat <<'EOF'
usage: build-tooling/code-coverage.sh [--artifacts-dir <dir>] [--dry-run]

Builds the report and source lists Sonar needs, then runs sonar-scanner.

  --artifacts-dir <dir>  where to write the lists (default: target/artifacts)
  --host <url>           Sonar host (default: https://sonarcloud.io)
  --dry-run              build the lists and print the scanner command, run nothing
  -h                     show this help
EOF
}

ARTIFACTS_DIR='target/artifacts'
SONAR_HOST='https://sonarcloud.io'
DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --artifacts-dir) ARTIFACTS_DIR="$2"; shift 2 ;;
    --host)          SONAR_HOST="$2"; shift 2 ;;
    --dry-run)       DRY_RUN=1; shift ;;
    -h|--help)       usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

OUT="${ARTIFACTS_DIR}/code-coverage"
mkdir -p "$OUT"

echo "Collecting coverage inputs into ${OUT}"

# Every JaCoCo report produced during the compile phase.
find . -type f '!' -path './.git/*' -name jacoco.xml | sort -u > "${OUT}/jacoco.xml"

# Source and test folders, reverse engineered from the compiled target directories.
find . -type d '!' -path './.git/*' -name target | sed -e 's,/target,/src,' | while read -r src; do
  printf '%s/main\n%s/assembly\n' "$src" "$src"
done | sort -u > "${OUT}/source-folders.txt"

find . -type d '!' -path './.git/*' -name target | sed -e 's,/target,/src,' | while read -r src; do
  printf '%s/test\n' "$src"
done | sort -u > "${OUT}/test-folders.txt"

# JUnit report directories, then the class trees that sit beside them.
find . -type d '!' -path './.git/*' \
     -a \( -name 'surefire-reports*' -o -name 'failsafe-reports*' \) \
     | sort -u > "${OUT}/junit-report-folders.txt"

# Written above, so it is readable here. That ordering is the point of this script.
sed -e 's,/surefire-reports,,' -e 's,/failsafe-reports,,' "${OUT}/junit-report-folders.txt" \
  | sort -u | while read -r dir; do
      [ -d "$dir" ] && find "$dir" -maxdepth 1 -type d -name test-classes
    done | sort -u > "${OUT}/test-class-folders.txt"

sed -e 's,/surefire-reports,,' -e 's,/failsafe-reports,,' "${OUT}/junit-report-folders.txt" \
  | sort -u | while read -r dir; do
      [ -d "$dir" ] && find "$dir" -maxdepth 1 -type d -name classes
    done | sort -u > "${OUT}/class-folders.txt"

join_list() { paste -s -d, - < "$1"; }

JACOCO="$(join_list "${OUT}/jacoco.xml")"
JUNIT="$(join_list "${OUT}/junit-report-folders.txt")"
SOURCES="$(join_list "${OUT}/source-folders.txt")"
TESTS="$(join_list "${OUT}/test-folders.txt")"
BINARIES="$(join_list "${OUT}/class-folders.txt")"
TEST_BINARIES="$(join_list "${OUT}/test-class-folders.txt")"

for name in JACOCO JUNIT SOURCES TESTS BINARIES TEST_BINARIES; do
  eval "value=\${$name}"
  count=0
  [ -n "$value" ] && count=$(printf '%s' "$value" | tr ',' '\n' | grep -c . || true)
  printf '  %-14s %s entries\n' "$name" "$count"
  if [ "$count" -eq 0 ]; then
    echo "  WARNING: ${name} is empty. Sonar will ignore it. Did the build run with -Pcoverage?" >&2
  fi
done

set -- \
  "-Dsonar.host.url=${SONAR_HOST}" \
  "-Djava.security.egd=file:/dev/./urandom" \
  "-Dsonar.coverage.jacoco.xmlReportPaths=${JACOCO}" \
  "-Dsonar.junit.reportPaths=${JUNIT}" \
  "-Dsonar.sources=${SOURCES}" \
  "-Dsonar.tests=${TESTS}" \
  "-Dsonar.java.binaries=${BINARIES}" \
  "-Dsonar.java.libraries=${HOME}/.m2/repository/**/*.jar,**/*.jar" \
  "-Dsonar.java.test.binaries=${TEST_BINARIES}" \
  "-Dsonar.java.test.libraries=${HOME}/.m2/repository/**/*.jar,**/*.jar"

if [ "$DRY_RUN" -eq 1 ]; then
  echo ""
  echo "would run: sonar-scanner"
  for arg in "$@"; do printf '  %s\n' "$(printf '%s' "$arg" | cut -c1-160)"; done
  exit 0
fi

exec sonar-scanner "$@"
