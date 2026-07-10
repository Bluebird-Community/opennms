/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.netmgt.flows.clickhouse;

import static org.junit.Assert.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opennms.integration.api.v1.flows.Flow;
import org.opennms.netmgt.flows.api.Host;
import org.opennms.netmgt.flows.api.TrafficSummary;
import org.opennms.netmgt.flows.filter.api.Filter;
import org.opennms.netmgt.flows.filter.api.SnmpInterfaceIdFilter;
import org.opennms.netmgt.flows.filter.api.TimeRangeFilter;
import org.opennms.netmgt.flows.processing.ConversationKeyUtils;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.utility.DockerImageName;

import com.clickhouse.client.api.Client;
import com.codahale.metrics.MetricRegistry;

/**
 * Pins the unknown-direction reclassification (D-UNKNOWN, ES {@code onms.unknownDirectionScript}):
 * with an {@link SnmpInterfaceIdFilter} present, an UNKNOWN-direction flow is counted as ingress when
 * its {@code input_snmp} matches and as egress when its {@code output_snmp} matches. The two https
 * flows that {@code FlowQueryIT} marks UNKNOWN (input_snmp 98/100 and 100/98) must therefore produce
 * exactly the same https totals (210/2100) as the known-direction fixture. Without the SNMP filter
 * the unknown flows are dropped from the ingress/egress split entirely (matching ES).
 */
public class ClickhouseUnknownDirectionIT {

    private static ClickHouseContainer clickhouse;
    private static Client client;
    private static ClickhouseFlowQueryService query;

    @BeforeClass
    public static void setUp() throws Exception {
        clickhouse = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:24.8"));
        clickhouse.start();
        final String host = "localhost".equals(clickhouse.getHost()) ? "127.0.0.1" : clickhouse.getHost();
        final String endpoint = "http://" + host + ":" + clickhouse.getMappedPort(8123);
        client = new ClickhouseClientFactory(endpoint, clickhouse.getUsername(), clickhouse.getPassword(), "default")
                .createClient();
        new ClickhouseSchemaBootstrap(client, 0).initialize();

        final ClickhouseFlowRepository repository = new ClickhouseFlowRepository(new MetricRegistry(), client, "flows");
        repository.setBulkSize(1);
        // Same fixture as ClickhouseFlowQueryIT, except the two [13,26] https flows are UNKNOWN with the
        // input/output SNMP ids from FlowQueryIT.getFlowSet(true): flow-in input=98, flow-out output=98.
        repository.persist(List.of(
                flow("http", "192.168.1.100", "10.1.1.11", Flow.Direction.INGRESS, 10, 3, 15, null, null, 98, 0),
                flow("http", "10.1.1.11", "192.168.1.100", Flow.Direction.EGRESS, 100, 3, 15, null, null, 0, 98),
                flow("https", "192.168.1.100", "10.1.1.12", Flow.Direction.UNKNOWN, 100, 13, 26, null, "la.le.lu", 98, 100),
                flow("https", "10.1.1.12", "192.168.1.100", Flow.Direction.UNKNOWN, 1000, 13, 26, "la.le.lu", null, 100, 98),
                flow("https", "192.168.1.101", "10.1.1.12", Flow.Direction.INGRESS, 110, 14, 45, "ingress.only", "la.le.lu", 98, 0),
                flow("https", "10.1.1.12", "192.168.1.101", Flow.Direction.EGRESS, 1100, 14, 45, "la.le.lu", null, 0, 98),
                flow("", "192.168.1.102", "10.1.1.13", Flow.Direction.INGRESS, 200, 50, 52, null, null, 98, 0),
                flow("", "10.1.1.13", "192.168.1.102", Flow.Direction.EGRESS, 100, 50, 52, null, null, 0, 98)));

        query = new ClickhouseFlowQueryService(client, "flows");
    }

    @AfterClass
    public static void tearDown() {
        if (client != null) {
            client.close();
        }
        if (clickhouse != null) {
            clickhouse.stop();
        }
    }

    private static List<Filter> withSnmp() {
        return List.of(new TimeRangeFilter(0, System.currentTimeMillis()), new SnmpInterfaceIdFilter(98));
    }

    private static List<Filter> noSnmp() {
        return List.of(new TimeRangeFilter(0, System.currentTimeMillis()));
    }

    @Test
    public void snmpFilterMatchesUnknownFlows() throws Exception {
        // The four-branch SNMP filter includes the UNKNOWN flows (input_snmp=98 / output_snmp=98).
        assertEquals(Long.valueOf(8), query.getFlowCount(withSnmp()).get());
    }

    @Test
    public void applicationSummaryReclassifiesUnknownUnderSnmpFilter() throws Exception {
        // flow-in (input_snmp=98) -> ingress 100, flow-out (output_snmp=98) -> egress 1000, plus the
        // known https flows 110/1100 = 210/2100, identical to the known-direction fixture.
        final List<TrafficSummary<String>> s = query.getTopNApplicationSummaries(1, false, withSnmp()).get();
        assertEquals(1, s.size());
        assertEquals("https", s.get(0).getEntity());
        assertEquals(210, s.get(0).getBytesIn());
        assertEquals(2100, s.get(0).getBytesOut());
    }

    @Test
    public void hostSummaryReclassifiesUnknownUnderSnmpFilter() throws Exception {
        // 10.1.1.12 is an endpoint of both UNKNOWN https flows; reclassification must flow through the
        // host union too (input_snmp/output_snmp carried into hostUnion).
        final List<TrafficSummary<Host>> s = query.getTopNHostSummaries(1, false, withSnmp()).get();
        assertEquals(1, s.size());
        assertEquals(new Host("10.1.1.12", "la.le.lu"), s.get(0).getEntity());
        assertEquals(210, s.get(0).getBytesIn());
        assertEquals(2100, s.get(0).getBytesOut());
    }

    @Test
    public void unknownFlowsDroppedWithoutSnmpFilter() throws Exception {
        // With no SNMP filter there is nothing to reclassify by, so the UNKNOWN flows are excluded from
        // the ingress/egress split — only the known https flows (110/1100) remain.
        final List<TrafficSummary<String>> s = query.getTopNApplicationSummaries(1, false, noSnmp()).get();
        assertEquals(1, s.size());
        assertEquals("https", s.get(0).getEntity());
        assertEquals(110, s.get(0).getBytesIn());
        assertEquals(1100, s.get(0).getBytesOut());
    }

    private static Flow flow(final String app, final String src, final String dst, final Flow.Direction dir,
                             final long bytes, final long first, final long last,
                             final String srcHn, final String dstHn, final int inSnmp, final int outSnmp) {
        final Flow f = org.mockito.Mockito.mock(Flow.class);
        org.mockito.Mockito.when(f.getApplication()).thenReturn(app);
        org.mockito.Mockito.when(f.getSrcAddr()).thenReturn(src);
        org.mockito.Mockito.when(f.getDstAddr()).thenReturn(dst);
        org.mockito.Mockito.when(f.getProtocol()).thenReturn(6);
        org.mockito.Mockito.when(f.getDirection()).thenReturn(dir);
        org.mockito.Mockito.when(f.getBytes()).thenReturn(bytes);
        org.mockito.Mockito.when(f.getFirstSwitched()).thenReturn(Instant.ofEpochMilli(first));
        org.mockito.Mockito.when(f.getLastSwitched()).thenReturn(Instant.ofEpochMilli(last));
        org.mockito.Mockito.when(f.getTimestamp()).thenReturn(Instant.ofEpochMilli(last));
        org.mockito.Mockito.when(f.getLocation()).thenReturn("test");
        org.mockito.Mockito.when(f.getSrcAddrHostname()).thenReturn(Optional.ofNullable(srcHn));
        org.mockito.Mockito.when(f.getDstAddrHostname()).thenReturn(Optional.ofNullable(dstHn));
        org.mockito.Mockito.when(f.getNextHopHostname()).thenReturn(Optional.empty());
        org.mockito.Mockito.when(f.getConvoKey()).thenReturn(ConversationKeyUtils.getConvoKeyAsJsonString("test", 6, src, dst, app));
        org.mockito.Mockito.when(f.getInputSnmp()).thenReturn(inSnmp);
        org.mockito.Mockito.when(f.getOutputSnmp()).thenReturn(outSnmp);
        return f;
    }
}
