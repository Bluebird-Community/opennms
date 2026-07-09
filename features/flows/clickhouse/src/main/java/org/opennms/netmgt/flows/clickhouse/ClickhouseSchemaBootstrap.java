/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.netmgt.flows.clickhouse;

import java.util.Objects;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.clickhouse.client.api.Client;

/**
 * Creates the flow schema (raw {@code flows} table, rollup tables and their materialized views) on
 * startup if it does not already exist. All statements are {@code IF NOT EXISTS} so this is
 * idempotent and safe to re-run (D-BOOTSTRAP). The DDL is loaded from {@link ClickhouseSchema};
 * this class only executes it.
 */
public class ClickhouseSchemaBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(ClickhouseSchemaBootstrap.class);

    private final Client client;
    private final int ttlDays;

    public ClickhouseSchemaBootstrap(final Client client, final int ttlDays) {
        this.client = Objects.requireNonNull(client);
        this.ttlDays = ttlDays;
    }

    public void initialize() {
        LOG.info("Initializing ClickHouse flow schema (ttlDays={}).", ttlDays);
        for (final String statement : ClickhouseSchema.statements(ttlDays)) {
            try {
                client.query(statement).get();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while applying ClickHouse schema DDL.", e);
            } catch (final ExecutionException e) {
                throw new IllegalStateException("Failed to apply ClickHouse schema DDL: " + statement, e);
            }
        }
        LOG.info("ClickHouse flow schema is ready.");
    }
}
