/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.netmgt.flows.clickhouse;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;

import com.clickhouse.client.api.Client;

public class ClickhouseSchemaBootstrapTest {

    /** initialize() fails fast so the ITs (and any strict caller) surface a real bootstrap problem. */
    @Test(expected = IllegalStateException.class)
    public void initializeThrowsWhenServerUnreachable() {
        final Client client = mock(Client.class);
        when(client.query(anyString())).thenThrow(new RuntimeException("connection refused"));
        new ClickhouseSchemaBootstrap(client, 0).initialize();
    }

    /** initializeQuietly() (the blueprint entry point) must NOT fail bundle startup when ClickHouse is down. */
    @Test
    public void initializeQuietlyToleratesUnreachableServer() {
        final Client client = mock(Client.class);
        when(client.query(anyString())).thenThrow(new RuntimeException("connection refused"));
        new ClickhouseSchemaBootstrap(client, 0).initializeQuietly(); // no exception = pass
    }
}
