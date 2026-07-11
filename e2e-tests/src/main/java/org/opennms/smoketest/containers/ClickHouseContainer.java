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

    public ClickHouseContainer() {
        super("clickhouse/clickhouse-server:24.8");
        withNetwork(Network.SHARED)
                .withNetworkAliases(OpenNMSContainer.CLICKHOUSE_ALIAS)
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
