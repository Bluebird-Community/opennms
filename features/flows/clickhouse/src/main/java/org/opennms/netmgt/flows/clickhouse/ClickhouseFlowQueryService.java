/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.netmgt.flows.clickhouse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.opennms.netmgt.flows.api.Conversation;
import org.opennms.netmgt.flows.api.Directional;
import org.opennms.netmgt.flows.api.FlowQueryService;
import org.opennms.netmgt.flows.api.Host;
import org.opennms.netmgt.flows.api.LimitedCardinalityField;
import org.opennms.netmgt.flows.api.TrafficSummary;
import org.opennms.netmgt.flows.filter.api.Filter;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import com.google.common.collect.Table;

/**
 * ClickHouse-backed {@link FlowQueryService}. Short ranges hit the raw {@code flows} table; long
 * ranges will use the per-minute rollup tables (D-QUERY). Proportional byte distribution across
 * time buckets (D-PROPORTION) is applied in the series queries.
 *
 * <p>Phase-3 status: the application dimension (count, enumeration, top-N and specific summaries)
 * is implemented; the series methods and the host/conversation/field dimensions follow the same
 * patterns and currently throw {@link UnsupportedOperationException}. The exact "Other"/unknown
 * labels are reconciled against the {@code FlowQueryIT} oracle in phase 4.
 */
public class ClickhouseFlowQueryService implements FlowQueryService {

    /** Synthetic entity for the aggregate of everything outside the returned top-N (D-OTHER). */
    static final String OTHER_APPLICATION = "Other";

    private final Client client;
    private final String table;

    public ClickhouseFlowQueryService(final Client client, final String table) {
        this.client = Objects.requireNonNull(client);
        this.table = Objects.requireNonNull(table);
    }

    @Override
    public CompletableFuture<Long> getFlowCount(final List<Filter> filters) {
        final String sql = "SELECT count() AS c FROM " + table + " WHERE " + where(filters);
        return CompletableFuture.completedFuture(scalarLong(sql, "c"));
    }

    @Override
    public CompletableFuture<List<String>> getApplications(final String matchingPrefix, final long limit,
                                                           final List<Filter> filters) {
        final StringBuilder sql = new StringBuilder("SELECT DISTINCT application AS a FROM ").append(table)
                .append(" WHERE ").append(where(filters)).append(" AND application != ''");
        if (matchingPrefix != null && !matchingPrefix.isEmpty()) {
            sql.append(" AND application LIKE ").append(quote(matchingPrefix + "%"));
        }
        sql.append(" ORDER BY a LIMIT ").append(limit);
        final List<String> apps = client.queryAll(sql.toString()).stream()
                .map(r -> r.getString("a"))
                .collect(Collectors.toList());
        return CompletableFuture.completedFuture(apps);
    }

    @Override
    public CompletableFuture<List<TrafficSummary<String>>> getTopNApplicationSummaries(final int n,
                                                                                       final boolean includeOther,
                                                                                       final List<Filter> filters) {
        final String w = where(filters);
        final String sql = "SELECT application AS e,"
                + " sumIf(bytes, direction = 'ingress') AS bin,"
                + " sumIf(bytes, direction = 'egress') AS bout"
                + " FROM " + table + " WHERE " + w
                + " GROUP BY application ORDER BY (bin + bout) DESC, e LIMIT " + n;
        final List<TrafficSummary<String>> summaries = summariesFrom(client.queryAll(sql));
        if (includeOther) {
            appendOther(summaries, w);
        }
        return CompletableFuture.completedFuture(summaries);
    }

    @Override
    public CompletableFuture<List<TrafficSummary<String>>> getApplicationSummaries(final Set<String> applications,
                                                                                   final boolean includeOther,
                                                                                   final List<Filter> filters) {
        final String w = where(filters);
        final String sql = "SELECT application AS e,"
                + " sumIf(bytes, direction = 'ingress') AS bin,"
                + " sumIf(bytes, direction = 'egress') AS bout"
                + " FROM " + table + " WHERE " + w + " AND application IN (" + quoteAll(applications) + ")"
                + " GROUP BY application ORDER BY e";
        final List<TrafficSummary<String>> summaries = summariesFrom(client.queryAll(sql));
        if (includeOther) {
            appendOther(summaries, w);
        }
        return CompletableFuture.completedFuture(summaries);
    }

    // --- helpers ----------------------------------------------------------

    private static String where(final List<Filter> filters) {
        return ClickhouseFlowFilters.whereClause(filters);
    }

    private long scalarLong(final String sql, final String column) {
        final List<GenericRecord> rows = client.queryAll(sql);
        return rows.isEmpty() ? 0L : rows.get(0).getLong(column);
    }

    private static List<TrafficSummary<String>> summariesFrom(final List<GenericRecord> rows) {
        final List<TrafficSummary<String>> out = new ArrayList<>(rows.size());
        for (final GenericRecord r : rows) {
            out.add(TrafficSummary.from(r.getString("e"))
                    .withBytes(r.getLong("bin"), r.getLong("bout"))
                    .build());
        }
        return out;
    }

    /** Add the "Other" bucket = grand total (matching the filters) minus the summaries already collected. */
    private void appendOther(final List<TrafficSummary<String>> summaries, final String whereClause) {
        final List<GenericRecord> grand = client.queryAll(
                "SELECT sumIf(bytes, direction = 'ingress') AS bin, sumIf(bytes, direction = 'egress') AS bout"
                        + " FROM " + table + " WHERE " + whereClause);
        final long grandIn = grand.isEmpty() ? 0L : grand.get(0).getLong("bin");
        final long grandOut = grand.isEmpty() ? 0L : grand.get(0).getLong("bout");
        final long topIn = summaries.stream().mapToLong(TrafficSummary::getBytesIn).sum();
        final long topOut = summaries.stream().mapToLong(TrafficSummary::getBytesOut).sum();
        final long otherIn = grandIn - topIn;
        final long otherOut = grandOut - topOut;
        if (otherIn > 0 || otherOut > 0) {
            summaries.add(TrafficSummary.from(OTHER_APPLICATION).withBytes(otherIn, otherOut).build());
        }
    }

    private static String quote(final String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String quoteAll(final Set<String> values) {
        return values.stream().map(ClickhouseFlowQueryService::quote).collect(Collectors.joining(", "));
    }

    private static UnsupportedOperationException todo(final String method) {
        return new UnsupportedOperationException(
                "ClickhouseFlowQueryService." + method + " is not yet implemented (phase 3 continuation).");
    }

    // --- not yet implemented (phase 3 continuation) -----------------------

    @Override
    public CompletableFuture<Table<Directional<String>, Long, Double>> getApplicationSeries(
            final Set<String> applications, final long step, final boolean includeOther, final List<Filter> filters) {
        throw todo("getApplicationSeries");
    }

    @Override
    public CompletableFuture<Table<Directional<String>, Long, Double>> getTopNApplicationSeries(
            final int n, final long step, final boolean includeOther, final List<Filter> filters) {
        throw todo("getTopNApplicationSeries");
    }

    @Override
    public CompletableFuture<List<String>> getConversations(final String locationPattern, final String protocolPattern,
                                                            final String lowerIPPattern, final String upperIPPattern,
                                                            final String applicationPattern, final long limit,
                                                            final List<Filter> filters) {
        throw todo("getConversations");
    }

    @Override
    public CompletableFuture<List<TrafficSummary<Conversation>>> getTopNConversationSummaries(
            final int n, final boolean includeOther, final List<Filter> filters) {
        throw todo("getTopNConversationSummaries");
    }

    @Override
    public CompletableFuture<List<TrafficSummary<Conversation>>> getConversationSummaries(
            final Set<String> conversations, final boolean includeOther, final List<Filter> filters) {
        throw todo("getConversationSummaries");
    }

    @Override
    public CompletableFuture<Table<Directional<Conversation>, Long, Double>> getConversationSeries(
            final Set<String> conversations, final long step, final boolean includeOther, final List<Filter> filters) {
        throw todo("getConversationSeries");
    }

    @Override
    public CompletableFuture<Table<Directional<Conversation>, Long, Double>> getTopNConversationSeries(
            final int n, final long step, final boolean includeOther, final List<Filter> filters) {
        throw todo("getTopNConversationSeries");
    }

    @Override
    public CompletableFuture<List<String>> getHosts(final String regex, final long limit, final List<Filter> filters) {
        throw todo("getHosts");
    }

    @Override
    public CompletableFuture<List<TrafficSummary<Host>>> getTopNHostSummaries(
            final int n, final boolean includeOther, final List<Filter> filters) {
        throw todo("getTopNHostSummaries");
    }

    @Override
    public CompletableFuture<List<TrafficSummary<Host>>> getHostSummaries(
            final Set<String> hosts, final boolean includeOther, final List<Filter> filters) {
        throw todo("getHostSummaries");
    }

    @Override
    public CompletableFuture<Table<Directional<Host>, Long, Double>> getHostSeries(
            final Set<String> hosts, final long step, final boolean includeOther, final List<Filter> filters) {
        throw todo("getHostSeries");
    }

    @Override
    public CompletableFuture<Table<Directional<Host>, Long, Double>> getTopNHostSeries(
            final int n, final long step, final boolean includeOther, final List<Filter> filters) {
        throw todo("getTopNHostSeries");
    }

    @Override
    public CompletableFuture<List<String>> getFieldValues(final LimitedCardinalityField field,
                                                          final List<Filter> filters) {
        throw todo("getFieldValues");
    }

    @Override
    public CompletableFuture<List<TrafficSummary<String>>> getFieldSummaries(final LimitedCardinalityField field,
                                                                             final List<Filter> filters) {
        throw todo("getFieldSummaries");
    }

    @Override
    public CompletableFuture<Table<Directional<String>, Long, Double>> getFieldSeries(
            final LimitedCardinalityField field, final long step, final List<Filter> filters) {
        throw todo("getFieldSeries");
    }
}
