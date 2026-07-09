/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.netmgt.flows.clickhouse;

import java.util.List;

/**
 * Column names of the ClickHouse {@code flows} table. Single source of truth shared by
 * {@link FlowRowMapper}; {@code FlowRowMapperTest} asserts that {@link #ALL} matches the columns
 * declared in {@code ddl/01_flows.sql}, so a column added/renamed in one place but not the other
 * fails the build rather than silently dropping data or failing against a live server.
 */
public final class FlowColumns {

    public static final String TIMESTAMP = "timestamp";
    public static final String LOCATION = "location";
    public static final String APPLICATION = "application";
    public static final String PROTOCOL = "protocol";
    public static final String SRC_ADDR = "src_addr";
    public static final String SRC_PORT = "src_port";
    public static final String SRC_AS = "src_as";
    public static final String SRC_MASK_LEN = "src_mask_len";
    public static final String DST_ADDR = "dst_addr";
    public static final String DST_PORT = "dst_port";
    public static final String DST_AS = "dst_as";
    public static final String DST_MASK_LEN = "dst_mask_len";
    public static final String DIRECTION = "direction";
    public static final String EXPORTER_NODE = "exporter_node";
    public static final String INPUT_SNMP = "input_snmp";
    public static final String OUTPUT_SNMP = "output_snmp";
    public static final String BYTES = "bytes";
    public static final String PACKETS = "packets";
    public static final String FIRST_SWITCHED = "first_switched";
    public static final String LAST_SWITCHED = "last_switched";
    public static final String DSCP = "dscp";
    public static final String ECN = "ecn";
    public static final String TOS = "tos";
    public static final String VLAN = "vlan";
    public static final String TCP_FLAGS = "tcp_flags";
    public static final String SRC_LOCALITY = "src_locality";
    public static final String DST_LOCALITY = "dst_locality";
    public static final String FLOW_LOCALITY = "flow_locality";

    /** Every column of the {@code flows} table, in DDL order. */
    public static final List<String> ALL = List.of(
            TIMESTAMP, LOCATION, APPLICATION, PROTOCOL,
            SRC_ADDR, SRC_PORT, SRC_AS, SRC_MASK_LEN,
            DST_ADDR, DST_PORT, DST_AS, DST_MASK_LEN,
            DIRECTION, EXPORTER_NODE, INPUT_SNMP, OUTPUT_SNMP,
            BYTES, PACKETS, FIRST_SWITCHED, LAST_SWITCHED,
            DSCP, ECN, TOS, VLAN, TCP_FLAGS,
            SRC_LOCALITY, DST_LOCALITY, FLOW_LOCALITY);

    private FlowColumns() {
    }
}
