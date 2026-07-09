/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.netmgt.flows.clickhouse;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import org.opennms.netmgt.flows.clickhouse.ClickhouseSchema.Migration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;

/**
 * Creates the flow schema on startup and applies any not-yet-applied migrations. Applied versions
 * are recorded in a {@code flow_schema_migrations} ledger table, so an upgrade that ships a new DDL
 * resource runs only the new migration rather than being silently skipped (design D-BOOTSTRAP). The
 * initial migrations use {@code CREATE … IF NOT EXISTS}, so a partial/repeated run is safe.
 */
public class ClickhouseSchemaBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(ClickhouseSchemaBootstrap.class);

    private static final String LEDGER_DDL =
            "CREATE TABLE IF NOT EXISTS flow_schema_migrations ("
            + "version UInt32, name String, applied_at DateTime DEFAULT now()) "
            + "ENGINE = MergeTree ORDER BY version";

    private final Client client;
    private final int ttlDays;

    public ClickhouseSchemaBootstrap(final Client client, final int ttlDays) {
        this.client = Objects.requireNonNull(client);
        this.ttlDays = ttlDays;
    }

    public void initialize() {
        LOG.info("Initializing ClickHouse flow schema (ttlDays={}).", ttlDays);
        execute(LEDGER_DDL);
        final long applied = maxAppliedVersion();

        for (final Migration migration : ClickhouseSchema.migrations(ttlDays)) {
            if (migration.version() <= applied) {
                continue;
            }
            for (final String statement : migration.statements()) {
                execute(statement);
            }
            execute("INSERT INTO flow_schema_migrations (version, name) VALUES ("
                    + migration.version() + ", '" + migration.name().replace("'", "''") + "')");
            LOG.info("Applied ClickHouse flow schema migration v{} ({}).", migration.version(), migration.name());
        }
        LOG.info("ClickHouse flow schema is ready.");
    }

    private long maxAppliedVersion() {
        // max() over an empty table returns a single row with 0, so this is safe on first run.
        final List<GenericRecord> records = client.queryAll("SELECT max(version) AS v FROM flow_schema_migrations");
        return records.isEmpty() ? 0L : records.get(0).getLong("v");
    }

    private void execute(final String statement) {
        try {
            client.query(statement).get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while applying ClickHouse schema DDL.", e);
        } catch (final ExecutionException e) {
            throw new IllegalStateException("Failed to apply ClickHouse schema DDL: " + statement, e);
        }
    }
}
