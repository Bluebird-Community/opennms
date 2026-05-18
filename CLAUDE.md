# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## CRITICAL: Git Remote Rules

**NEVER create pull requests against any `OpenNMS/*` repository.** This is a fork (`bluebird-community/opennms`). Always use `--repo bluebird-community/opennms` with `gh pr create`. The `gh` CLI defaults to the fork parent (`OpenNMS/opennms`) which is wrong.

Remotes in this clone: `origin` → `bluebird-community/opennms` (our fork), `upstream` → `OpenNMS/opennms` (read-only; merged in via `merge-foundation/*` branches).

```bash
# CORRECT
gh pr create --repo bluebird-community/opennms --base main ...

# WRONG — DO NOT DO THIS
gh pr create ...  # defaults to OpenNMS/opennms
```

## Project Overview

BluebirdOps is an enterprise-grade open-source network monitoring platform. Version 36.0.2-SNAPSHOT, licensed under AGPL v3. Java 21 required (enforced range `[21,22)` — bumped from 17 via NMS-19396).

## Build Commands

The project uses the Apache Maven Wrapper (`./mvnw`, `.mvn/wrapper/maven-wrapper.properties`) — the first invocation downloads the pinned Maven version (3.9.14 at the time of writing) into `~/.m2/wrapper/dists/` and caches it there. No system Maven install needed. The Perl wrappers `compile.pl` / `assemble.pl` / `clean.pl` also resolve to `./mvnw` via `bin/functions.pl`. The `Makefile` wraps all of this with sensible defaults — prefer make targets for whole-tree work, use `./compile.pl` directly for partial builds on a single module.

Prerequisites: Java 21, Docker (+ Compose plugin) for tests, Node 24 + pnpm 10.x for the UI.

Naming: unit tests end in `*Test`, integration tests end in `*IT`, end-to-end tests live in `e2e-tests/`.

```bash
make help                     # list every target the Makefile exposes

# Fast inner loop — "quick-*" means "skip tests"
make quick-compile            # compile everything, no tests
make quick-assemble           # assemble tarball into target/ (requires quick-compile)
make quick-build              # quick-compile + quick-assemble
make install-core             # shortcut: quick-compile + quick-assemble
make compile-ui               # build the Vue UI (cd ui && pnpm install && pnpm build)

# Full, tested build
make compile                  # full compile with tests
make assemble                 # full assembly

# Tests
make unit-tests                                           # all unit tests
make unit-tests U_TESTS="org.opennms.netmgt.vmmgr.ControllerTest"
make unit-tests TEST_PROJECTS=":opennms-dao"              # scoped to one module
make integration-tests                                    # all IT tests (spins up postgres)
make integration-tests I_TESTS="org.opennms.core.snmp.profile.mapper.SnmpProfileMapperIT"
make integration-tests TEST_PROJECTS=":opennms-dao"
make smoke                                                # smoke tests (builds core OCI first)
make core-e2e / minion-e2e / sentinel-e2e                 # end-to-end per artifact

# Docs (Antora/AsciiDoc — see antora-playbook-local.yml)
make docs
```

### Partial builds with compile.pl

For anything smaller than a whole tree, drive Maven directly. The pattern is `--projects :<artifactId>` with `-am` (build its deps) or `-amd` (build its dependents):

```bash
# Build opennms-dao and everything it needs
./compile.pl -DskipTests=true --projects :opennms-dao -am install

# Rebuild everything that depends on opennms-dao (after changing it)
./compile.pl -t --projects :opennms-dao -amd install

# Find all artifacts whose code matches a grep and build them
./compile.pl -DskipTests=true --projects `tools/development/grep-pom-artifact.sh -i jdom` install
```

`tools/development/pom-artifact.sh` and `grep-pom-artifact.sh` are the helpers that turn a `pom.xml` or grep match into `groupId:artifactId` tuples.

### Local development quick start
```bash
./tools/local_development/dependencies.sh --check-dependencies
./tools/local_development/opennms.sh
```

## Running Locally After Build

```bash
export ONMS_RELEASE=$(grep '<version>' pom.xml | head -1 | sed -e 's/.*<version>\(.*\)<\/version>.*/\1/')
echo "RUNAS=$(id -u -n)" > "target/opennms-${ONMS_RELEASE}/etc/opennms.conf"
# Configure PostgreSQL in target/opennms-${ONMS_RELEASE}/etc/opennms-datasources.xml
./target/opennms-"${ONMS_RELEASE}"/bin/runjava -s
./target/opennms-"${ONMS_RELEASE}"/bin/install -dis
./target/opennms-"${ONMS_RELEASE}"/bin/opennms -vt start
```

A quick PostgreSQL for dev: `docker run -d -e POSTGRES_HOST_AUTH_METHOD=trust -p 5432:5432 postgres:18`

## Architecture

### Module Organization

The codebase has two structural patterns:

**Modern structure:**
- `core/` — Core platform (38 modules: api, cache, config, daemon, db, grpc, ipc, jmx, snmp, web, etc.)
- `features/` — 87+ feature modules (alarms, collection, discovery, events, flows, kafka, poller, provisioning, rest, telemetry, topology-map, vaadin UI components, etc.)
- `dependencies/` — Centralized dependency management (66 sub-modules)
- `container/` — Karaf OSGi container assembly and features
- `protocols/` — Protocol implementations (CIFS, NSClient, RADIUS, Selenium, XML)
- `integrations/` — External system integrations
- `tests/` — Shared test infrastructure (DAO tests, mock elements, mock SNMP agent)
- `ui/` — Modern Vue 3 SPA frontend
- `e2e-tests/` — End-to-end tests

**Legacy structure (top-level `opennms-*` directories):**
- `opennms-model/` — Domain model
- `opennms-dao/`, `opennms-dao-api/` — Data access
- `opennms-config/`, `opennms-config-api/`, `opennms-config-model/`, `opennms-config-jaxb/` — Configuration
- `opennms-services/` — Core services
- `opennms-provision/` — Provisioning
- `opennms-webapp-rest/` — REST API
- `opennms-web-api/` — Web API layer
- `opennms-webapp/` — Legacy JSP webapp
- `opennms-full-assembly/` — Final Horizon assembly

### Runtime Architecture

OpenNMS embeds Apache Karaf (4.4.9) as an OSGi container. Karaf is embedded *above* the legacy webapp in the Spring context hierarchy, so the core is pre-initialized before Karaf extends it. The goal is to remove Karaf OSGi in the future.

**Karaf feature files** are in `container/features/src/main/resources/`:
- `features.xml` — Main features
- `features-core.xml` — Core/third-party base features
- `features-minion.xml` — Minion features
- `features-sentinel.xml` — Sentinel features

**Three deployable artifacts:** Horizon (core), Minion (distributed data collection), Sentinel (high-availability event processing).

### Key Technology Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 21 |
| Build | Apache Maven Wrapper (`./mvnw`, downloads Maven 3.9.14 on demand), Perl wrapper scripts |
| OSGi Container | Apache Karaf 4.4.9 |
| Web Framework | Spring 4.2.x (OpenNMS-patched fork), Spring Security 4.2.x (patched) |
| ORM | Hibernate 3.6.11 (OpenNMS build) |
| REST | Apache CXF 3.6.8 |
| Messaging | Apache ActiveMQ 5.16.8, Apache Kafka 3.6.2 |
| Integration | Apache Camel 2.21.5 |
| Time-Series | Newts 3.0.0 (Cassandra-backed), RRDtool via JRRD2 |
| Servlet Container | Jetty 9.4.x (embedded) |
| Database | PostgreSQL (Liquibase 3.6.3 for schema) |
| Frontend | Vue 3 + TypeScript + Vite + Pinia, Feather Design System |
| Serialization | Jackson 2.16.2, Protobuf 3.25.5, JAXB 2.3.3, gRPC 1.75.0 |

### Frontend (ui/)

The modern UI is a Vue 3 SPA in `ui/` built with:
- **Package manager:** pnpm (enforced, version 10.24.0)
- **Build tool:** Vite — **two separate Vite apps** share `src/` but build independently: `src/main/` (full SPA at `/opennms/ui`) and `src/menu/` (embeds in legacy JSP at `/opennms-menu`)
- **Component library:** Feather Design System (`@featherds/*`)
- **State:** Pinia (**setup store pattern**, not Options API)
- **Components:** `<script setup lang="ts">` Composition API only
- **Auto-imports:** `ref`/`computed`/`watch`/`useRouter`/VueUse via `unplugin-auto-import` — don't import these manually; custom composables (`useSnackbar`, `useSpinner`, `useRole`) must still be imported
- **Services:** axios instances from `services/axiosInstances.ts` (`v2`, `rest`, `restFile`), aggregated in `services/index.ts`
- **Visualization:** D3, Chart.js, Leaflet
- **Tests:** Vitest + Vue Test Utils + Happy-DOM

Build output goes to `src/main/dist/` and `src/menu/dist-menu/`. See `ui/copilot-instructions.md` for the full UI playbook (deploy to running instance, routing/SpaRoutingFilter behavior, role-gated routes, test harness patterns).

## Testing

- **Unit tests:** JUnit 4 (primary) + JUnit 5 (with Vintage engine for compatibility)
- **Mocking:** Mockito 3.12.4, PowerMock 2.0.9
- **BDD:** Spock 2.3 (Groovy)
- **Integration tests:** Testcontainers 1.19.7, Maven Failsafe plugin
- **Coverage:** JaCoCo 0.8.9
- **UI tests:** Vitest

Run all unit tests for a module:
```bash
make unit-tests TEST_PROJECTS=":opennms-dao"
```

Run all integration tests for a module:
```bash
make integration-tests TEST_PROJECTS=":opennms-dao"
```

## Branching Model

- `main` — next major release (default branch)
- Tags: `vXX.X.X` for Bluebird releases

## Key Conventions

- Spring beans use **constructor injection** (not `@Autowired` field injection)
- Configuration uses **JAXB** for XML serialization of config model objects
- REST endpoints use **CXF/JAX-RS** annotations
- OSGi services registered via **Karaf blueprint** or **SCR annotations**
- The Maven Enforcer Plugin bans certain dependencies (e.g., `commons-logging` — use `slf4j-api` instead). Fix violations by adding `<exclusions>` and using the approved alternative
- License validation: `./compile.pl -DskipTests -Denable.license=true -Passemblies -Psmoke install`
- Commit messages should follow the Conventional Commits specification

## Debugging Karaf / smoke-test failures

Karaf failures almost always surface as a smoke test timing out. The top-level test output is usually unhelpful — pull the `karaf.log` artifact from the failing job and search it for `exception`. OSGi resolution errors ("Unable to resolve root: missing requirement …") read **backwards**: the innermost `[caused by: …]` is the package or bundle that's actually missing, earlier frames tell you which feature pulled it in. Fix by adjusting the offending feature in `container/features/src/main/resources/features*.xml` (or the dependency that brings in the wrong bundle version).

## Further reading

- `DEVELOPMENT-TIPS.md` — deep-dives on Maven partial builds, the Maven Enforcer Plugin, the license plugin, the OpenNMS Spring/Spring Security forks, Karaf design history, and container image rebuild steps.
- `ui/copilot-instructions.md` — Vue 3 SPA architecture, patterns, test harness, and deploy-to-local-instance workflow.
- `ui/DEVELOPMENT.md`, `ui/MENU_TEMPLATES.md` — additional frontend notes.
- `tools/local_development/README.md` — the helper scripts used by the local-dev quick start.

## CI/CD

GitHub Actions (`.github/workflows/main.yml`). A `decide-scope` job runs first on every event and emits `full_build`, `baseline_ref`, `trigger_reason` outputs. Downstream behaviour:

- `unit-tests` / `integration-tests` always run; they receive `BASELINE_REF` and `FULL_BUILD` env vars and the Maven reactor is scoped to the modules transitively affected by the diff via `.cicd-assets/find-tests/`.
- `e2e-tests-core` / `e2e-tests-minion` / `e2e-tests-sentinel` are `if:`-gated on `full_build == 'true'`. They run on `v*` tag pushes and on `[full-ci]`/trip-wire-triggered runs only; on day-to-day PRs and `main` pushes they show as skipped.
- `make smoke` continues to run on every commit inside `build-with-smoke-test`.

### Forcing a full build

| Mechanism | When to use |
|---|---|
| Tag push `v*` | Release builds — unconditional full suite. |
| `[full-ci]` token in a commit message | One-off PRs that need full coverage. For PR events the token is scanned on the PR HEAD commit; for `main` pushes it's scanned in `${before}..${after}`. Include it in the PR title/description if you also want the post-merge push to be full. |
| Trip-wire path change | Edits to any of: root `pom.xml`, `dependencies/**`, `Makefile`, `compile.pl`, `assemble.pl`, `tools/development/**`, `.github/workflows/**`, `.cicd-assets/**`, `.mvn/**`, `container/features/**`, `pnpm-lock.yaml`, `ui/pnpm-lock.yaml` force a full build automatically. When you add a new cross-cutting path (a new lockfile, a new top-level build script), extend the list in `decide-scope`. |

### Local `find-tests.py`

`python3 .cicd-assets/find-tests/find-tests.py generate-test-lists .` still works with no flags — it falls back to reading `parent_branch:` from `.nightly`. Pass `--baseline-ref=<ref>` (or set `BASELINE_REF=<ref>`) to override.
