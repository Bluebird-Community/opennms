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

import java.util.List;

import org.junit.Test;
import org.opennms.netmgt.flows.filter.api.DscpFilter;
import org.opennms.netmgt.flows.filter.api.ExporterNodeFilter;
import org.opennms.netmgt.flows.filter.api.Filter;
import org.opennms.netmgt.flows.filter.api.NodeCriteria;
import org.opennms.netmgt.flows.filter.api.SnmpInterfaceIdFilter;
import org.opennms.netmgt.flows.filter.api.TimeRangeFilter;

public class ClickhouseFlowFiltersTest {

    @Test
    public void emptyFiltersMatchAll() {
        assertEquals("1 = 1", ClickhouseFlowFilters.whereClause(List.of()));
    }

    @Test
    public void timeRange() {
        assertEquals("(timestamp >= fromUnixTimestamp64Milli(1000) AND timestamp < fromUnixTimestamp64Milli(2000))",
                ClickhouseFlowFilters.whereClause(List.of(new TimeRangeFilter(1000, 2000))));
    }

    @Test
    public void dscpSnmpAndExporterNode() {
        assertEquals("dscp IN (1, 46)",
                ClickhouseFlowFilters.whereClause(List.of(new DscpFilter(List.of(1, 46)))));
        assertEquals("(input_snmp = 5 OR output_snmp = 5)",
                ClickhouseFlowFilters.whereClause(List.of(new SnmpInterfaceIdFilter(5))));
        assertEquals("exporter_node = 42",
                ClickhouseFlowFilters.whereClause(List.of(new ExporterNodeFilter(new NodeCriteria(42)))));
    }

    @Test
    public void multipleFiltersAreConjoined() {
        final List<Filter> filters = List.of(new TimeRangeFilter(0, 10), new SnmpInterfaceIdFilter(7));
        final String where = ClickhouseFlowFilters.whereClause(filters);
        assertTrue(where.contains(" AND "));
        assertTrue(where.startsWith("(timestamp >="));
        assertTrue(where.endsWith("(input_snmp = 7 OR output_snmp = 7)"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void foreignSourceExporterNodeNotYetSupported() {
        ClickhouseFlowFilters.whereClause(List.of(new ExporterNodeFilter(new NodeCriteria("fs", "fid"))));
    }
}
