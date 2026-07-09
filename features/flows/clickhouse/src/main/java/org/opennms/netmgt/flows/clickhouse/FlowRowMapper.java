/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.netmgt.flows.clickhouse;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.opennms.integration.api.v1.flows.Flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.net.InetAddresses;

/**
 * Maps an integration-API {@link Flow} to a single JSONEachRow line for the ClickHouse
 * {@code flows} table. Column names come from {@link FlowColumns}; the {@code DateTime64(3)} string
 * format, the enum string values and the IPv4→v4-mapped-IPv6 convention (D-IP) all match the schema
 * in {@code ddl/01_flows.sql}. Using JSONEachRow keeps the column mapping explicit and avoids any
 * POJO/name-strategy coupling to the client library.
 */
public final class FlowRowMapper {

    /** ClickHouse DateTime64(3) literal format, always UTC. */
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    /** Enum value used for a null/absent enum; present in every enum column's Enum8 definition. */
    private static final String UNKNOWN = "unknown";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FlowRowMapper() {
    }

    /**
     * @return a single JSON object (one JSONEachRow record) for the given flow.
     */
    public static String toJsonLine(final Flow flow) {
        final ObjectNode n = MAPPER.createObjectNode();

        n.put(FlowColumns.TIMESTAMP, ts(flow.getTimestamp()));
        n.put(FlowColumns.LOCATION, nz(flow.getLocation()));
        n.put(FlowColumns.APPLICATION, nz(flow.getApplication()));
        n.put(FlowColumns.PROTOCOL, i(flow.getProtocol()));
        n.put(FlowColumns.SRC_ADDR, addr(flow.getSrcAddr()));
        n.put(FlowColumns.SRC_PORT, i(flow.getSrcPort()));
        n.put(FlowColumns.SRC_AS, l(flow.getSrcAs()));
        n.put(FlowColumns.SRC_MASK_LEN, i(flow.getSrcMaskLen()));
        n.put(FlowColumns.DST_ADDR, addr(flow.getDstAddr()));
        n.put(FlowColumns.DST_PORT, i(flow.getDstPort()));
        n.put(FlowColumns.DST_AS, l(flow.getDstAs()));
        n.put(FlowColumns.DST_MASK_LEN, i(flow.getDstMaskLen()));
        n.put(FlowColumns.DIRECTION, enumName(flow.getDirection()));
        n.put(FlowColumns.EXPORTER_NODE, exporterNode(flow));
        n.put(FlowColumns.INPUT_SNMP, i(flow.getInputSnmp()));
        n.put(FlowColumns.OUTPUT_SNMP, i(flow.getOutputSnmp()));
        n.put(FlowColumns.BYTES, l(flow.getBytes()));
        n.put(FlowColumns.PACKETS, l(flow.getPackets()));
        n.put(FlowColumns.FIRST_SWITCHED, ts(flow.getFirstSwitched()));
        n.put(FlowColumns.LAST_SWITCHED, ts(flow.getLastSwitched()));
        n.put(FlowColumns.DSCP, i(flow.getDscp()));
        n.put(FlowColumns.ECN, i(flow.getEcn()));
        n.put(FlowColumns.TOS, i(flow.getTos()));
        n.put(FlowColumns.VLAN, i(flow.getVlan()));
        n.put(FlowColumns.TCP_FLAGS, i(flow.getTcpFlags()));
        n.put(FlowColumns.SRC_LOCALITY, enumName(flow.getSrcLocality()));
        n.put(FlowColumns.DST_LOCALITY, enumName(flow.getDstLocality()));
        n.put(FlowColumns.FLOW_LOCALITY, enumName(flow.getFlowLocality()));

        return n.toString();
    }

    private static String ts(final Instant instant) {
        return TS.format(instant != null ? instant : Instant.EPOCH);
    }

    private static String nz(final String s) {
        return s != null ? s : "";
    }

    /**
     * IPv6 column: an IPv4 literal is stored v4-mapped. A null or non-IP value (e.g. a hostname or a
     * malformed literal) is replaced with "::" so a single bad address cannot fail the whole batch
     * insert; only that row's address is lost, not the batch.
     */
    private static String addr(final String s) {
        return (s != null && InetAddresses.isInetAddress(s)) ? s : "::";
    }

    private static int i(final Integer v) {
        return v != null ? v : 0;
    }

    private static long l(final Long v) {
        return v != null ? v : 0L;
    }

    private static int exporterNode(final Flow flow) {
        final Flow.NodeInfo exporter = flow.getExporterNodeInfo();
        return exporter != null ? exporter.getNodeId() : 0;
    }

    /**
     * Canonical Enum8 value for a {@link Flow} enum: the lowercased enum name, or "unknown" when
     * null. This tracks the enum automatically (no hand-maintained switch); every value the API
     * defines — {@code Direction}={INGRESS,EGRESS,UNKNOWN}, {@code Locality}={PUBLIC,PRIVATE} — is
     * present in the corresponding Enum8 in the DDL.
     */
    private static String enumName(final Enum<?> value) {
        return value != null ? value.name().toLowerCase(Locale.ROOT) : UNKNOWN;
    }
}
