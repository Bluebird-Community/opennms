/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.netmgt.flows.clickhouse;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import org.opennms.core.health.api.Context;
import org.opennms.core.health.api.HealthCheck;
import org.opennms.core.health.api.Response;
import org.opennms.core.health.api.Status;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.clickhouse.client.api.Client;

/**
 * Health check for ClickHouse flow persistence connectivity. Runs a trivial {@code SELECT 1} against
 * the configured server. Mirrors the Elasticsearch {@code RequireConfigurationElasticHealthCheck}:
 * if the feature is installed but not configured (no ConfigAdmin properties for the flow-persistence
 * PID) the check reports success with "Not configured" rather than a spurious failure.
 */
public class ClickhouseHealthCheck implements HealthCheck {

    private static final Logger LOG = LoggerFactory.getLogger(ClickhouseHealthCheck.class);
    private static final String CLICKHOUSE = "clickhouse";

    private final Client client;
    private final String featureName;
    private final ConfigurationAdmin configAdmin;
    private final String pid;

    public ClickhouseHealthCheck(final Client client, final String featureName,
                                 final ConfigurationAdmin configAdmin, final String pid) {
        this.client = Objects.requireNonNull(client);
        this.featureName = Objects.requireNonNull(featureName);
        this.configAdmin = Objects.requireNonNull(configAdmin);
        this.pid = Objects.requireNonNull(pid);
    }

    @Override
    public String getDescription() {
        return "ClickHouse connectivity health check for " + featureName;
    }

    @Override
    public List<String> getTags() {
        return List.of(CLICKHOUSE);
    }

    @Override
    public Response perform(final Context context) {
        // If the feature is installed but not configured, don't report a failure.
        try {
            final Configuration configuration = configAdmin.getConfiguration(pid);
            if (configuration.getProperties() == null) {
                return new Response(Status.Success, "Not configured");
            }
        } catch (final IOException e) {
            return new Response(e);
        }

        try {
            client.queryAll("SELECT 1");
            return new Response(Status.Success, "ClickHouse is reachable for " + featureName);
        } catch (final Exception e) {
            LOG.error("Failed to check ClickHouse health", e);
            return new Response(Status.Failure,
                    "Failed to connect to ClickHouse for " + featureName + ": " + e.getMessage());
        }
    }
}
