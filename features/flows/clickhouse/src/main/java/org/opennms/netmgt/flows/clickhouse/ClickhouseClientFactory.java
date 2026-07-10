/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.netmgt.flows.clickhouse;

import java.time.temporal.ChronoUnit;
import java.util.Objects;

import com.clickhouse.client.api.Client;

/**
 * Builds a configured ClickHouse {@link Client} from the blueprint config PID. Kept as a factory
 * so the fluent builder can be driven from blueprint via {@code factory-method}.
 */
public class ClickhouseClientFactory {

    /** Fail fast when the server is unreachable rather than blocking on the OS-default connect timeout. */
    private static final long CONNECT_TIMEOUT_MS = 5_000L;

    private final String endpoint;
    private final String username;
    private final String password;
    private final String database;

    public ClickhouseClientFactory(final String endpoint,
                                   final String username,
                                   final String password,
                                   final String database) {
        this.endpoint = Objects.requireNonNull(endpoint);
        this.username = username != null ? username : "default";
        this.password = password != null ? password : "";
        this.database = database != null ? database : "default";
    }

    public Client createClient() {
        return new Client.Builder()
                .addEndpoint(endpoint)
                .setUsername(username)
                .setPassword(password)
                .setDefaultDatabase(database)
                .setClientName("opennms-flows-clickhouse")
                // Fail fast on an unreachable/firewalled server instead of blocking a query (or the
                // startup schema bootstrap) on the OS-default connect timeout. Deliberately NO socket
                // (read) timeout: ClickHouse streams no bytes until an aggregation completes, so a read
                // timeout would abort large-but-valid flow queries (the documented socket_timeout gotcha).
                .setConnectTimeout(CONNECT_TIMEOUT_MS, ChronoUnit.MILLIS)
                // client-v2 0.8.6's pooled connection manager times out leasing a connection even to
                // a reachable server (ConnectionRequestTimeout on the first request); disable pooling
                // so each request opens a fresh (working) connection.
                .enableConnectionPool(false)
                .build();
    }
}
