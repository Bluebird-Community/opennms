#!/usr/bin/env bash
# ----------------------------------------------------------------------
# Render `make help` from the Makefile itself.
#
# The help text used to be 78 hand-written @echo lines. It had already drifted:
# 20 targets were missing from it, including compile-ui, package-reactor-artifacts
# and restore-reactor-artifacts, all of which CI invokes.
#
# Now each documented target carries its own description and cannot drift:
#
#   ##@ Section name
#   target: prereqs ## what it does
#
# Usage: build-tooling/make-help.sh <makefile> [<makefile> ...]
# ----------------------------------------------------------------------
set -euo pipefail

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  sed -n '3,15p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
fi

if [ $# -eq 0 ]; then
  echo "usage: $0 <makefile> [<makefile> ...]" >&2
  exit 1
fi

# Colour only when writing to a terminal, so piping stays clean.
if [ -t 1 ]; then
  BOLD=$'\033[1m'; CYAN=$'\033[36m'; DIM=$'\033[2m'; OFF=$'\033[0m'
else
  BOLD=''; CYAN=''; DIM=''; OFF=''
fi

cat <<EOF

${BOLD}Makefile to build artifacts for OpenNMS${OFF}

Requirements: OpenJDK 21, NodeJS 24 with pnpm, Antora for docs.
Maven is downloaded on demand by ./mvnw, so no local install is needed.
Maven flags live in .mvn/maven.config, JVM options in .mvn/jvm.config.

Usage: ${CYAN}make <target>${OFF}   (default: $(awk -F':= *' '/^\.DEFAULT_GOAL/{print $2}' "$1"))
EOF

awk -v bold="$BOLD" -v cyan="$CYAN" -v off="$OFF" '
  # Section heading: ##@ Name
  /^##@/ {
    section = substr($0, 5)
    gsub(/^[ \t]+|[ \t]+$/, "", section)
    printf "\n%s%s%s\n", bold, section, off
    next
  }
  # Documented target: name: [prereqs] ## description
  /^[a-zA-Z0-9_.-]+:[^=]*##/ {
    split($0, parts, ":")
    name = parts[1]
    desc = $0
    sub(/^[^#]*## */, "", desc)
    printf "  %s%-28s%s %s\n", cyan, name, off, desc
  }
' "$@"

cat <<EOF

${DIM}Scoping tests${OFF}
  make unit-tests U_TESTS=org.opennms...BgpSessionDetectorTest TEST_PROJECTS=org.opennms:opennms-detector-simple
  make integration-tests I_TESTS=org.opennms...SnmpPollerIT TEST_PROJECTS=org.opennms:opennms-services

${DIM}Common variables${OFF}
  SITE_FILE=<file>      Antora site file used by the docs target
  MAVEN_SHARDS / MAVEN_SHARD_IDX   split a test suite across parallel jobs

EOF
