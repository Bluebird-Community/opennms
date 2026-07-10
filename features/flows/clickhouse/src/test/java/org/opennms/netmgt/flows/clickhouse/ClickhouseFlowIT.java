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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opennms.integration.api.v1.flows.Flow;
import org.opennms.netmgt.flows.api.TrafficSummary;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.utility.DockerImageName;

import com.clickhouse.client.api.Client;
import com.codahale.metrics.MetricRegistry;

/**
 * End-to-end integration test of the ClickHouse flow stack against a real server: schema bootstrap,
 * the Path A {@link ClickhouseFlowRepository} write path, and the {@link ClickhouseFlowQueryService}
 * read path. Runs with {@code ttlDays=0} so fixture timestamps are never TTL-expired (spike-0.2
 * gotcha). This is the seed of the phase-4 oracle harness; the full {@code FlowQueryIT} assertions
 * are ported on top.
 */
public class ClickhouseFlowIT {

    private static final Instant T = Instant.parse("2026-07-10T10:00:00Z");

    private static ClickHouseContainer clickhouse;
    private static Client client;
    private static ClickhouseFlowQueryService query;

    @BeforeClass
    public static void setUp() throws Exception {
        clickhouse = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:24.8"));
        clickhouse.start();

        // Use 127.0.0.1 rather than the "localhost" testcontainers reports: the client's HTTP
        // transport otherwise tries the IPv6 loopback (::1), where the mapped port isn't bound, and
        // hangs until the connection-request timeout.
        final String host = "localhost".equals(clickhouse.getHost()) ? "127.0.0.1" : clickhouse.getHost();
        final String endpoint = "http://" + host + ":" + clickhouse.getMappedPort(8123);
        client = new ClickhouseClientFactory(endpoint, clickhouse.getUsername(), clickhouse.getPassword(), "default")
                .createClient();

        new ClickhouseSchemaBootstrap(client, 0).initialize();

        final ClickhouseFlowRepository repository = new ClickhouseFlowRepository(new MetricRegistry(), client, "flows");
        repository.setBulkSize(1); // flush each persist() synchronously
        repository.persist(List.of(
                flow("http", "10.0.0.1", "10.0.0.2", Flow.Direction.INGRESS, 100L),
                flow("http", "10.0.0.2", "10.0.0.1", Flow.Direction.EGRESS, 40L),
                flow("https", "10.0.0.1", "10.0.0.3", Flow.Direction.INGRESS, 200L)));

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

    @Test
    public void countsPersistedFlows() throws Exception {
        assertEquals(Long.valueOf(3), query.getFlowCount(List.of()).get());
    }

    @Test
    public void enumeratesApplications() throws Exception {
        assertEquals(List.of("http", "https"), query.getApplications("", 10, List.of()).get());
    }

    @Test
    public void topNApplicationSummariesMatchIngestedBytes() throws Exception {
        final List<TrafficSummary<String>> summaries = query.getTopNApplicationSummaries(10, false, List.of()).get();
        assertEquals(2, summaries.size());
        // https has the most total bytes (200) and sorts first.
        final TrafficSummary<String> https = summaries.get(0);
        assertEquals("https", https.getEntity());
        assertEquals(200L, https.getBytesIn());
        assertEquals(0L, https.getBytesOut());
        final TrafficSummary<String> http = summaries.get(1);
        assertEquals("http", http.getEntity());
        assertEquals(100L, http.getBytesIn());
        assertEquals(40L, http.getBytesOut());
    }

    @Test
    public void includeOtherAddsResidual() throws Exception {
        final List<TrafficSummary<String>> summaries = query.getTopNApplicationSummaries(1, true, List.of()).get();
        // top-1 = https(200/0); Other = everything else = http(100/40)
        assertEquals(2, summaries.size());
        assertTrue(summaries.stream().anyMatch(s -> "Other".equals(s.getEntity())
                && s.getBytesIn() == 100L && s.getBytesOut() == 40L));
    }

    private static Flow flow(final String application, final String src, final String dst,
                             final Flow.Direction direction, final long bytes) {
        final Flow f = mock(Flow.class);
        when(f.getApplication()).thenReturn(application);
        when(f.getSrcAddr()).thenReturn(src);
        when(f.getDstAddr()).thenReturn(dst);
        when(f.getDirection()).thenReturn(direction);
        when(f.getBytes()).thenReturn(bytes);
        when(f.getProtocol()).thenReturn(6);
        when(f.getTimestamp()).thenReturn(T);
        when(f.getFirstSwitched()).thenReturn(T);
        when(f.getLastSwitched()).thenReturn(T);
        when(f.getSrcAddrHostname()).thenReturn(Optional.empty());
        when(f.getDstAddrHostname()).thenReturn(Optional.empty());
        when(f.getNextHopHostname()).thenReturn(Optional.empty());
        return f;
    }
}
