#!/usr/bin/env bash
# ----------------------------------------------------------------------
# Build distribution packages for one component with nfpm.
#
# Replaces six near-identical Makefile recipes: core, minion and sentinel crossed
# with deb and rpm. They differed only in the nfpm config file, the output directory
# and the --packager flag, so that is now the component table below.
#
# --packager may be given more than once. Building both formats in a single call is
# the point: core-pkg-deb and core-pkg-rpm each depend on core-pkg-buildroot, and
# make only deduplicates a shared prerequisite within one invocation. Two separate
# make calls therefore extracted the 1 GB core assembly twice.
# ----------------------------------------------------------------------
set -euo pipefail

usage() {
  cat <<'EOF'
usage: build-tooling/build-package.sh --component <core|minion|sentinel> \
                                     --packager <deb|rpm> [--packager ...] [options]

Required:
  --component <name>        core, minion or sentinel
  --packager <fmt>          deb or rpm, repeatable to build both in one pass
  --version <v>             OPENNMS_VERSION passed to nfpm

Optional:
  --release <n>             PKG_RELEASE (default: 0)
  --arch <arch>             ARCH (default: amd64)
  --maintainer-email <addr> MAINTAINER_EMAIL
  --artifacts-dir <dir>     output root (default: target/artifacts)
  -h                        show this help
EOF
}

COMPONENT=''; VERSION=''; RELEASE='0'; ARCH='amd64'
MAINTAINER_EMAIL=''; ARTIFACTS_DIR='target/artifacts'
declare -a PACKAGERS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --component)        COMPONENT="$2"; shift 2 ;;
    --packager)         PACKAGERS+=("$2"); shift 2 ;;
    --version)          VERSION="$2"; shift 2 ;;
    --release)          RELEASE="$2"; shift 2 ;;
    --arch)             ARCH="$2"; shift 2 ;;
    --maintainer-email) MAINTAINER_EMAIL="$2"; shift 2 ;;
    --artifacts-dir)    ARTIFACTS_DIR="$2"; shift 2 ;;
    -h|--help)          usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

[ -n "$COMPONENT" ] || { echo "missing --component" >&2; exit 2; }
[ -n "$VERSION" ]   || { echo "missing --version" >&2; exit 2; }
[ "${#PACKAGERS[@]}" -gt 0 ] || { echo "missing --packager" >&2; exit 2; }

case "$COMPONENT" in
  core)     LABEL='Core' ;;
  minion)   LABEL='Minion' ;;
  sentinel) LABEL='Sentinel' ;;
  *) echo "unknown component: $COMPONENT (expected core, minion or sentinel)" >&2; exit 2 ;;
esac

CONFIG="nfpm/nfpm-${COMPONENT}.yaml"
TARGET="${ARTIFACTS_DIR}/packages/${COMPONENT}/"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

[ -f "$CONFIG" ] || { echo "nfpm config not found: $CONFIG" >&2; exit 1; }
mkdir -p "$TARGET"

for packager in "${PACKAGERS[@]}"; do
  case "$packager" in
    deb) FORMAT='Debian' ;;
    rpm) FORMAT='RPM' ;;
    *) echo "unknown packager: $packager (expected deb or rpm)" >&2; exit 2 ;;
  esac

  echo "==== Building ${FORMAT} ${LABEL} Packages ===="
  echo
  echo "Version:      ${VERSION}"
  echo "Release:      ${RELEASE}"
  echo "Architecture: ${ARCH}"
  echo "Config:       ${CONFIG}"
  echo "Target:       ${TARGET}"
  echo

  ARCH="$ARCH" \
  OPENNMS_VERSION="$VERSION" \
  PKG_RELEASE="$RELEASE" \
  MAINTAINER_EMAIL="$MAINTAINER_EMAIL" \
    nfpm package --packager "$packager" --config "$CONFIG" --target "$TARGET"
done
