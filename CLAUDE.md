# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## CRITICAL: Git Remote Rules

**NEVER create pull requests against any `OpenNMS/*` repository.** This is a fork (`bluebird-community/oopennms`). Always use `--repo bluebird-community/opennms` with `gh pr create`. The `gh` CLI defaults to the fork parent (`OpenNMS/opennms`) which is wrong.

```bash
# CORRECT
gh pr create --repo bluebird-community/opennms --base main ...

# WRONG — DO NOT DO THIS
gh pr create ...  # defaults to OpenNMS/opennms
```

## Project Overview

BluebirdOps is an enterprise-grade open-source network monitoring platform. Version 36.0.2-SNAPSHOT, licensed under AGPL v3. Java 17 required (enforced range `[17,18)`).

## Build Commands

The project ships its own Maven in `maven/bin/mvn`. The `Makefile` wraps Maven with sensible defaults.
Integration tests have a suffix IT and unit tests have a suffix Test.
The end to end tests (e2e) are in the "e2e-tests" directory.

```bash
# Full compile (skip tests for speed)
make quick-compile

# Build the Vue UI
make compile-ui

# Assemble for local running (dir profile = run from target/)
make quick-assemble

# Run smoke tests
make smoke

# Run unit tests
make unit-tests

# Run integration tests
make integration-tests

# Run end to end tests for Core
make core-e2e

# Run end to end tests for Minion
make minion-e2e

# Run end to end tests for Sentinel
make sentinel-e2e

# Run a single unit test class
make unit-tests U_TESTS="org.opennms.netmgt.vmmgr.ControllerTest"

# Run a single integration test class
make integration-tests I_TESTS="org.opennms.core.snmp.profile.mapper.SnmpProfileMapperIT"
```

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
| Language | Java 17 |
| Build | Maven (bundled), Perl wrapper scripts |
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
- **Build tool:** Vite
- **Component library:** Feather Design System
- **State:** Pinia
- **Visualization:** D3, Chart.js, Leaflet
- **Tests:** Vitest + Vue Test Utils + Happy-DOM

The UI also has a `menu/` sub-build that provides embeddable Vue components for legacy JSP pages. Build output goes to `src/main/dist/` and `src/menu/dist-menu/`.

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

## CI/CD

GitHub Actions. Path-based filtering determines which jobs run:
- Source changes trigger full build
