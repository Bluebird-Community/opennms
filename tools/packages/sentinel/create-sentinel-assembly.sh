#!/usr/bin/env bash
set -e

export OPTS_MAVEN="-Daether.connector.basic.threads=1 -Daether.connector.resumeDownloads=false"
export OPTS_SKIP_TESTS="-DskipITs=true -Dmaven.test.skip.exec=true -DskipTests=true"
export OPTS_SKIP_TARBALL="-Dbuild.skip.tarball=true"
export OPTS_ASSEMBLIES="-Passemblies"
export OPTS_PROFILES="-Prun-expensive-tasks"

OPTS_ENABLE_SNAPSHOTS=""
OPTS_PRODUCTION=""

TOPDIR="$(pwd)"
MYDIR="$(dirname "$0")"
MYDIR="$(cd "$MYDIR"; pwd)"

SKIP_COMPILE=0

printHelp() {
	echo "usage: $0 [-h] [-s] [-c]"
	echo ""
	echo "	-h    this help"
	echo "	-s    enable snapshot downloads"
	echo "	-c    skip compilation"
}

while getopts "chs" OPT
do
	case "$OPT" in
		h)
			printHelp
			exit 1
			;;
		s)
			OPTS_ENABLE_SNAPSHOTS="-Denable.snapshots=true"
			;;
		c)
			SKIP_COMPILE=1
			;;
		*)
			echo "Unknown option: $OPT"
			exit 1
			;;
	esac
done

case "${CIRCLE_BRANCH}" in
	"master"*|"release-"*|develop)
		OPTS_PRODUCTION="-Dbuild.type=production"
	;;
esac

# always build the root POM, just to be sure inherited properties/plugin/dependencies are right
echo "=== Building root POM ==="
"${TOPDIR}/mvnw" \
	$OPTS_SKIP_TESTS \
	$OPTS_SKIP_TARBALL \
	$OPTS_ENABLE_SNAPSHOTS \
	$OPTS_PRODUCTION \
	--projects org.opennms:opennms \
	--builder smart \
	--threads ${CCI_MAXCPU:-2} \
	install

COMPILE="./mvnw"

echo ""
if [ $SKIP_COMPILE -eq 1 ]; then
	echo "=== Compiling Assemblies ==="
	OPTS_PROFILES="${OPTS_PROFILES} -PskipCompile"
	# assemble.pl used to do this: run against opennms-full-assembly with the default
	# build profile. Note this branch also passes --projects
	# org.opennms.assemblies:org.opennms.assemblies.sentinel below, which is not a module
	# of that reactor, so this path looks untested. Translated faithfully rather than
	# repaired, since changing it is out of scope here.
	COMPILE="./mvnw --file opennms-full-assembly/pom.xml -Dbuild.profile=default"
else
	echo "=== Compiling Projects + Assemblies ==="
fi

echo ""
"$COMPILE" \
	$OPTS_MAVEN \
	$OPTS_SKIP_TESTS \
	$OPTS_SKIP_TARBALL \
	$OPTS_ENABLE_SNAPSHOTS \
	$OPTS_PROFILES \
	$OPTS_PRODUCTION \
	$OPTS_ASSEMBLIES \
	-DvaadinJavaMaxMemory=${CCI_VAADINJAVAMAXMEM:-1g} \
	-DmaxCpus=${CCI_MAXCPU:-2} \
	--projects "org.opennms.assemblies:org.opennms.assemblies.sentinel" \
	--also-make \
	--builder smart \
	--threads ${CCI_MAXCPU:-2} \
	install

echo "=== Finished ==="
echo "Your tarball is in:" opennms-assemblies/sentinel/target/org.opennms.assemblies.sentinel-*-sentinel.tar.gz
