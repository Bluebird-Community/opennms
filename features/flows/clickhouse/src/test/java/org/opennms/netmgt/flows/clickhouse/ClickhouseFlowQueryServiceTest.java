/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.netmgt.flows.clickhouse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;
import org.opennms.netmgt.flows.filter.api.DscpFilter;
import org.opennms.netmgt.flows.filter.api.ExporterNodeFilter;
import org.opennms.netmgt.flows.filter.api.NodeCriteria;
import org.opennms.netmgt.flows.filter.api.SnmpInterfaceIdFilter;
import org.opennms.netmgt.flows.filter.api.TimeRangeFilter;

public class ClickhouseFlowQueryServiceTest {

    /** A single quote must be doubled and a backslash escaped, so a value cannot break out of the literal. */
    @Test
    public void quoteEscapesQuotesAndBackslashes() {
        assertEquals("'http'", ClickhouseFlowQueryService.quote("http"));
        assertEquals("'O''Brien'", ClickhouseFlowQueryService.quote("O'Brien"));
        // trailing backslash must not escape the closing quote
        assertEquals("'foo\\\\'", ClickhouseFlowQueryService.quote("foo\\"));
        assertEquals("'a\\\\''b'", ClickhouseFlowQueryService.quote("a\\'b"));
    }

    /** Only time-range + exporter-node filters exist on the rollup tables; anything else forces raw. */
    @Test
    public void rollupCompatibility() {
        assertTrue(ClickhouseFlowQueryService.rollupCompatible(List.of()));
        assertTrue(ClickhouseFlowQueryService.rollupCompatible(
                List.of(new TimeRangeFilter(0, 10), new ExporterNodeFilter(new NodeCriteria(1)))));
        assertFalse(ClickhouseFlowQueryService.rollupCompatible(List.of(new DscpFilter(List.of(46)))));
        assertFalse(ClickhouseFlowQueryService.rollupCompatible(List.of(new SnmpInterfaceIdFilter(3))));
    }

    /** Rollup WHERE targets the bucket/exporter_node columns, not the raw timestamp/snmp columns. */
    @Test
    public void rollupWhereUsesBucketColumns() {
        assertEquals("(bucket >= toStartOfMinute(fromUnixTimestamp64Milli(1000)) AND bucket < fromUnixTimestamp64Milli(2000))",
                ClickhouseFlowQueryService.rollupWhere(List.of(new TimeRangeFilter(1000, 2000))));
        assertEquals("exporter_node = 7",
                ClickhouseFlowQueryService.rollupWhere(List.of(new ExporterNodeFilter(new NodeCriteria(7)))));
        assertEquals("1 = 1", ClickhouseFlowQueryService.rollupWhere(List.of()));
    }
}
