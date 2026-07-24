/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.smoketest.containers;

import java.net.InetSocketAddress;

import org.opennms.smoketest.utils.TestContainerUtils;
import org.testcontainers.containers.Network;

public class ClickHouseContainer extends org.testcontainers.clickhouse.ClickHouseContainer {

    private static final int HTTP_PORT = 8123;

    /**
     * Credentials the container is provisioned with. These must match the values OpenNMS writes into
     * {@code org.opennms.features.flows.persistence.clickhouse.cfg} (see {@link OpenNMSContainer}),
     * otherwise the ClickHouse flows health check fails authentication and the health probe never
     * turns green. The Testcontainers ClickHouse module defaults the {@code default} user to a
     * password, so we pin an explicit user/password rather than relying on a blank one.
     */
    public static final String USERNAME = "test";
    public static final String PASSWORD = "test";
    public static final String DATABASE = "default";

    public ClickHouseContainer() {
        super("clickhouse/clickhouse-server:24.8");
        withNetwork(Network.SHARED)
                .withNetworkAliases(OpenNMSContainer.CLICKHOUSE_ALIAS)
                .withUsername(USERNAME)
                .withPassword(PASSWORD)
                .withDatabaseName(DATABASE)
                .withCreateContainerCmdModifier(TestContainerUtils::setGlobalMemAndCpuLimits);
    }

    public InetSocketAddress getRestAddress() {
        return InetSocketAddress.createUnresolved(getHost(), getMappedPort(HTTP_PORT));
    }

    public String getRestAddressString() {
        final InetSocketAddress restAddress = getRestAddress();
        return String.format("http://%s:%d", restAddress.getHostString(), restAddress.getPort());
    }
}
