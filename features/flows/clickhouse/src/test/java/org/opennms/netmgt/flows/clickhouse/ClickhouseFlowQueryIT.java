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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opennms.integration.api.v1.flows.Flow;
import org.opennms.netmgt.flows.api.Conversation;
import org.opennms.netmgt.flows.api.Directional;
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
import com.google.common.collect.Table;

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

    @Test
    public void applicationSummariesForSet() throws Exception {
        final List<TrafficSummary<String>> one = query.getApplicationSummaries(Set.of("https"), false, filters()).get();
        assertEquals(1, one.size());
        assertSummary(one.get(0), "https", 210, 2100);

        final List<TrafficSummary<String>> two = query.getApplicationSummaries(Set.of("https", "http"), false, filters()).get();
        assertEquals(2, two.size());
        assertSummary(find(two, "https"), "https", 210, 2100);
        assertSummary(find(two, "http"), "http", 10, 100);

        // Specific-set summaries follow the input collection's iteration order (ES contract), NOT
        // byte-descending: passing [http, https] must return http first even though https has more
        // bytes. A LinkedHashSet pins a deterministic order (Set.of has none).
        final Set<String> ordered = new LinkedHashSet<>(List.of("http", "https"));
        final List<String> byInput = query.getApplicationSummaries(ordered, false, filters()).get()
                .stream().map(TrafficSummary::getEntity).collect(Collectors.toList());
        assertEquals(List.of("http", "https"), byInput);
    }

    @Test
    public void applicationSummariesPartialRange() throws Exception {
        // Proportional (partial) summary over a sub-range: the https flows [13,26] and [14,45] only
        // partially overlap [10,20), so their bytes are apportioned by overlap/duration
        // (100*7/13 + 110*6/31 = 75.13 in; ×~10 = 751.36 out) — matching FlowQueryIT's 75/751 oracle.
        final List<Filter> range = List.of(new TimeRangeFilter(10, 20), new SnmpInterfaceIdFilter(98));
        final List<TrafficSummary<String>> s = query.getTopNApplicationSummaries(1, false, range).get();
        assertEquals(1, s.size());
        assertSummary(s.get(0), "https", 75, 751);
    }

    @Test
    public void hostSummariesForSet() throws Exception {
        final List<TrafficSummary<Host>> two = query.getHostSummaries(Set.of("10.1.1.11", "10.1.1.12"), false, filters()).get();
        assertEquals(2, two.size());
        final TrafficSummary<Host> h12 = two.stream().filter(s -> "10.1.1.12".equals(s.getEntity().getIp())).findFirst().orElseThrow();
        assertEquals(new Host("10.1.1.12", "la.le.lu"), h12.getEntity());
        assertEquals(210, h12.getBytesIn());
        assertEquals(2100, h12.getBytesOut());
        final TrafficSummary<Host> h11 = two.stream().filter(s -> "10.1.1.11".equals(s.getEntity().getIp())).findFirst().orElseThrow();
        assertEquals(new Host("10.1.1.11"), h11.getEntity()); // no hostname in the fixture for this endpoint
        assertEquals(10, h11.getBytesIn());
        assertEquals(100, h11.getBytesOut());
    }

    @Test
    public void conversationSummariesForSet() throws Exception {
        final String key = ConversationKeyUtils.getConvoKeyAsJsonString("test", 6, "192.168.1.100", "10.1.1.11", "http");
        final List<TrafficSummary<Conversation>> s = query.getConversationSummaries(Set.of(key), false, filters()).get();
        assertEquals(1, s.size());
        final Conversation c = s.get(0).getEntity();
        assertEquals("10.1.1.11", c.getLowerIp());
        assertEquals("192.168.1.100", c.getUpperIp());
        assertEquals("http", c.getApplication());
        assertEquals(10, s.get(0).getBytesIn());
        assertEquals(100, s.get(0).getBytesOut());
    }

    @Test
    public void getHostsRegex() throws Exception {
        assertEquals(List.of("10.1.1.11"), query.getHosts(".*", 1, filters()).get());
        assertEquals(List.of("10.1.1.11", "10.1.1.12", "10.1.1.13", "192.168.1.100", "192.168.1.101", "192.168.1.102"),
                query.getHosts(".*", 10, filters()).get());
        assertEquals(List.of("10.1.1.11", "10.1.1.12", "10.1.1.13"),
                query.getHosts("^10\\.1\\.1\\.", 10, filters()).get());
    }

    private static TrafficSummary<String> find(final List<TrafficSummary<String>> list, final String entity) {
        return list.stream().filter(s -> entity.equals(s.getEntity())).findFirst().orElseThrow();
    }

    /** Buckets [10,20,30,40] with the exact proportional https ingress bytes (egress = ×10). */
    private static final long[] BUCKETS = {10, 20, 30, 40};
    private static final double[] HTTPS_INGRESS = {
            75.136476426799, 81.63771712158808, 35.483870967741936, 17.741935483870968};

    @Test
    public void topNApplicationSeries() throws Exception {
        // step = 10ms; top-1 app = https. Proportional distribution across the buckets each flow overlaps.
        final Table<Directional<String>, Long, Double> t =
                query.getTopNApplicationSeries(1, 10, false, filters()).get();
        assertEquals(2, t.rowKeySet().size());
        final Directional<String> in = new Directional<>("https", true);
        final Directional<String> out = new Directional<>("https", false);
        for (int i = 0; i < BUCKETS.length; i++) {
            assertEquals(HTTPS_INGRESS[i], t.get(in, BUCKETS[i]), 1e-8);
            assertEquals(HTTPS_INGRESS[i] * 10, t.get(out, BUCKETS[i]), 1e-8);
        }
    }

    @Test
    public void topNHostSeries() throws Exception {
        // top-1 host = 10.1.1.12; its ingress/egress series equal the https series above.
        final Table<Directional<Host>, Long, Double> t =
                query.getTopNHostSeries(1, 10, false, filters()).get();
        assertEquals(2, t.rowKeySet().size());
        final Host host = new Host("10.1.1.12", "la.le.lu");
        final Directional<Host> in = new Directional<>(host, true);
        final Directional<Host> out = new Directional<>(host, false);
        for (int i = 0; i < BUCKETS.length; i++) {
            assertEquals(HTTPS_INGRESS[i], t.get(in, BUCKETS[i]), 1e-8);
            assertEquals(HTTPS_INGRESS[i] * 10, t.get(out, BUCKETS[i]), 1e-8);
        }
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
