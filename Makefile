##
# Makefile to build OpenNMS from source
##
.DEFAULT_GOAL := quick-build

SHELL                 := /bin/bash -o nounset -o pipefail -o errexit
WORKING_DIRECTORY     := $(shell pwd)
SITE_FILE             := antora-playbook-local.yml
ARTIFACTS_DIR         := target/artifacts
MAVEN_SHARDS          := 1
MAVEN_SHARD_IDX       := 0
MAVEN_BIN             := ./mvnw
# Maven CLI flags and JVM options now live where Maven reads them itself:
#   .mvn/maven.config  build hygiene flags, previously pasted into 16 call sites
#   .mvn/jvm.config    heap and GC settings, previously an exported MAVEN_OPTS
# Plain ./mvnw therefore behaves the same way the build does.
#
# What stays here is CI test policy rather than build hygiene. The rerun flags mask
# flaky tests, so they must not silently change what a developer sees locally. See #207.
MAVEN_ARGS            := -Dfailsafe.rerunFailingTestsCount=2 -Dsurefire.rerunFailingTestsCount=2

GIT_BRANCH            := $(shell git branch | grep \* | cut -d' ' -f2)
OPENNMS_VERSION       ?= $(shell grep '<version>' pom.xml | head -1 | sed -e 's/.*<version>\(.*\)<\/version>.*/\1/')
VERSION               := $(shell echo ${OPENNMS_VERSION} | sed -e 's,-SNAPSHOT,,')
RELEASE_BRANCH        := $(shell echo ${GIT_BRANCH} | sed -e 's,/,-,g')
ifndef CIRCLE_BUILD_NUM
override RELEASE_BUILD_NUM = 0
endif

RELEASE_BUILD_NUM     ?= ${CIRCLE_BUILD_NUM}
RELEASE_COMMIT        := $(shell git rev-parse --short HEAD)
OPEN_FILES_LIMIT      := 20000
CURRENT_FILES_LIMIT   := $(shell ulimit -n 2>/dev/null || echo 0)
RELEASE_VERSION       := UNSET.0.0
RELEASE_BRANCH        := main
PUSH_RELEASE          := false
MAJOR_VERSION         := $(shell echo $(RELEASE_VERSION) | cut -d. -f1)
MINOR_VERSION         := $(shell echo $(RELEASE_VERSION) | cut -d. -f2)
PATCH_VERSION         := $(shell echo $(RELEASE_VERSION) | cut -d. -f3)
SNAPSHOT_VERSION      := $(MAJOR_VERSION).$(MINOR_VERSION).$(shell expr $(PATCH_VERSION) + 1)-SNAPSHOT
RELEASE_LOG           := target/release.log
OK                    := "[ 👍 ]"
FAILED                := "[ 🤬 ]"
SKIP                  := "[ ⏭️ ]"
SKIP_UI_TESTS         := false
JAVA_MAJOR_VERSION    := 21

# Package requirements
PKG_CORE_HOME         := /opt/opennms
PKG_CORE_RRD          := /var/lib/opennms/rrd
PKG_CORE_REPORTS      := /var/lib/opennms/reports
PKG_CORE_LOGS         := /var/log/opennms
PKG_CORE_DEPLOY       := /var/lib/opennms/deploy

PKG_MINION_HOME       := /opt/minion
PKG_MINION_LOGS       := /var/log/minion
PKG_MINION_DEPLOY     := /var/lib/minion/deploy

PKG_SENTINEL_HOME     := /opt/sentinel
PKG_SENTINEL_LOGS     := /var/log/sentinel
PKG_SENTINEL_DEPLOY   := /var/lib/sentinel/deploy

BUILD_ROOT            := $(ARTIFACTS_DIR)/buildroot
PKG_RELEASE           := $(RELEASE_BUILD_NUM)
MAINTAINER_EMAIL      ?= maintainer@bluebirdops.org
ARCH                  ?= amd64

INSTALL_VERSION       := ${OPENNMS_VERSION}-${RELEASE_COMMIT}
DEPLOY_BASE_IMAGE     := quay.io/bluebird/deploy-base:5.0.0.b46
BUILD_DATE            := $(shell date '+%Y%m%d')
OCI_PLATFORM          := linux/$(shell uname -m)
OCI_REGISTRY          ?= quay.io
OCI_REGISTRY_USER     ?= changeme
OCI_REGISTRY_PASSWORD ?= changeme
OCI_REGISTRY_ORG      ?= changeme
TRIVY_ARGS            := --java-db-repository quay.io/bluebird/trivy-java-db:1 --timeout 30m --format json

define setversion
	@echo -n "💅 Set Maven release version:   "
	@$(MAVEN_BIN) versions:set -DnewVersion=$(1) >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@echo -n "💅 Set version Karaf Test case: "
	@sed -i.versionsBackup 's/$(OPENNMS_VERSION)/$(1)/g' opennms-full-assembly/src/test/java/org/opennms/assemblies/karaf/OnmsKarafTestCase.java >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@echo -n "💅 Set version web assets:      "
	@sed -i.versionsBackup 's/$(OPENNMS_VERSION)/$(1)/g' core/web-assets/package.json >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@echo -n "💅 Set version Antora docs:     "
	@sed -i.versionsBackup 's/$(OPENNMS_VERSION)/$(1)/g' docs/antora.yml >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@echo -n "💅 Set version Maven deploy:    "
	@$(MAVEN_BIN) versions:set -DnewVersion=$(1) --file deploy/pom.xml >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@echo -n "💅 Set version OSGi:            "
	@sed -i.versionsBackup 's/\<opennms\.osgi\.version\>$(VERSION).SNAPSHOT\<\/opennms\.osgi\.version\>/\<opennms\.osgi\.version\>$(1)\<\/opennms\.osgi\.version\>/g' pom.xml >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@echo -n "💅 Set version e2e-tests:      "
	@$(MAVEN_BIN) versions:set -DnewVersion=$(1) --file e2e-tests/pom.xml >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
endef

.PHONY: help
help: ## Show this help
	@build-tooling/make-help.sh $(MAKEFILE_LIST)


.PHONY: deps-build
deps-build:
	@echo "Check build dependencies: Java JDK, NodeJS, PNMP, paste, python3"
	@echo -n "👮‍♀️ Check Maven binary:          "
	@command -v $(MAVEN_BIN) > /dev/null
	@echo $(OK)
	@echo -n "👮‍♀️ Check Java runtime:          "
	@command -v java > /dev/null
	@echo $(OK)
	@echo -n "👮‍♀️ Check Java compiler:         "
	@command -v javac > /dev/null
	@echo $(OK)
	@echo -n "👮‍♀️ Check Node Package manager:  "
	@command -v npm > /dev/null
	@echo $(OK)
	@echo -n "👮‍♀️ Check paste binary:          "
	@command -v paste > /dev/null
	@echo $(OK)
	@echo -n "👮‍♀️ Check Python3:               "
	@command -v python3 > /dev/null
	@echo $(OK)
	@echo -n "👮‍♀️ Check pnmp:                  "
	@command -v pnpm > /dev/null
	@echo $(OK)
	@mkdir -p $(ARTIFACTS_DIR)
	@echo -n "👮‍♀️ Check Java version $(JAVA_MAJOR_VERSION):       "
	@java -version 2>&1 | grep '$(JAVA_MAJOR_VERSION)\..*' >/dev/null
	@echo $(OK)
	@echo -n "👮‍♀️ Check file limits ($(OPEN_FILES_LIMIT)):   "
	@if [ "$$(ulimit -n)" -lt "$(OPEN_FILES_LIMIT)" ]; then \
	  echo $(FAILED); \
	  echo ""; \
	  echo "Your open file limit is $(CURRENT_FILES_LIMIT) and $(OPEN_FILES_LIMIT) is required. Please adjust your system settings."; \
	  echo "You can set the open file limit using 'ulimit -n $(OPEN_FILES_LIMIT)' or by editing your shell configuration file (e.g., ~/.bashrc)."; \
	  exit 1; \
	fi
	@echo $(OK)

.PHONY: deps-packages
deps-packages:
	@echo "Check dependencies to build packages"
	@command -v nfpm >/dev/null 2>&1 || { \
		echo "nfpm not found. Install it to build packages:"; \
		echo "  brew install nfpm"; \
		echo "  go install github.com/goreleaser/nfpm/v2/cmd/nfpm@latest"; \
		echo "  (CI installs a pinned binary via .github/actions/setup-nfpm)"; \
		exit 1; \
	}

.PHONY: deps-docs
deps-docs:
	@echo "Check documentation build dependency: antora"
	command -v antora

.PHONY: deps-oci
deps-oci:
	@echo "Check OCI build dependency: docker"
	command -v docker
	command -v tar

.PHONY: deps-oci-sbom
deps-oci-sbom:
	@echo "Check OCI SBOM dependency: syft"
	command -v syft

.PHONY: deps-oci-sec-scan
deps-oci-sec-scan:
	@echo "Check OCI security scan dependency: trivy"
	command -v trivy

.PHONY: deps-sonar
deps-sonar:
	@echo "Check code coverage test dependency: sonar-scanner"
	command -v sonar-scanner

.PHONY: deps-oci-layers
deps-oci-layers:
	@echo "Show OCI container layer usage: dive"
	command -v dive

.PHONY: show-info
show-info:
	@echo "MAVEN_ARGS (test policy only)=\"$(MAVEN_ARGS)\""
	@echo "--- .mvn/maven.config (CLI flags Maven applies itself) ---"
	@grep -v '^#' .mvn/maven.config | grep -v '^$$' | sed 's/^/  /'
	@echo "--- .mvn/jvm.config (Maven JVM options) ---"
	@sed 's/^/  /' .mvn/jvm.config
	@$(MAVEN_BIN) --version

.PHONY: validate
##@ Build
validate: deps-build show-info ## Fail quickly by checking project structure with mvn:clean
	$(MAVEN_BIN) clean
	$(MAVEN_BIN) clean --file opennms-full-assembly/pom.xml -Dbuild.profile=default

.PHONY: maven-structure-graph
maven-structure-graph: deps-build show-info ## Generate a JSON file with the Maven structure used to generate test class list
	$(MAVEN_BIN) org.opennms.maven.plugins:structure-maven-plugin:1.0:structure $(MAVEN_ARGS) -Dbuild.profile=default -Droot.dir=$(WORKING_DIRECTORY) --fail-at-end -Prun-expensive-tasks -Pbuild-bamboo

.PHONY: test-lists
# BASELINE_REF env var (if set) is read by find-tests.py via os.environ to scope test discovery.
# FULL_BUILD=true makes find-tests.py consider all reactor modules (--changes-only=false);
# maven-structure-graph runs in both cases so generate-test-modules emits a correctly-scoped
# test_modules list (only modules that contain tests AND are in the root reactor).
test-lists: maven-structure-graph ## Generate a list with all JUnit and Integration Test class names for splitting jobs
	mkdir -p $(ARTIFACTS_DIR)/tests
	$(eval CHANGES_ONLY := $(if $(filter true,$(FULL_BUILD)),false,true))
	python3 .cicd-assets/find-tests/find-tests.py generate-test-lists --changes-only="$(CHANGES_ONLY)" --output-unit-test-classes="$(ARTIFACTS_DIR)/tests/unit_tests_classnames" --output-integration-test-classes="$(ARTIFACTS_DIR)/tests/integration_tests_classnames" .
	cat $(ARTIFACTS_DIR)/tests/*_tests_classnames | python3 .cicd-assets/find-tests/find-tests.py generate-test-modules --output="$(ARTIFACTS_DIR)/tests/test_modules" .
	# Every e2e shard regenerates this list in its own job and then slices it with
	# awk "NR%shards==idx", which only partitions correctly when all shards see an
	# identical ordering. find(1) returns directory order, which is not stable across
	# checkouts, so sort -u is what makes the slicing shard-safe -- without it the
	# shards both duplicate and skip tests. Same rationale as find-tests.py's
	# sorted(set(...)) for the unit/integration lists.
	find e2e-tests -type f -regex ".*\/src\/test\/java\/.*IT.*\.java" | sed -e 's#^.*src/test/java/\(.*\)\.java#\1#' | tr "/" "." | sort -u > $(ARTIFACTS_DIR)/tests/e2e_tests_classnames

.PHONY: compile
compile: maven-structure-graph ## Compile OpenNMS from source code with runs expensive tasks doing
	$(MAVEN_BIN) install $(MAVEN_ARGS) -DskipTests=true -Dbuild.profile=default -Droot.dir=$(WORKING_DIRECTORY) -Dbuild.skip.tarball=false -Prun-expensive-tasks -Psmoke -Dbuild.type=production -Dbuild.sbom=true 2>&1 | tee $(ARTIFACTS_DIR)/mvn.compile.log

.PHONY: compile-ui
compile-ui: ## Build the Vue UI with pnpm, set SKIP_UI_TESTS=true to skip its tests
	cd ui && pnpm install && pnpm build && \
	if [ "$(SKIP_UI_TESTS)" == "false" ]; then pnpm test; else echo "Skip UI Tests"; fi;

.PHONY: assemble
assemble: deps-build show-info ## Assemble the build artifacts with expensive tasks for a production build
	$(MAVEN_BIN) install $(MAVEN_ARGS) -DskipTests=true -Dbuild.profile=default -Droot.dir=$(WORKING_DIRECTORY) -Dopennms.home=$(PKG_CORE_HOME) -Dinstall.version=$(INSTALL_VERSION) -Pbuild-bamboo -Prun-expensive-tasks -Dbuild.skip.tarball=false -Denable.license=true -Dbuild.type=production -Dbuild.sbom=true --file opennms-full-assembly/pom.xml 2>&1 | tee $(ARTIFACTS_DIR)/mvn.assemble.log

.PHONY: quick-build
quick-build: quick-compile quick-assemble ## Runs a quick compile and quick assemble for development

.PHONY: quick-compile
quick-compile: maven-structure-graph ## Quick compile to get fast feedback for development
	# Pre-warm node/pnpm cache so the parallel reactor (-T 1C) below doesn't race
	# on the shared ~/.m2/repository/com/github/eirslett/pnpm/<v>/pnpm-<v>.tar.gz
	# download path. Only :org.opennms.ui and :org.opennms.core.web-assets bind
	# frontend-maven-plugin:install-node-and-pnpm; running them serially first
	# populates the cache, after which the parallel pass becomes a no-op for that goal.
	$(MAVEN_BIN) $(MAVEN_ARGS) -DskipTests=true -Dbuild.profile=default -Droot.dir=$(WORKING_DIRECTORY) -pl :org.opennms.ui,:org.opennms.core.web-assets com.github.eirslett:frontend-maven-plugin:install-node-and-pnpm 2>&1 | tee $(ARTIFACTS_DIR)/mvn.prewarm-frontend.log
	$(MAVEN_BIN) install $(MAVEN_ARGS) -T 1C -DskipTests=true -Dbuild.profile=default -Droot.dir=$(WORKING_DIRECTORY) -Dcyclonedx.skip=true 2>&1 | tee $(ARTIFACTS_DIR)/mvn.quick-compile.log

.PHONY: quick-assemble
quick-assemble: deps-build show-info ## Quick assemble to run on a build local system
	$(MAVEN_BIN) install $(MAVEN_ARGS) -DskipTests=true -Dbuild.profile=default -Droot.dir=$(WORKING_DIRECTORY) -Dopennms.home=$(PKG_CORE_HOME) -Dinstall.version=$(INSTALL_VERSION) --file opennms-full-assembly/pom.xml 2>&1 | tee $(ARTIFACTS_DIR)/mvn.quick-assemble.log

# Reactor artifact handoff.
#
# CI used to run quick-compile + quick-assemble in every test job, which meant one
# commit was compiled from scratch 22 times per run (~13 min each). Instead the build
# job packages the reactor artifacts it just installed and each test job restores them
# into its local Maven repository, so the test jobs only compile the modules under test.
#
# What is included: jars (including test-jars), poms, and the small side artifacts test
# modules can resolve -- Karaf feature xml, properties and cfg files.
#
# What is excluded, and why:
#   *.tar.gz / *.zip  ~7.5G of OpenNMS assembly archives
#   *.war             1.4G, dominated by assemblies.webapp-full
# Every consumer of one of those is an assembly module, and assembly modules are built
# by the build job and never rebuilt by a test job. Excluding them takes the handoff
# from ~7.7G to ~200M. Re-verify this if a test module ever grows such a dependency.
#
# Sources/javadoc jars are excluded because nothing in the test path resolves them.
M2_REPO               ?= $(HOME)/.m2/repository
REACTOR_ARTIFACTS     := $(ARTIFACTS_DIR)/reactor-m2.tar.gz
# Escape hatch / kill-switch: set to --also-make to restore the old self-contained
# behaviour of the unit-tests and integration-tests targets.
REACTOR_ALSO_MAKE     ?=

.PHONY: package-reactor-artifacts
package-reactor-artifacts: ## Package the installed reactor artifacts for hand-off to test jobs
	mkdir -p $(ARTIFACTS_DIR)
	cd $(M2_REPO) && find org/opennms -path "*/$(OPENNMS_VERSION)/*" -type f \
	  \( -name '*.jar' -o -name '*.pom' -o -name '*.xml' -o -name '*.properties' -o -name '*.cfg' \) \
	  ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
	  | tar -czf $(WORKING_DIRECTORY)/$(REACTOR_ARTIFACTS) -T -
	@echo "Packaged reactor artifacts for $(OPENNMS_VERSION): $$(du -h $(REACTOR_ARTIFACTS) | cut -f1)"

# The handoff is shipped as a run-scoped GitHub artifact, so it cannot carry content
# from a different commit the way a restore-keys cache hit could. The assertion below
# is a cheap backstop: fail loudly rather than run tests against an empty repository.
.PHONY: restore-reactor-artifacts
restore-reactor-artifacts: ## Restore a packaged reactor hand-off into the local Maven repository
ifeq (,$(wildcard $(REACTOR_ARTIFACTS)))
	@echo "Can't restore reactor artifacts, $(REACTOR_ARTIFACTS) is missing."
	@echo "It is published by the build job; run 'make quick-build package-reactor-artifacts' locally."
	@exit 1
else
	mkdir -p $(M2_REPO)
	tar -xzf $(REACTOR_ARTIFACTS) -C $(M2_REPO)
	@test -n "$$(find $(M2_REPO)/org/opennms -path '*/$(OPENNMS_VERSION)/*' -name '*.jar' -print -quit)" \
	  || { echo "Restored the handoff but found no org.opennms jars for $(OPENNMS_VERSION)"; exit 1; }
	@echo "Restored reactor artifacts for $(OPENNMS_VERSION) into $(M2_REPO)"
endif

.PHONY: core-oci
##@ Container images
core-oci: ## Build container image for Horizon Core, tag: local/core:latest
	@build-tooling/build-oci-image.sh --component core \
		--version $(OPENNMS_VERSION) \
		--install-version $(INSTALL_VERSION) \
		--revision $(RELEASE_COMMIT) \
		--base-image $(DEPLOY_BASE_IMAGE) \
		--platform $(OCI_PLATFORM) \
		--build-date $(BUILD_DATE)

.PHONY: minion-oci
minion-oci: ## Build container image for Minion, tag local/minion:latest
	@build-tooling/build-oci-image.sh --component minion \
		--version $(OPENNMS_VERSION) \
		--install-version $(INSTALL_VERSION) \
		--revision $(RELEASE_COMMIT) \
		--base-image $(DEPLOY_BASE_IMAGE) \
		--platform $(OCI_PLATFORM) \
		--build-date $(BUILD_DATE) \
		--branch $(GIT_BRANCH) \
		--build-number $(RELEASE_BUILD_NUM)

.PHONY: sentinel-oci
sentinel-oci: ## Build container image for Sentinel, tag local/sentinel:latest
	@build-tooling/build-oci-image.sh --component sentinel \
		--version $(OPENNMS_VERSION) \
		--install-version $(INSTALL_VERSION) \
		--revision $(RELEASE_COMMIT) \
		--base-image $(DEPLOY_BASE_IMAGE) \
		--platform $(OCI_PLATFORM) \
		--build-date $(BUILD_DATE)

.PHONY: show-core-oci
show-core-oci: deps-oci-layers core-oci ## Analyze the OCI image using dive, tag local/horizon:latest
	CI=true dive local/core:latest

.PHONY: show-minion-oci
show-minion-oci: deps-oci-layers minion-oci ## Analyze the OCI image using dive, tag local/minion:latest
	CI=true dive local/minion:latest

.PHONY: show-sentinel-oci
show-sentinel-oci: deps-oci-layers sentinel-oci ## Analyze the OCI image using dive, tag local/sentinel:latest
	CI=true dive local/sentinel:latest

.PHONY: core-oci-sbom
##@ Dependencies and scans
core-oci-sbom: deps-oci-sbom core-oci ## Create software bill of material for the Core container image
	syft scan local/core:latest -o cyclonedx=$(ARTIFACTS_DIR)/oci/core-oci-sbom.xml --quiet

.PHONY: minion-oci-sbom
minion-oci-sbom: deps-oci-sbom minion-oci ## Create software bill of material for the Minion container image
	syft scan local/minion:latest -o cyclonedx=$(ARTIFACTS_DIR)/oci/minion-oci-sbom.xml --quiet

.PHONY: sentinel-oci-sbom
sentinel-oci-sbom: deps-oci-sbom sentinel-oci ## Create software bill of material for the Sentinel container image
	syft scan local/sentinel:latest -o cyclonedx=$(ARTIFACTS_DIR)/oci/sentinel-oci-sbom.xml --quiet

.PHONY: core-oci-sec-scan
core-oci-sec-scan: deps-oci-sec-scan core-oci ## Create security scan report for the Core container image
	trivy image local/core:latest $(TRIVY_ARGS) -o $(ARTIFACTS_DIR)/oci/core-trivy-report.json

.PHONY: minion-oci-sec-scan
minion-oci-sec-scan: deps-oci-sec-scan minion-oci ## Create security scan report for the Core container image
	trivy image local/minion:latest $(TRIVY_ARGS) -o $(ARTIFACTS_DIR)/oci/minion-trivy-report.json

.PHONY: sentinel-oci-sec-scan
sentinel-oci-sec-scan: deps-oci-sec-scan sentinel-oci ## Create security scan report for the Core container image
	trivy image local/sentinel:latest $(TRIVY_ARGS) -o $(ARTIFACTS_DIR)/oci/sentinel-trivy-report.json

# Run just the a very limited set of integration tests to verify the application comes up and we have something we can
# at least work with.
.PHONY: smoke
# smoke's recipe runs a hardcoded -Dit.test subset and does not read $(ARTIFACTS_DIR)/tests/*,
# so it doesn't need test-lists. Skipping the dep avoids paying maven-structure-graph on every
# commit (build-with-smoke-test runs unconditionally).
##@ Tests
smoke: deps-oci core-oci ## Simple smoke test to verify the application can be started by using the MenuHeaderIT and SinglePortFlowsIT test
	$(MAVEN_BIN) install $(MAVEN_ARGS) -N -DskipTests=false -DskipITs=false -DfailIfNoTests=false -Dtest.fork.count=1 -Dit.test="MenuHeaderIT,SinglePortFlowsIT" --fail-fast -Dfailsafe.skipAfterFailureCount=1 -P!smoke.all -Psmoke.core --file e2e-tests/pom.xml 2>&1 | tee $(ARTIFACTS_DIR)/mvn.smoke-quick.log

.PHONY: core-e2e
core-e2e: deps-oci test-lists core-oci minion-oci sentinel-oci ## Run full end to end test suite against the Core components. Specific tests can be set with: CORE_E2E_TESTS=MyTestIT-1,MyTestIT-2, ...
	$(eval CORE_E2E_TESTS ?= $(shell build-tooling/shard-list.sh --file $(ARTIFACTS_DIR)/tests/e2e_tests_classnames --shards $(MAVEN_SHARDS) --index $(MAVEN_SHARD_IDX)))
	$(MAVEN_BIN) install $(MAVEN_ARGS) -N -DskipTests=false -DskipITs=false -DfailIfNoTests=false -Dtest.fork.count=1 -Dit.test="$(CORE_E2E_TESTS)" --fail-fast -Dfailsafe.skipAfterFailureCount=1 -P!smoke.all -Psmoke.core --file e2e-tests/pom.xml 2>&1 | tee $(ARTIFACTS_DIR)/mvn.core-smoke.log

.PHONY: minion-e2e
minion-e2e: deps-oci test-lists minion-oci sentinel-oci core-oci ## Run end to end test suite against the Minion components. Specific tests can be set with: MINION_E2E_TESTS=MyTestIT-1,MyTestIT-2, ...
	$(eval MINION_E2E_TESTS ?= $(shell build-tooling/shard-list.sh --file $(ARTIFACTS_DIR)/tests/e2e_tests_classnames --shards $(MAVEN_SHARDS) --index $(MAVEN_SHARD_IDX)))
	$(MAVEN_BIN) install $(MAVEN_ARGS) -N -DskipTests=false -DskipITs=false -DfailIfNoTests=false -Dtest.fork.count=1 -Dit.test="$(MINION_E2E_TESTS)" --fail-fast -Dfailsafe.skipAfterFailureCount=1 -P!smoke.all -Psmoke.minion --file e2e-tests/pom.xml 2>&1 | tee $(ARTIFACTS_DIR)/mvn.minion-smoke.log

.PHONY: sentinel-e2e
sentinel-e2e: deps-oci test-lists sentinel-oci minion-oci core-oci ## Run end to end test suite against the Sentinel components. Specific tests can be set with: SENTINEL_E2E_TESTS=MyTestIT-1,MyTestIT-2, ...
	$(eval SENTINEL_E2E_TESTS ?= $(shell build-tooling/shard-list.sh --file $(ARTIFACTS_DIR)/tests/e2e_tests_classnames --shards $(MAVEN_SHARDS) --index $(MAVEN_SHARD_IDX)))
	$(MAVEN_BIN) install $(MAVEN_ARGS) -N -DskipTests=false -DskipITs=false -DfailIfNoTests=false -Dtest.fork.count=1 -Dit.test="$(SENTINEL_E2E_TESTS)" --fail-fast -Dfailsafe.skipAfterFailureCount=1 -P!smoke.all -Psmoke.sentinel --file e2e-tests/pom.xml 2>&1 | tee $(ARTIFACTS_DIR)/mvn.sentinel-smoke.log

# We allow users here to pass a specific unit tests and projects to run.
# Otherwise we run the full test suite
.PHONY: unit-tests
unit-tests: test-lists spinup-postgres ## Run full unit test suite, you can run specific tests in a projects with:
	$(eval U_TESTS ?= $(shell build-tooling/shard-list.sh --file $(ARTIFACTS_DIR)/tests/unit_tests_classnames --skip ./.cicd-assets/_skipTests.txt --shards $(MAVEN_SHARDS) --index $(MAVEN_SHARD_IDX)))
	$(eval TEST_PROJECTS ?= $(shell cat ${ARTIFACTS_DIR}/tests/test_modules | paste -s -d, -))
	# Parallel compiling with -T 1C works, but it doesn't for tests.
	# No --also-make: the reactor artifacts are restored into the local Maven repository
	# by restore-reactor-artifacts, so upstream modules resolve as installed artifacts
	# instead of being rebuilt. Set REACTOR_ALSO_MAKE=--also-make for a self-contained
	# build on a machine that has not run quick-build for this commit.
	$(MAVEN_BIN) install $(MAVEN_ARGS) -T 1C -DskipTests=true -DskipITs=true -Dbuild.profile=default -Droot.dir=$(WORKING_DIRECTORY) -Dfailsafe.skipAfterFailureCount=1 -P!checkstyle -P!production -Pbuild-bamboo -Dbuild.skip.tarball=true -Dmaven.test.skip.exec=true --fail-fast $(REACTOR_ALSO_MAKE) --projects "$(TEST_PROJECTS)" 2>&1 | tee $(ARTIFACTS_DIR)/mvn.tests.compile.log
	if [ $(command -v ionice) ]; then ionice; fi; nice $(MAVEN_BIN) install $(MAVEN_ARGS) -DskipTests=false -DskipITs=true -DskipSurefire=false -DskipFailsafe=true -Dbuild.profile=default -Droot.dir=$(WORKING_DIRECTORY) -Dfailsafe.skipAfterFailureCount=1 -P!checkstyle -P!production -Pbuild-bamboo -Pcoverage -Dbuild.skip.tarball=true -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -DrunPingTests=false --fail-fast -Dorg.opennms.core.test-api.dbCreateThreads=1 -Dorg.opennms.core.test-api.snmp.useMockSnmpStrategy=false -Dtest="$(U_TESTS)" --projects "$(TEST_PROJECTS)" 2>&1 | tee $(ARTIFACTS_DIR)/mvn.u_tests.log

.PHONY: integration-tests
integration-tests: test-lists spinup-postgres ## Run full integration test suit, you can run specific integration tests in a project with:
	$(eval I_TESTS ?= $(shell build-tooling/shard-list.sh --file $(ARTIFACTS_DIR)/tests/integration_tests_classnames --skip ./.cicd-assets/_skipIntegrationTests.txt --shards $(MAVEN_SHARDS) --index $(MAVEN_SHARD_IDX)))
	$(eval TEST_PROJECTS ?= $(shell cat $(ARTIFACTS_DIR)/tests/test_modules | paste -s -d, -))
	# Parallel compiling with -T 1C works, but it doesn't for tests.
	# See the unit-tests target above for why --also-make is not used here.
	$(MAVEN_BIN) install $(MAVEN_ARGS) -T 1C -DskipTests=true -DskipITs=true -Dbuild.profile=default -Droot.dir=$(WORKING_DIRECTORY) -Dfailsafe.skipAfterFailureCount=1 -P!checkstyle -P!production -Pbuild-bamboo -Dbuild.skip.tarball=true -Dmaven.test.skip.exec=true --fail-fast $(REACTOR_ALSO_MAKE) --projects "$(TEST_PROJECTS)" 2>&1 | tee $(ARTIFACTS_DIR)/mvn.tests.compile.log
	if [ $(command -v ionice) ]; then ionice; fi; nice $(MAVEN_BIN) install $(MAVEN_ARGS) -DskipTests=false -DskipITs=false -DskipSurefire=true -DskipFailsafe=false -Dbuild.profile=default -Droot.dir=$(WORKING_DIRECTORY) -Dfailsafe.skipAfterFailureCount=1 -P!checkstyle -P!production -Pbuild-bamboo -Pcoverage -Dbuild.skip.tarball=true -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -DrunPingTests=false --fail-fast -Dorg.opennms.core.test-api.dbCreateThreads=1 -Dorg.opennms.core.test-api.snmp.useMockSnmpStrategy=false -Dtest="$(U_TESTS)" -Dit.test="$(I_TESTS)" --projects "$(TEST_PROJECTS)" 2>&1 | tee $(ARTIFACTS_DIR)/mvn.i_tests.log

.PHONY: code-coverage
code-coverage: deps-sonar ## Test code coverage with SonarScanner CLI
	@build-tooling/code-coverage.sh --artifacts-dir $(ARTIFACTS_DIR)

.PHONY: core-pkg-buildroot
core-pkg-buildroot:
	@build-tooling/build-package-root.sh --component core \
		--version $(OPENNMS_VERSION) \
		--build-root $(BUILD_ROOT) \
		--artifacts-dir $(ARTIFACTS_DIR) \
		--home $(PKG_CORE_HOME) \
		--logs $(PKG_CORE_LOGS) \
		--deploy $(PKG_CORE_DEPLOY) \
		--rrd $(PKG_CORE_RRD) \
		--reports $(PKG_CORE_REPORTS)

.PHONY: core-pkg-deb
core-pkg-deb: deps-packages core-pkg-buildroot ## Build Core Debian packages
	@echo "==== Building Debian Core Packages ===="
	@echo
	@echo "Version:     " $(OPENNMS_VERSION)
	@echo "Release:     " $(PKG_RELEASE)
	@echo
	ARCH="$(ARCH)" OPENNMS_VERSION="$(OPENNMS_VERSION)" PKG_RELEASE="$(PKG_RELEASE)" MAINTAINER_EMAIL="$(MAINTAINER_EMAIL)" \
		nfpm package --packager deb --config nfpm/nfpm-core.yaml --target "$(ARTIFACTS_DIR)/packages/core/"

.PHONY: core-pkg-rpm
core-pkg-rpm: deps-packages core-pkg-buildroot ## Build Core RPM packages
	@echo "==== Building RPM Core Packages ===="
	@echo
	@echo "Version:     " $(OPENNMS_VERSION)
	@echo "Release:     " $(PKG_RELEASE)
	@echo
	ARCH="$(ARCH)" OPENNMS_VERSION="$(OPENNMS_VERSION)" PKG_RELEASE="$(PKG_RELEASE)" MAINTAINER_EMAIL="$(MAINTAINER_EMAIL)" \
		nfpm package --packager rpm --config nfpm/nfpm-core.yaml --target "$(ARTIFACTS_DIR)/packages/core/"

.PHONY: minion-pkg-buildroot
minion-pkg-buildroot:
	@build-tooling/build-package-root.sh --component minion \
		--version $(OPENNMS_VERSION) \
		--build-root $(BUILD_ROOT) \
		--artifacts-dir $(ARTIFACTS_DIR) \
		--home $(PKG_MINION_HOME) \
		--logs $(PKG_MINION_LOGS) \
		--deploy $(PKG_MINION_DEPLOY)

.PHONY: minion-pkg-deb
minion-pkg-deb: deps-packages minion-pkg-buildroot ## Build Minion Debian packages
	@echo "==== Building Debian Minion Packages ===="
	@echo
	@echo "Version:     " $(OPENNMS_VERSION)
	@echo "Release:     " $(DEB_PKG_RELEASE)
	@echo
	ARCH="$(ARCH)" OPENNMS_VERSION="$(OPENNMS_VERSION)" PKG_RELEASE="$(PKG_RELEASE)" MAINTAINER_EMAIL="$(MAINTAINER_EMAIL)" \
		nfpm package --packager deb --config nfpm/nfpm-minion.yaml --target "$(ARTIFACTS_DIR)/packages/minion/"

.PHONY: minion-pkg-rpm
minion-pkg-rpm: deps-packages minion-pkg-buildroot ## Build Minion RPM packages
	@echo "==== Building RPM Minion Packages ===="
	@echo
	@echo "Version:     " $(OPENNMS_VERSION)
	@echo "Release:     " $(DEB_PKG_RELEASE)
	@echo
	ARCH="$(ARCH)" OPENNMS_VERSION="$(OPENNMS_VERSION)" PKG_RELEASE="$(PKG_RELEASE)" MAINTAINER_EMAIL="$(MAINTAINER_EMAIL)" \
		nfpm package --packager rpm --config nfpm/nfpm-minion.yaml --target "$(ARTIFACTS_DIR)/packages/minion/"

.PHONY: sentinel-pkg-buildroot
sentinel-pkg-buildroot:
	@build-tooling/build-package-root.sh --component sentinel \
		--version $(OPENNMS_VERSION) \
		--build-root $(BUILD_ROOT) \
		--artifacts-dir $(ARTIFACTS_DIR) \
		--home $(PKG_SENTINEL_HOME) \
		--logs $(PKG_SENTINEL_LOGS) \
		--deploy $(PKG_SENTINEL_DEPLOY)

.PHONY: sentinel-pkg-deb
sentinel-pkg-deb: deps-packages sentinel-pkg-buildroot ## Build Sentinel Debian packages
	@echo "==== Building Debian Sentinel Packages ===="
	@echo
	@echo "Version:     " $(OPENNMS_VERSION)
	@echo "Release:     " $(DEB_PKG_RELEASE)
	@echo
	ARCH="$(ARCH)" OPENNMS_VERSION="$(OPENNMS_VERSION)" PKG_RELEASE="$(PKG_RELEASE)" MAINTAINER_EMAIL="$(MAINTAINER_EMAIL)" \
		nfpm package --packager deb --config nfpm/nfpm-sentinel.yaml --target "$(ARTIFACTS_DIR)/packages/sentinel/"

.PHONY: sentinel-pkg-rpm
sentinel-pkg-rpm: deps-packages sentinel-pkg-buildroot ## Build Sentinel RPM packages
	@echo "==== Building RPM Sentinel Packages ===="
	@echo
	@echo "Version:     " $(OPENNMS_VERSION)
	@echo "Release:     " $(DEB_PKG_RELEASE)
	@echo
	ARCH="$(ARCH)" OPENNMS_VERSION="$(OPENNMS_VERSION)" PKG_RELEASE="$(PKG_RELEASE)" MAINTAINER_EMAIL="$(MAINTAINER_EMAIL)" \
		nfpm package --packager rpm --config nfpm/nfpm-sentinel.yaml --target "$(ARTIFACTS_DIR)/packages/sentinel/"

.PHON: all-pkgs
all-pkgs: core-pkg-deb core-pkg-rpm minion-pkg-deb minion-pkg-rpm sentinel-pkg-deb sentinel-pkg-rpm ## Build all packages

.PHONY: javadocs
javadocs: deps-build show-info ## Generate Java docs
	$(MAVEN_BIN) javadoc:aggregate --batch-mode -Prun-expensive-tasks

.PHONY: docs
docs: deps-docs ## Build Antora docs with a local install Antora, default target
	@echo "Build Antora docs..."
	antora --stacktrace $(SITE_FILE)

.PHONY: install-core
install-core: quick-compile quick-assemble unpack-core ## Install OpenNMS assembly to PKG_CORE_HOME to $(PKG_CORE_HOME)

# The extraction half of install-core, without the build prerequisites. Callers that
# have already built the tarball (CI test jobs run quick-compile + quick-assemble in a
# preceding step) use this to avoid rebuilding the whole reactor a second time.
.PHONY: unpack-core
unpack-core: ## Extract an already-built Core assembly to $(PKG_CORE_HOME) without rebuilding
ifeq (,$(wildcard ./target/opennms-$(OPENNMS_VERSION).tar.gz))
	@echo "Can't unpack the Core assembly, ./target/opennms-$(OPENNMS_VERSION).tar.gz is missing."
	@echo "Run 'make quick-build' or 'make install-core' first."
	@exit 1
else
	@echo "Install OpenNMS Horizon Core to $(PKG_CORE_HOME)"
	mkdir -p $(PKG_CORE_HOME)
	tar xzf ./target/opennms-$(OPENNMS_VERSION).tar.gz -C $(PKG_CORE_HOME)
endif

.PHONY: uninstall-core
uninstall-core: ## Remove the installed version in PKG_CORE_HOME from $(PKG_CORE_HOME)
	@echo "Uninstall OpenNMS Horizon Core from $(PKG_CORE_HOME)"
	rm -rf "$(PKG_CORE_HOME)/*"

.PHONY: clean-all
clean-all: clean-m2 clean-git ## Clean git repository with untracked files, docs, M2 opennms artifacts and build assemblies

.PHONY: clean-git
clean-git: ## DELETE *all* untracked files from local git repository
	git clean -fdx

.PHONY: clean-m2
clean-m2: ## Remove just OpenNMS build artifacts from Maven local repository
	rm -rf ~/.m2/repository/org/opennms

.PHONY: clean-assembly
clean-assembly: ## Run mvn clean on assemblies, equivalent to clean.pl
	$(MAVEN_BIN) -Passemblies clean

.PHONY: clean-docs
clean-docs: ## Clean all docs build artifacts
	@echo "Delete build and public artifacts ..."
	@rm -rf build public
	@echo "Clean Antora cache for git repositories and UI components ..."
	@rm -rf .cache

.PHONY: clean-buildroot
clean-buildroot: ## Clean all package build root directories for Core, Minion, Sentinel in $(BUILD_ROOT)
	@echo "Delete build root content for package builds ..."
	@rm -rf $(BUILD_ROOT)

.PHONY: clean-packages
clean-packages: ## Clean all Debian and RPM package artifacts in $(ARTIFACTS_DIR)/packages
	@echo "Delete RPM and Debian package artifacts ..."
	@rm -rf $(ARTIFACTS_DIR)/packages

.PHONY: clean
clean: clean-assembly clean-docs ## Clean assembly and docs and mostly used to recompile or rebuild from source

.PHONY: collect-artifacts
# We use find with a regex, which exits gracefully when targets don't exist in case steps failed.
collect-artifacts: ## Fetch and collect build artifacts in $(ARTIFACTS_DIR)
	mkdir -p $(ARTIFACTS_DIR)/{archives,config-schema,oci}
	find . -type f -regex "^\.\/target\/opennms-.*\.tar\.gz" -exec mv -v {} $(ARTIFACTS_DIR)/archives \; # Fetch -source and assembled archive
	find . -type f -regex "^\.\/opennms-assemblies\/minion\/target\/org.opennms.assemblies.minion-.*\.tar\.gz" -exec mv -v {} $(ARTIFACTS_DIR)/archives/minion-${OPENNMS_VERSION}.tar.gz \;
	find . -type f -regex "^\.\/opennms-assemblies\/sentinel\/target\/org.opennms.assemblies.sentinel-.*\.tar\.gz" -exec mv -v {} $(ARTIFACTS_DIR)/archives/sentinel-${OPENNMS_VERSION}.tar.gz \;
	find . -type f -regex "^\.\/opennms-assemblies\/xsds\/target\/.*-xsds\.tar\.gz" -exec mv -v {} $(ARTIFACTS_DIR)/archives/opennms-${OPENNMS_VERSION}-xsds.tar.gz \;
	find . -type f -regex "^\.\/opennms-full-assembly\/target\/opennms-full-assembly-.*-core\.tar\.gz" -exec mv -v {} $(ARTIFACTS_DIR)/archives/opennms-${OPENNMS_VERSION}-core.tar.gz \;
	find . -type f -regex "^\.\/opennms-full-assembly\/target\/opennms-full-assembly-.*-optional\.tar\.gz" -exec mv -v {} $(ARTIFACTS_DIR)/archives/opennms-${OPENNMS_VERSION}-optional.tar.gz \;
	find . -type f -regex "^\.\/opennms-full-assembly\/target\/THIRD-PARTY.txt" -exec mv -v {} $(ARTIFACTS_DIR) \;
	find . -type f -regex "^\.\/opennms-container\/.*\/images\/.*\.oci" -exec mv -v {} $(ARTIFACTS_DIR)/oci \;
	find . -type f -regex "^\.\/target\/bom.*" -exec mv -v {} $(ARTIFACTS_DIR) \;

.PHONY: collect-testresults
collect-testresults: ## Fetch test results from tests in $(ARTIFACTS_DIR)/tests
	mkdir -p $(ARTIFACTS_DIR)/{surefire-reports,failsafe-reports,recordings}
	find . -type f -regex ".*\/target\/.*\.mp4" -exec mv -v {} $(ARTIFACTS_DIR)/recordings \;
	find . -type f -regex ".*\/target\/surefire-reports\/.*\.xml" -exec mv -v {} $(ARTIFACTS_DIR)/surefire-reports/ \;
	find . -type f -regex ".*\/target\/failsafe-reports\/.*\.xml" -exec mv -v {} $(ARTIFACTS_DIR)/failsafe-reports/ \;
	find . -type d -regex "^\.\/target\/logs" -exec tar czf $(ARTIFACTS_DIR)/logs.tar.gz {} \;
	find . -type d -regex "^\./e2e-tests\/target\/logs" -exec tar czf $(ARTIFACTS_DIR)/e2e-tests-logs.tar.gz {} \;
	find . -type d -regex "^\./e2e-tests\/target\/screenshots" -exec tar czf $(ARTIFACTS_DIR)/e2e-tests-screenshots.tar.gz {} \;
	find . -type f -regex "^\.\/target\/structure-graph\.json" -exec mv -v {} $(ARTIFACTS_DIR) \;

.PHONY: spinup-postgres
spinup-postgres: deps-oci ## Spinup a PostgreSQL container to run integration tests used by integration tests
	@echo "Spin-up PostgreSQL database for tests using Docker Compose on port 5432/tcp"
	docker compose -f .cicd-assets/postgres/compose.yaml up -d

.PHONY: destroy-postgres
destroy-postgres: deps-oci ## Shutdown and destroy the PostgreSQL container
	@echo "Shutdown and remove PostgreSQL database using Docker Compose"
	docker compose -f .cicd-assets/postgres/compose.yaml down -v

.PHONY: registry-login
registry-login: deps-oci
	@echo ${OCI_REGISTRY_PASSWORD} | docker login --username ${OCI_REGISTRY_USER} --password-stdin ${OCI_REGISTRY}

.PHONY: version
version: deps-build
	$(call setversion,$(RELEASE_VERSION))

.PHONY: release
release: deps-build ## Cut a release, set RELEASE_VERSION and PUSH_RELEASE=true to publish
	@mkdir -p target
	@echo ""
	@echo "Release version:                $(RELEASE_VERSION)"
	@echo "New snapshot version:           $(SNAPSHOT_VERSION)"
	@echo "Git version tag:                v$(RELEASE_VERSION)"
	@echo "Release log:                    $(RELEASE_LOG)"
	@echo "Current branch:                 $(GIT_BRANCH)"
	@echo "Release branch:                 $(RELEASE_BRANCH)"
	@echo ""
	@echo -n "👮‍♀️ Check release branch:        "
	@if [ "$(GIT_BRANCH)" != "$(RELEASE_BRANCH)" ]; then echo "Releases are made from the $(RELEASE_BRANCH) branch, your branch is $(GIT_BRANCH)."; exit 1; fi
	@echo "$(OK)"
	@echo -n "👮‍♀️ Check branch in sync         "
	@if [ "$$(git rev-parse HEAD)" != "$$(git rev-parse '@{u}')" ]; then echo "$(RELEASE_BRANCH) branch not in sync with remote origin."; exit 1; fi
	@echo "$(OK)"
	@echo -n "👮‍♀️ Check uncommited changes     "
	@if git status --porcelain | grep -q .; then echo "There are uncommited changes in your repository."; exit 1; fi
	@echo "$(OK)"
	@echo -n "👮‍♀️ Check release version:       "
	@if [ "$(RELEASE_VERSION)" = "UNSET.0.0" ]; then echo "Set a release version, e.g. make release RELEASE_VERSION=1.0.0"; exit 1; fi
	@echo "$(OK)"
	@echo -n "👮‍♀️ Check version tag available: "
	@if git rev-parse v$(RELEASE_VERSION) >$(RELEASE_LOG) 2>&1; then echo "Tag v$(RELEASE_VERSION) already exists"; exit 1; fi
	@echo "$(OK)"
	@$(call setversion,$(RELEASE_VERSION))
	@$(MAVEN_BIN) validate >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@echo -n "🎁 Git commit new release       "
	@git commit --signoff -am "release: BluebirdOps $(RELEASE_VERSION)" >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@echo -n "🦄 Set Git version tag:         "
	@git tag -a "v$(RELEASE_VERSION)" -m "Release BluebirdOps version $(RELEASE_VERSION)" >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@$(call setversion,$(SNAPSHOT_VERSION))
	@echo -n "🎁 Git commit snapshot release: "
	@git commit --signoff -am "release: BluebirdOps $(SNAPSHOT_VERSION)" >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@if [ "$(PUSH_RELEASE)" = "true" ]; then \
	    echo -n "🦄 Push commits                  "; \
  		git push >>$(RELEASE_LOG) 2>&1; \
		echo "$(OK)"; \
		echo -n "🚀 Push tag                      "; \
  		git push origin v$(RELEASE_VERSION) >>$(RELEASE_LOG) 2>&1; \
  		echo "$(OK)"; \
  	else \
  		echo "Push commits and tag:           $(SKIP)"; \
  	fi;

