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

import org.opennms.integration.api.v1.flows.Flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Maps an integration-API {@link Flow} to a single JSONEachRow line for the ClickHouse
 * {@code flows} table. Column names, the {@code DateTime64(3)} string format, the enum string
 * values and the IPv4→v4-mapped-IPv6 convention (D-IP) all match the schema in
 * {@code ddl/01_flows.sql}. Using JSONEachRow keeps the column mapping explicit and avoids any
 * POJO/name-strategy coupling to the client library.
 */
public final class FlowRowMapper {

    /** ClickHouse DateTime64(3) literal format, always UTC. */
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FlowRowMapper() {
    }

    /**
     * @return a single JSON object (one JSONEachRow record) for the given flow.
     */
    public static String toJsonLine(final Flow flow) {
        final ObjectNode n = MAPPER.createObjectNode();

        n.put("timestamp", ts(flow.getTimestamp()));
        n.put("location", nz(flow.getLocation()));
        n.put("application", nz(flow.getApplication()));
        n.put("protocol", i(flow.getProtocol()));
        n.put("src_addr", addr(flow.getSrcAddr()));
        n.put("src_port", i(flow.getSrcPort()));
        n.put("src_as", l(flow.getSrcAs()));
        n.put("src_mask_len", i(flow.getSrcMaskLen()));
        n.put("dst_addr", addr(flow.getDstAddr()));
        n.put("dst_port", i(flow.getDstPort()));
        n.put("dst_as", l(flow.getDstAs()));
        n.put("dst_mask_len", i(flow.getDstMaskLen()));
        n.put("direction", direction(flow.getDirection()));
        n.put("exporter_node", exporterNode(flow));
        n.put("input_snmp", i(flow.getInputSnmp()));
        n.put("output_snmp", i(flow.getOutputSnmp()));
        n.put("bytes", l(flow.getBytes()));
        n.put("packets", l(flow.getPackets()));
        n.put("first_switched", ts(flow.getFirstSwitched()));
        n.put("last_switched", ts(flow.getLastSwitched()));
        n.put("dscp", i(flow.getDscp()));
        n.put("ecn", i(flow.getEcn()));
        n.put("tos", i(flow.getTos()));
        n.put("vlan", i(flow.getVlan()));
        n.put("tcp_flags", i(flow.getTcpFlags()));
        n.put("src_locality", locality(flow.getSrcLocality()));
        n.put("dst_locality", locality(flow.getDstLocality()));
        n.put("flow_locality", locality(flow.getFlowLocality()));

        return n.toString();
    }

    private static String ts(final Instant instant) {
        return TS.format(instant != null ? instant : Instant.EPOCH);
    }

    private static String nz(final String s) {
        return s != null ? s : "";
    }

    /** IPv6 column: pass the address through; an IPv4 literal is stored v4-mapped. Null → "::". */
    private static String addr(final String s) {
        return (s != null && !s.isEmpty()) ? s : "::";
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

    private static String direction(final Flow.Direction d) {
        if (d == Flow.Direction.INGRESS) {
            return "ingress";
        }
        if (d == Flow.Direction.EGRESS) {
            return "egress";
        }
        return "unknown";
    }

    private static String locality(final Flow.Locality loc) {
        if (loc == Flow.Locality.PUBLIC) {
            return "public";
        }
        if (loc == Flow.Locality.PRIVATE) {
            return "private";
        }
        return "unknown";
    }
}
