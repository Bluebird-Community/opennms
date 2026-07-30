#!/usr/bin/env bash
# ----------------------------------------------------------------------
# Build the container image for one component: core, minion or sentinel.
#
# Replaces three near-identical Makefile recipes. They differed in only three ways,
# all of which are now the component table below:
#
#   component  assembly tarball                                   strip  extra step
#   core       opennms-full-assembly/target/...-core.tar.gz        0      -
#   minion     opennms-assemblies/minion/target/...-minion.tar.gz  1      render config schema
#   sentinel   opennms-assemblies/sentinel/target/...-...tar.gz    1      -
#
# The missing-tarball check moves from make's ifeq/$(wildcard) to a runtime test.
# $(wildcard) is evaluated when the Makefile is parsed, so it could report a stale
# answer for a tarball produced later in the same make run. A runtime check cannot.
# ----------------------------------------------------------------------
set -euo pipefail

usage() {
  cat <<'EOF'
usage: build-tooling/build-oci-image.sh --component <core|minion|sentinel> [options]

Extracts the component's assembly tarball and builds its container image.

Required:
  --component <name>        core, minion or sentinel
  --version <v>             OpenNMS version, used to locate the tarball
  --install-version <v>     written to tarball-root/etc/version.info
  --revision <sha>          short commit, passed as the REVISION build arg
  --base-image <ref>        DEPLOY_BASE_IMAGE build arg

Optional:
  --platform <p>            docker --platform value (default: linux/$(uname -m))
  --build-date <d>          BUILD_DATE build arg (default: today, YYYYMMDD)
  --tag <ref>               image tag (default: local/<component>:latest)
  --branch <name>           git branch, only used by the minion config schema
  --build-number <n>        build number, only used by the minion config schema
  -h                        show this help
EOF
}

COMPONENT=''; VERSION=''; INSTALL_VERSION=''; REVISION=''; BASE_IMAGE=''
PLATFORM=''; BUILD_DATE=''; TAG=''; BRANCH=''; BUILD_NUMBER='0'
while [ $# -gt 0 ]; do
  case "$1" in
    --component)       COMPONENT="$2"; shift 2 ;;
    --version)         VERSION="$2"; shift 2 ;;
    --install-version) INSTALL_VERSION="$2"; shift 2 ;;
    --revision)        REVISION="$2"; shift 2 ;;
    --base-image)      BASE_IMAGE="$2"; shift 2 ;;
    --platform)        PLATFORM="$2"; shift 2 ;;
    --build-date)      BUILD_DATE="$2"; shift 2 ;;
    --tag)             TAG="$2"; shift 2 ;;
    --branch)          BRANCH="$2"; shift 2 ;;
    --build-number)    BUILD_NUMBER="$2"; shift 2 ;;
    -h|--help)         usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

for required in COMPONENT VERSION INSTALL_VERSION REVISION BASE_IMAGE; do
  eval "value=\${$required}"
  [ -n "$value" ] || { echo "missing required argument for --$(echo "$required" | tr 'A-Z_' 'a-z-')" >&2; exit 2; }
done

# The component table: tarball location and how deep the archive is nested.
case "$COMPONENT" in
  core)
    TARBALL="opennms-full-assembly/target/opennms-full-assembly-${VERSION}-core.tar.gz"
    STRIP=0
    ;;
  minion)
    TARBALL="opennms-assemblies/minion/target/org.opennms.assemblies.minion-${VERSION}-minion.tar.gz"
    STRIP=1
    ;;
  sentinel)
    TARBALL="opennms-assemblies/sentinel/target/org.opennms.assemblies.sentinel-${VERSION}-sentinel.tar.gz"
    STRIP=1
    ;;
  *)
    echo "unknown component: $COMPONENT (expected core, minion or sentinel)" >&2
    exit 2
    ;;
esac

: "${PLATFORM:=linux/$(uname -m)}"
: "${BUILD_DATE:=$(date '+%Y%m%d')}"
: "${TAG:=local/${COMPONENT}:latest}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [ ! -f "$TARBALL" ]; then
  cat >&2 <<EOF
Can't build the ${COMPONENT} container image, the build artifact
./${TARBALL} doesn't exist.

You can create the artifact with:

  make quick-compile && make quick-assemble

EOF
  exit 1
fi

CONTEXT="opennms-container/${COMPONENT}"
ROOTFS="${CONTEXT}/tarball-root"

echo "Building ${TAG} for ${PLATFORM}"
echo "  tarball: ${TARBALL}"

mkdir -p "$ROOTFS"
if [ "$STRIP" -gt 0 ]; then
  tar xzf "$TARBALL" --strip-components "$STRIP" -C "$ROOTFS"
else
  tar xzf "$TARBALL" -C "$ROOTFS"
fi

echo "$INSTALL_VERSION" > "${ROOTFS}/etc/version.info"

# Minion ships a config schema rendered from a template.
if [ "$COMPONENT" = "minion" ]; then
  sed -e "s,@VERSION@,${VERSION}," \
      -e "s,@REVISION@,${REVISION}," \
      -e "s,@BRANCH@,${BRANCH}," \
      -e "s,@BUILD_NUMBER@,${BUILD_NUMBER}," \
      "${CONTEXT}/minion-config-schema.yml.in" > "${CONTEXT}/minion-config-schema.yml"
fi

docker build --platform="$PLATFORM" \
  --build-arg "DEPLOY_BASE_IMAGE=${BASE_IMAGE}" \
  --build-arg "BUILD_DATE=${BUILD_DATE}" \
  --build-arg "VERSION=${VERSION}" \
  --build-arg "REVISION=${REVISION}" \
  -t "$TAG" \
  "$CONTEXT"
