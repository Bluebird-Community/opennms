/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.netmgt.flows.clickhouse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Hashtable;
import java.util.List;

import org.junit.Test;
import org.opennms.core.health.api.Response;
import org.opennms.core.health.api.Status;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import com.clickhouse.client.api.Client;

public class ClickhouseHealthCheckTest {

    private static final String PID = "org.opennms.features.flows.persistence.clickhouse";

    @Test
    public void reportsSuccessWhenNotConfigured() throws Exception {
        final Client client = mock(Client.class);
        final Configuration config = mock(Configuration.class);
        when(config.getProperties()).thenReturn(null); // no ConfigAdmin properties yet
        final ConfigurationAdmin configAdmin = mock(ConfigurationAdmin.class);
        when(configAdmin.getConfiguration(PID)).thenReturn(config);

        final Response r = new ClickhouseHealthCheck(client, "Flows", configAdmin, PID).perform(null);

        assertEquals(Status.Success, r.getStatus());
        assertEquals("Not configured", r.getMessage());
        verifyNoInteractions(client); // must not touch ClickHouse when unconfigured
    }

    @Test
    public void reportsSuccessWhenReachable() throws Exception {
        final Client client = mock(Client.class);
        when(client.queryAll("SELECT 1")).thenReturn(List.of());
        final ClickhouseHealthCheck check = configuredCheck(client);

        final Response r = check.perform(null);

        assertTrue(r.isSuccess());
        assertTrue(r.getMessage().contains("reachable"));
    }

    @Test
    public void reportsFailureWhenUnreachable() throws Exception {
        final Client client = mock(Client.class);
        when(client.queryAll(anyString())).thenThrow(new RuntimeException("connection refused"));
        final ClickhouseHealthCheck check = configuredCheck(client);

        final Response r = check.perform(null);

        assertEquals(Status.Failure, r.getStatus());
        assertTrue(r.getMessage().contains("connection refused"));
    }

    @Test
    public void reportsFailureWhenConfigAdminThrows() throws Exception {
        final Client client = mock(Client.class);
        final ConfigurationAdmin configAdmin = mock(ConfigurationAdmin.class);
        when(configAdmin.getConfiguration(PID)).thenThrow(new IOException("configadmin unavailable"));

        final Response r = new ClickhouseHealthCheck(client, "Flows", configAdmin, PID).perform(null);

        assertEquals(Status.Failure, r.getStatus());
        assertTrue(r.getMessage().contains("configadmin unavailable"));
        verifyNoInteractions(client);
    }

    private static ClickhouseHealthCheck configuredCheck(final Client client) throws Exception {
        final Configuration config = mock(Configuration.class);
        when(config.getProperties()).thenReturn(new Hashtable<>()); // non-null -> configured
        final ConfigurationAdmin configAdmin = mock(ConfigurationAdmin.class);
        when(configAdmin.getConfiguration(PID)).thenReturn(config);
        return new ClickhouseHealthCheck(client, "Flows", configAdmin, PID);
    }
}
