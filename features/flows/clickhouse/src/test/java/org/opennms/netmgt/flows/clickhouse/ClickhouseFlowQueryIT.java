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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opennms.integration.api.v1.flows.Flow;
import org.opennms.netmgt.flows.api.Conversation;
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
 * Oracle integration test: loads the exact fixture from the Elasticsearch {@code FlowQueryIT} into
 * ClickHouse and asserts the same expected values through {@link ClickhouseFlowQueryService}. This
 * pins the ClickHouse read path to the established ES contract — including the {@code Unknown} /
 * {@code Other} entities, dotted-quad host IPs, and conversation lower/upper hostname mapping.
 */
public class ClickhouseFlowQueryIT {

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
        repository.persist(List.of(
                flow("http", "192.168.1.100", "10.1.1.11", Flow.Direction.INGRESS, 10, 3, 15, null, null),
                flow("http", "10.1.1.11", "192.168.1.100", Flow.Direction.EGRESS, 100, 3, 15, null, null),
                flow("https", "192.168.1.100", "10.1.1.12", Flow.Direction.INGRESS, 100, 13, 26, null, "la.le.lu"),
                flow("https", "10.1.1.12", "192.168.1.100", Flow.Direction.EGRESS, 1000, 13, 26, "la.le.lu", null),
                flow("https", "192.168.1.101", "10.1.1.12", Flow.Direction.INGRESS, 110, 14, 45, "ingress.only", "la.le.lu"),
                flow("https", "10.1.1.12", "192.168.1.101", Flow.Direction.EGRESS, 1100, 14, 45, "la.le.lu", null),
                flow("", "192.168.1.102", "10.1.1.13", Flow.Direction.INGRESS, 200, 50, 52, null, null),
                flow("", "10.1.1.13", "192.168.1.102", Flow.Direction.EGRESS, 100, 50, 52, null, null)));

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

    private static List<Filter> filters() {
        return List.of(new TimeRangeFilter(0, System.currentTimeMillis()), new SnmpInterfaceIdFilter(98));
    }

    @Test
    public void flowCount() throws Exception {
        assertEquals(Long.valueOf(8), query.getFlowCount(filters()).get());
    }

    @Test
    public void applications() throws Exception {
        assertEquals(List.of("http", "https"), query.getApplications("", 10, filters()).get());
    }

    @Test
    public void topNApplicationSummaries() throws Exception {
        final List<TrafficSummary<String>> s = query.getTopNApplicationSummaries(10, true, filters()).get();
        assertEquals(4, s.size());
        assertSummary(s.get(0), "https", 210, 2100);
        assertSummary(s.get(1), "Unknown", 200, 100);
        assertSummary(s.get(2), "http", 10, 100);
        assertSummary(s.get(3), "Other", 0, 0);

        final List<TrafficSummary<String>> top1 = query.getTopNApplicationSummaries(1, true, filters()).get();
        assertEquals(2, top1.size());
        assertSummary(top1.get(0), "https", 210, 2100);
        assertSummary(top1.get(1), "Other", 210, 200);
    }

    @Test
    public void topNHostSummaries() throws Exception {
        final List<TrafficSummary<Host>> s = query.getTopNHostSummaries(10, false, filters()).get();
        assertEquals(6, s.size());
        assertEquals(new Host("10.1.1.12", "la.le.lu"), s.get(0).getEntity());
        assertEquals(210, s.get(0).getBytesIn());
        assertEquals(2100, s.get(0).getBytesOut());

        final List<TrafficSummary<Host>> top1 = query.getTopNHostSummaries(1, true, filters()).get();
        assertEquals(2, top1.size());
        assertEquals(new Host("10.1.1.12", "la.le.lu"), top1.get(0).getEntity());
        assertEquals(Host.forOther().build(), top1.get(1).getEntity());
        assertEquals(210, top1.get(1).getBytesIn());
        assertEquals(200, top1.get(1).getBytesOut());
    }

    @Test
    public void topNConversationSummaries() throws Exception {
        final List<TrafficSummary<Conversation>> s = query.getTopNConversationSummaries(2, false, filters()).get();
        assertEquals(2, s.size());

        final Conversation c0 = s.get(0).getEntity();
        assertEquals("10.1.1.12", c0.getLowerIp());
        assertEquals("192.168.1.101", c0.getUpperIp());
        assertEquals("https", c0.getApplication());
        assertEquals(Optional.of("la.le.lu"), c0.getLowerHostname());
        assertEquals(Optional.of("ingress.only"), c0.getUpperHostname());
        assertEquals(110, s.get(0).getBytesIn());
        assertEquals(1100, s.get(0).getBytesOut());

        final Conversation c1 = s.get(1).getEntity();
        assertEquals("10.1.1.12", c1.getLowerIp());
        assertEquals("192.168.1.100", c1.getUpperIp());
        assertEquals("https", c1.getApplication());
        assertEquals(100, s.get(1).getBytesIn());
        assertEquals(1000, s.get(1).getBytesOut());
    }

    private static void assertSummary(final TrafficSummary<String> s, final String entity,
                                      final long in, final long out) {
        assertEquals(entity, s.getEntity());
        assertEquals(in, s.getBytesIn());
        assertEquals(out, s.getBytesOut());
    }

    private static Flow flow(final String app, final String src, final String dst, final Flow.Direction dir,
                             final long bytes, final long first, final long last,
                             final String srcHn, final String dstHn) {
        final Flow f = mock(Flow.class);
        when(f.getApplication()).thenReturn(app);
        when(f.getSrcAddr()).thenReturn(src);
        when(f.getDstAddr()).thenReturn(dst);
        when(f.getProtocol()).thenReturn(6);
        when(f.getDirection()).thenReturn(dir);
        when(f.getBytes()).thenReturn(bytes);
        when(f.getFirstSwitched()).thenReturn(Instant.ofEpochMilli(first));
        when(f.getLastSwitched()).thenReturn(Instant.ofEpochMilli(last));
        when(f.getTimestamp()).thenReturn(Instant.ofEpochMilli(last));
        when(f.getLocation()).thenReturn("test");
        when(f.getSrcAddrHostname()).thenReturn(Optional.ofNullable(srcHn));
        when(f.getDstAddrHostname()).thenReturn(Optional.ofNullable(dstHn));
        when(f.getNextHopHostname()).thenReturn(Optional.empty());
        when(f.getConvoKey()).thenReturn(ConversationKeyUtils.getConvoKeyAsJsonString("test", 6, src, dst, app));
        if (dir == Flow.Direction.INGRESS) {
            when(f.getInputSnmp()).thenReturn(98);
        } else {
            when(f.getOutputSnmp()).thenReturn(98);
        }
        return f;
    }
}
