# Removing Newts and Cassandra from BluebirdOps

This document maps every place Newts (time-series storage) and Cassandra (its backing database) appear in the codebase and describes what must change to remove them completely.

---

## Scope

**Newts** is one of three pluggable time-series back-ends. It stores collected metrics in Cassandra via the `newts-cassandra` library (version 3.0.0). Removing it does not affect RRDtool/JRobin or the Time Series Integration Layer (TSIL), which remain.

**Cassandra** appears in three independent roles:
1. Newts storage back-end *(primary use — removed with Newts)*
2. KV blob store back-end *(Postgres and in-memory alternatives already exist)*
3. `executor-factory/cassandra` — borrows Cassandra's `SharedExecutorPool` thread-pool implementation; this has **no Cassandra database connection** and is a separate consideration (see section 7).

---

## Modules to delete entirely

These directories are self-contained and have no callers once Newts is gone. Delete the full directory tree.

| Directory | Purpose |
|---|---|
| `features/newts/` | Core Newts persistence, indexing, DAO, measurements strategy |
| `features/newts-repository-converter/` | One-time RRD→Newts migration tool |
| `features/distributed/distributed-cassandra/` | `CassandraSession` / `CassandraSessionFactory` API + Newts impl (only consumers are Newts and KV blob store Cassandra impl) |
| `features/distributed/kv-store/blob/cassandra/` | Cassandra-backed blob store (Postgres and in-memory implementations remain) |
| `dependencies/newts/` | Centralized Newts dependency management pom |
| `dependencies/cassandra-test/` | Test dependency pom for Newts/Cassandra |

After deletion, remove the corresponding `<module>` entries from any parent `pom.xml` that lists them.

---

## Root pom.xml

**File:** `pom.xml`

1. Remove properties (search for these lines):
   ```xml
   <cassandraVersion>4.17.0</cassandraVersion>
   <newtsVersion>3.0.0</newtsVersion>
   <newtsOsgiVersion>3.0.0</newtsOsgiVersion>
   ```

2. Remove module declarations in the `features/` section:
   ```xml
   <module>newts</module>
   <module>newts-repository-converter</module>
   ```

---

## Karaf features (container/)

### `container/features/src/main/resources/features.xml`
- Remove the Newts Karaf repository declaration:
  ```xml
  <repository>mvn:org.opennms.newts/newts-karaf/${newtsVersion}/xml/features</repository>
  ```
- Remove the `opennms-newts` feature definition and any feature that depends on it.
- Remove the `cassandra-driver` feature reference.

### `container/features/src/main/resources/features-core.xml`
- Remove the `cassandra-driver` feature definition (lines 35–44), which declares the DataStax Java driver OSGi bundles.

### `container/features/src/main/resources/features-sentinel.xml`
- Remove the Newts Karaf repository declaration (line 13).
- Remove the following feature definitions:
  - `sentinel-newts`
  - `sentinel-newts-cassandra`
  - `sentinel-distributed-cassandra-api`
  - `sentinel-blobstore-cassandra`

---

## features/timeseries — partial change required

**This module cannot simply be deleted.** It is the active Time Series Integration Layer used by all non-Newts back-ends. However, two files inside it directly import `org.opennms.newts.api.*` and `org.opennms.newts.aggregate.*` classes:

| File | Newts dependency |
|---|---|
| `src/main/java/…/sampleread/aggregation/NewtsConverterUtils.java` | Imports 9 Newts API types (`Context`, `Counter`, `Gauge`, `Measurement`, `MetricType`, `Resource`, `Results`, `Timestamp`, `ValueType`) |
| `src/main/java/…/sampleread/aggregation/NewtsLikeSampleAggregator.java` | Imports `ResultProcessor`, `Duration`, and 6 other Newts types; wraps `ResultDescriptor` / `StandardAggregationFunctions` |

`TimeseriesFetchStrategy.java` calls both of these and is otherwise Newts-free.

**Required work:** Rewrite the aggregation logic in `NewtsConverterUtils` and `NewtsLikeSampleAggregator` using only standard Java/OpenNMS types, then remove the `newts-dependencies` pom import from `features/timeseries/pom.xml`. The classes can keep their current names or be renamed (e.g. `SampleAggregator`) — the logic itself does not require Newts; the Newts types were used as intermediate representations.

---

## features/measurements/shell — one file to delete

**File:** `features/measurements/shell/src/main/java/…/shell/ShowNewtsSamples.java`

This Karaf shell command queries Newts directly. Delete the file and remove the `newts-api` dependency from `features/measurements/shell/pom.xml`.

---

## opennms-base-assembly — configuration files

### Scripts (delete both):
- `src/main/filtered/bin/newts`
- `src/main/filtered/bin/newts-repository-converter`

### JMX datacollection configs (delete all four — these monitor a Cassandra node):
- `src/main/filtered/etc/jmx-datacollection-config.d/cassandra30x.xml`
- `src/main/filtered/etc/jmx-datacollection-config.d/cassandra30x-newts.xml`
- `src/main/filtered/etc/examples/jvm-datacollection/jmx-datacollection-config.d/cassandra.xml`
- `src/main/filtered/etc/examples/jvm-datacollection/jmx-datacollection-mbeans/Cassandra/`

### Graph properties (delete both):
- `src/main/filtered/etc/snmp-graph.properties.d/cassandra-graph.properties`
- `src/main/filtered/etc/snmp-graph.properties.d/cassandra-newts-graph.properties`

### Properties/config referencing Newts (edit, remove Newts-specific blocks):
- `src/main/filtered/etc/opennms.properties` — remove `org.opennms.timeseries.strategy=newts` option and related comments
- `src/main/filtered/etc/collectd-configuration.xml` — remove Cassandra collector examples
- `src/main/filtered/etc/poller-configuration.xml` — remove Cassandra service monitors

---

## opennms-container — Docker / Confd

### Delete:
- `opennms-container/core/container-fs/confd/conf.d/newts.properties.toml`
- `opennms-container/core/container-fs/confd/templates/newts.properties.tmpl`

These templates generate `org.opennms.newts.config.*` properties from `/opennms/cassandra/` environment keys at container startup.

---

## e2e-tests — container infrastructure

### Delete entirely:
- `src/main/java/…/containers/OpenNMSCassandraContainer.java` — Testcontainers wrapper for Cassandra 3

### Edit — remove Cassandra from stack orchestration:

**`src/main/java/…/stacks/OpenNMSStack.java`**
- Remove `OpenNMSCassandraContainer` field and its `RuleChain` entry in the `ALEC` stack builder.

**`src/main/java/…/containers/OpenNMSContainer.java`**
- Remove `CassandraContainer` constructor parameter, the `OPENNMS_TIMESERIES_STRATEGY=newts` env var injection, and the Cassandra-specific wait-strategy logic.

**`src/main/java/…/containers/SentinelContainer.java`**
- Remove `CassandraContainer` constructor parameter and related env var injection.

### Edit — pom.xml:
- Remove the `org.testcontainers:cassandra` dependency (or `testcontainers-cassandra` after the TC 2.0 upgrade).

### Delete — test files that require a live Cassandra:
- `src/test/java/…/newts/` (entire directory, if present)

---

## Documentation

Delete the entire Newts deployment guide directory:

```
docs/modules/deployment/pages/time-series-storage/newts/
```

Files inside:
- `newts.adoc`
- `cassandra-jmx.adoc`
- `cassandra-newts-jmx.adoc`
- `newts-repository-converter.adoc`
- `configuration.adoc`
- `newts-repository-converter.adoc`
- `resourcecli.adoc`

Also remove any navigation entries pointing to these pages in the Antora `nav.adoc` files.

---

## `features/executor-factory/cassandra/` — done

This module previously used `org.apache.cassandra:cassandra-all:2.1.6` only for its `SharedExecutorPool` thread pool, with no database connection. The implementation has been replaced with a standard `ThreadPoolExecutor` + `LogPreservingThreadFactory` (matching `ExecutorFactoryJavaImpl` in `opennms-util`), and the `cassandra-all` and `high-scale-lib` Maven dependencies have been removed. No callers needed updating — the only reference was a commented-out line in `RadixTreeParser.java`.

---

## Summary checklist

| # | Action | Scope | Status |
|---|---|---|---|
| 1 | Delete 6 modules entirely | `features/newts`, `features/newts-repository-converter`, `features/distributed/distributed-cassandra`, `features/distributed/kv-store/blob/cassandra`, `dependencies/newts`, `dependencies/cassandra-test` | ✅ Done |
| 2 | Remove root `pom.xml` properties and `<module>` entries | 1 file | ✅ Done |
| 3 | Rewrite Newts aggregation logic in `features/timeseries` | 2 files | ✅ Done |
| 4 | Delete `ShowNewtsSamples.java` + pom dep | 2 files | ✅ Done |
| 5 | Strip Newts/Cassandra features from 3 Karaf feature XMLs | 3 files | ✅ Done |
| 6 | Delete 4 JMX datacollection configs, 2 graph configs, 2 scripts | 8 files | ✅ Done |
| 7 | Edit `opennms.properties`, `collectd-configuration.xml`, `poller-configuration.xml` | 3 files | ✅ Done |
| 8 | Delete 2 Confd templates | 2 files | ✅ Done |
| 9 | Delete `OpenNMSCassandraContainer.java`; edit `OpenNMSStack`, `OpenNMSContainer`, `SentinelContainer`, `e2e-tests/pom.xml` | 5 files | ✅ Done |
| 10 | Delete Newts documentation directory | ~7 files | ✅ Done |
| 11 | ~~`features/executor-factory/cassandra/`~~ replaced with `ThreadPoolExecutor`; `cassandra-all` dep removed | Done | ✅ Done |
