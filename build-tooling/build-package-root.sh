#!/usr/bin/env bash
# ----------------------------------------------------------------------
# Lay out the package build root for one component: core, minion or sentinel.
#
# nfpm packages the tree this produces. Replaces three near-identical Makefile
# recipes that differed in the assembly tarball, which directories to prune, and how
# the systemd unit and init script are placed.
#
# Fixes two undefined-variable typos carried by the originals: the minion and
# sentinel recipes referenced $(PKG_MINION) and $(PKG_SENTINEL), neither of which is
# defined, so each expanded to an empty string and created "<build-root>/<component>"
# instead of the component home. Harmless in practice, because the home directory is
# already created a few lines earlier, but it silently did nothing. `make
# --warn-undefined-variables` reports both.
#
# The missing-tarball check is a runtime test rather than make's ifeq/$(wildcard),
# which is evaluated at parse time and can report a stale answer for a tarball
# produced later in the same run.
# ----------------------------------------------------------------------
set -euo pipefail

usage() {
  cat <<'EOF'
usage: build-tooling/build-package-root.sh --component <core|minion|sentinel> [options]

Extracts the component assembly and arranges it as a package build root.

Required:
  --component <name>       core, minion or sentinel
  --version <v>            OpenNMS version, used to locate the tarball
  --build-root <dir>       root the tree is created under
  --artifacts-dir <dir>    where the packages directory is created

Optional, defaulted from the component when omitted:
  --home <path>            install prefix, e.g. /opt/opennms
  --logs <path>            log directory to create
  --deploy <path>          deploy directory to create
  --rrd <path>             core only, RRD directory to create
  --reports <path>         core only, reports directory to create
  -h                       show this help
EOF
}

COMPONENT=''; VERSION=''; BUILD_ROOT=''; ARTIFACTS_DIR=''
HOME_DIR=''; LOGS_DIR=''; DEPLOY_DIR=''; RRD_DIR=''; REPORTS_DIR=''
while [ $# -gt 0 ]; do
  case "$1" in
    --component)     COMPONENT="$2"; shift 2 ;;
    --version)       VERSION="$2"; shift 2 ;;
    --build-root)    BUILD_ROOT="$2"; shift 2 ;;
    --artifacts-dir) ARTIFACTS_DIR="$2"; shift 2 ;;
    --home)          HOME_DIR="$2"; shift 2 ;;
    --logs)          LOGS_DIR="$2"; shift 2 ;;
    --deploy)        DEPLOY_DIR="$2"; shift 2 ;;
    --rrd)           RRD_DIR="$2"; shift 2 ;;
    --reports)       REPORTS_DIR="$2"; shift 2 ;;
    -h|--help)       usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

for required in COMPONENT VERSION BUILD_ROOT ARTIFACTS_DIR; do
  eval "value=\${$required}"
  [ -n "$value" ] || { echo "missing required argument for $required" >&2; exit 2; }
done

case "$COMPONENT" in
  core)
    TARBALL="opennms-full-assembly/target/opennms-full-assembly-${VERSION}-core.tar.gz"
    STRIP=0
    : "${HOME_DIR:=/opt/opennms}"
    PRUNE="logs share/rrd share/reports deploy"
    SERVICE_UNIT="opennms.service"
    SERVICE_MODE="copy"      # core keeps a copy in the install tree
    INIT_SCRIPT=""
    ;;
  minion|sentinel)
    if [ "$COMPONENT" = "minion" ]; then
      TARBALL="opennms-assemblies/minion/target/org.opennms.assemblies.minion-${VERSION}-minion.tar.gz"
    else
      TARBALL="opennms-assemblies/sentinel/target/org.opennms.assemblies.sentinel-${VERSION}-sentinel.tar.gz"
    fi
    STRIP=1
    : "${HOME_DIR:=/opt/${COMPONENT}}"
    PRUNE="data/log deploy"
    SERVICE_UNIT="${COMPONENT}.service"
    SERVICE_MODE="move"      # container images do not need it inside the tree
    INIT_SCRIPT="${COMPONENT}.init"
    ;;
  *)
    echo "unknown component: $COMPONENT (expected core, minion or sentinel)" >&2
    exit 2
    ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [ ! -f "$TARBALL" ]; then
  cat >&2 <<EOF
Can't build the ${COMPONENT} build root directory structure
./${TARBALL} doesn't exist.

You can create the artifact with:

  make quick-compile && make quick-assemble

EOF
  exit 1
fi

DEST="${BUILD_ROOT}/${COMPONENT}"
INSTALL_TREE="${DEST}${HOME_DIR}"

echo "Laying out ${COMPONENT} package root in ${DEST}"

mkdir -p "$INSTALL_TREE"
mkdir -p "${ARTIFACTS_DIR}/packages/${COMPONENT}"

if [ "$STRIP" -gt 0 ]; then
  tar xzf "$TARBALL" --strip-components "$STRIP" -C "$INSTALL_TREE"
else
  tar xzf "$TARBALL" -C "$INSTALL_TREE"
fi

# Directories that belong to the package as state, not as shipped content.
# ${INSTALL_TREE:?} so an empty variable aborts rather than letting rm -rf reach /.
for relative in $PRUNE; do
  rm -rf "${INSTALL_TREE:?}/${relative}"
done

mkdir -p "${DEST}/usr/lib/systemd/system"
for path in "$HOME_DIR" "$LOGS_DIR" "$DEPLOY_DIR" "$RRD_DIR" "$REPORTS_DIR"; do
  [ -n "$path" ] && mkdir -p "${DEST}${path}"
done

UNIT_SRC="${INSTALL_TREE}/etc/${SERVICE_UNIT}"
if [ ! -f "$UNIT_SRC" ]; then
  echo "expected systemd unit not found: ${UNIT_SRC}" >&2
  exit 1
fi
if [ "$SERVICE_MODE" = "copy" ]; then
  cp "$UNIT_SRC" "${DEST}/usr/lib/systemd/system"
else
  mv "$UNIT_SRC" "${DEST}/usr/lib/systemd/system"
fi

if [ -n "$INIT_SCRIPT" ]; then
  INIT_SRC="${INSTALL_TREE}/etc/${INIT_SCRIPT}"
  if [ ! -f "$INIT_SRC" ]; then
    echo "expected init script not found: ${INIT_SRC}" >&2
    exit 1
  fi
  mv "$INIT_SRC" "${INSTALL_TREE}/bin/${COMPONENT}"
fi

echo "  install tree : ${INSTALL_TREE}"
echo "  systemd unit : ${DEST}/usr/lib/systemd/system/${SERVICE_UNIT}"
