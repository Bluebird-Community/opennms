/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.netmgt.flows.clickhouse;

import java.util.List;
import java.util.stream.Collectors;

import org.opennms.netmgt.flows.filter.api.DscpFilter;
import org.opennms.netmgt.flows.filter.api.ExporterNodeFilter;
import org.opennms.netmgt.flows.filter.api.Filter;
import org.opennms.netmgt.flows.filter.api.FilterVisitor;
import org.opennms.netmgt.flows.filter.api.SnmpInterfaceIdFilter;
import org.opennms.netmgt.flows.filter.api.TimeRangeFilter;

/**
 * Translates the flow {@link Filter}s into a ClickHouse SQL {@code WHERE} fragment. All emitted
 * values are numeric, so no user-controlled string is interpolated here.
 */
public final class ClickhouseFlowFilters implements FilterVisitor<String> {

    private static final ClickhouseFlowFilters INSTANCE = new ClickhouseFlowFilters();

    private ClickhouseFlowFilters() {
    }

    /**
     * @return the conjunction of all filter predicates, or {@code 1 = 1} when there are none. The
     *         returned string does not include the {@code WHERE} keyword.
     */
    public static String whereClause(final List<Filter> filters) {
        if (filters == null || filters.isEmpty()) {
            return "1 = 1";
        }
        return filters.stream()
                .map(f -> f.visit(INSTANCE))
                .collect(Collectors.joining(" AND "));
    }

    @Override
    public String visit(final TimeRangeFilter f) {
        // Interval-overlap, not a point-in-time test: a flow is in range when its
        // [delta_switched, last_switched] interval overlaps [start, end]. This matches the ES filter
        // (netflow.delta_switched <= end AND netflow.last_switched >= start), so a flow that only
        // partially overlaps the range is still selected and its bytes are apportioned by the
        // proportional summary/series math. delta_switched is the clock-skew-corrected effective start.
        return "(delta_switched <= fromUnixTimestamp64Milli(" + f.getEnd() + ") "
                + "AND last_switched >= fromUnixTimestamp64Milli(" + f.getStart() + "))";
    }

    @Override
    public String visit(final SnmpInterfaceIdFilter f) {
        final int id = f.getSnmpInterfaceId();
        // Match the flow direction to the interface (ES filter_snmp_interface.ftl): ingress uses
        // input_snmp, egress uses output_snmp, and an unknown-direction flow matches on either. The
        // query then reclassifies the unknown flow to ingress/egress by which side matched — see
        // ClickhouseFlowQueryService#directionExpr (ES onms.unknownDirectionScript).
        return "((direction = 'ingress' AND input_snmp = " + id + ")"
                + " OR (direction = 'egress' AND output_snmp = " + id + ")"
                + " OR (direction = 'unknown' AND (input_snmp = " + id + " OR output_snmp = " + id + ")))";
    }

    @Override
    public String visit(final DscpFilter f) {
        final List<Integer> dscp = f.getDscp();
        if (dscp == null || dscp.isEmpty()) {
            return "1 = 1";
        }
        return "dscp IN (" + dscp.stream().map(String::valueOf).collect(Collectors.joining(", ")) + ")";
    }

    @Override
    public String visit(final ExporterNodeFilter f) {
        final Integer nodeId = f.getCriteria().getNodeId();
        if (nodeId == null) {
            // foreignSource:foreignId criteria requires NodeDao resolution (query-service concern);
            // fail loudly rather than silently returning cross-node data.
            throw new UnsupportedOperationException(
                    "ExporterNodeFilter with foreignSource:foreignId is not yet supported; use a numeric node id.");
        }
        return "exporter_node = " + nodeId;
    }
}
