/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.netmgt.flows.clickhouse;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;
import org.opennms.integration.api.v1.flows.Flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class FlowRowMapperTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** The JSON keys the mapper emits must be exactly the columns the mapper declares, in order. */
    @Test
    public void emitsExactlyDeclaredColumns() throws Exception {
        final Flow flow = mock(Flow.class);
        final JsonNode node = OM.readTree(FlowRowMapper.toJsonLine(flow));

        final List<String> emitted = new ArrayList<>();
        for (final Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            emitted.add(it.next());
        }
        assertEquals(FlowColumns.ALL, emitted);
    }

    /** The declared columns must match the actual `flows` DDL, so drift fails the build. */
    @Test
    public void declaredColumnsMatchDdl() throws Exception {
        assertEquals(parseFlowsColumns(), FlowColumns.ALL);
    }

    /** A null or non-IP address must become "::" so one bad row cannot poison the batch. */
    @Test
    public void invalidAddressBecomesUnspecified() throws Exception {
        final Flow flow = mock(Flow.class);
        when(flow.getSrcAddr()).thenReturn("host.example.com");     // not an IP literal
        when(flow.getDstAddr()).thenReturn("10.0.0.5");             // valid IPv4
        final JsonNode node = OM.readTree(FlowRowMapper.toJsonLine(flow));
        assertEquals("::", node.get(FlowColumns.SRC_ADDR).asText());
        assertEquals("10.0.0.5", node.get(FlowColumns.DST_ADDR).asText());
    }

    /** Enum values are the lowercased enum name; null → "unknown". */
    @Test
    public void enumMapping() throws Exception {
        final Flow flow = mock(Flow.class);
        when(flow.getDirection()).thenReturn(Flow.Direction.EGRESS);
        when(flow.getSrcLocality()).thenReturn(Flow.Locality.PRIVATE);
        // getDstLocality() left null
        final JsonNode node = OM.readTree(FlowRowMapper.toJsonLine(flow));
        assertEquals("egress", node.get(FlowColumns.DIRECTION).asText());
        assertEquals("private", node.get(FlowColumns.SRC_LOCALITY).asText());
        assertEquals("unknown", node.get(FlowColumns.DST_LOCALITY).asText());
    }

    /** Extract column names from the CREATE TABLE block of ddl/01_flows.sql. */
    private static List<String> parseFlowsColumns() throws Exception {
        final Pattern column = Pattern.compile("^([a-z_][a-z0-9_]*)\\s+\\S");
        final List<String> columns = new ArrayList<>();
        boolean inColumns = false;
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(
                FlowRowMapperTest.class.getResourceAsStream("/ddl/01_flows.sql"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String trimmed = line.trim();
                if (trimmed.startsWith("CREATE TABLE")) {
                    inColumns = true;
                    continue;
                }
                if (!inColumns || trimmed.startsWith("--")) {
                    continue;
                }
                if (trimmed.startsWith("ENGINE")) {
                    break;
                }
                final Matcher m = column.matcher(trimmed);
                if (m.find()) {
                    columns.add(m.group(1));
                }
            }
        }
        return columns;
    }
}
