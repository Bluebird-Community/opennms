/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.netmgt.flows.clickhouse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import org.opennms.netmgt.flows.filter.api.TimeRangeFilter;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import com.google.common.collect.HashBasedTable;
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
        if (applications.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
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

    // Package-private for testing. Escape backslashes before quotes: ClickHouse honours
    // backslash escapes inside string literals, so a trailing '\' would otherwise escape the
    // closing quote and let the value break out of the literal.
    static String quote(final String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
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
        return CompletableFuture.completedFuture(applicationSeries(applications, step, includeOther, filters));
    }

    @Override
    public CompletableFuture<Table<Directional<String>, Long, Double>> getTopNApplicationSeries(
            final int n, final long step, final boolean includeOther, final List<Filter> filters) {
        final Set<String> topN = topNApplications(n, where(filters));
        return CompletableFuture.completedFuture(applicationSeries(topN, step, includeOther, filters));
    }

    /**
     * Proportional byte-per-bucket series for the given applications (D-PROPORTION). Each flow's
     * bytes are spread across the step-aligned buckets its {@code [first_switched, last_switched]}
     * interval overlaps, weighted by the overlap. When {@code includeOther}, an "Other" row per
     * direction/bucket carries the grand total minus the selected applications.
     */
    private Table<Directional<String>, Long, Double> applicationSeries(final Set<String> applications,
                                                                       final long step, final boolean includeOther,
                                                                       final List<Filter> filters) {
        final Table<Directional<String>, Long, Double> table = HashBasedTable.create();
        // No entities requested -> empty series (do not emit an all-traffic "Other").
        if (applications.isEmpty()) {
            return table;
        }
        final long[] range = timeRange(filters);
        final String w = where(filters);
        // selected[bucket] = {ingressSum, egressSum}, only tracked when we need the "Other" residual.
        final Map<Long, double[]> selectedByBucket = includeOther ? new HashMap<>() : null;

        final String appFilter = "application IN (" + quoteAll(applications) + ")";
        for (final GenericRecord r : client.queryAll(seriesSql("application", appFilter, w, range[0], range[1], step))) {
            final boolean ingress = "ingress".equals(r.getString("d"));
            final long bucket = r.getLong("b");
            final double bytes = r.getDouble("bytes");
            table.put(new Directional<>(r.getString("e"), ingress), bucket, bytes);
            if (includeOther) {
                selectedByBucket.computeIfAbsent(bucket, k -> new double[2])[ingress ? 0 : 1] += bytes;
            }
        }

        if (includeOther) {
            for (final GenericRecord r : client.queryAll(seriesSql(null, null, w, range[0], range[1], step))) {
                final boolean ingress = "ingress".equals(r.getString("d"));
                final long bucket = r.getLong("b");
                final double grand = r.getDouble("bytes");
                final double selected = selectedByBucket.getOrDefault(bucket, new double[2])[ingress ? 0 : 1];
                final double other = grand - selected;
                if (Math.abs(other) > 1e-9) {
                    table.put(new Directional<>(OTHER_APPLICATION, ingress), bucket, other);
                }
            }
        }
        return table;
    }

    /** Top-N application names by total bytes over the range (the entities the top-N series covers). */
    private Set<String> topNApplications(final int n, final String whereClause) {
        final String sql = "SELECT application AS e FROM " + table + " WHERE " + whereClause
                + " GROUP BY application ORDER BY sum(bytes) DESC, e LIMIT " + n;
        return client.queryAll(sql).stream()
                .map(r -> r.getString("e"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Build the proportional-series SQL for an arbitrary entity column (D-PROPORTION). When
     * {@code entityColumn} is null the result is grouped by direction/bucket only (the grand total
     * used for the "Other" residual); otherwise it is grouped by {@code toString(entityColumn)} /
     * direction / bucket. {@code entityFilter} (may be null/empty) is ANDed into the WHERE clause.
     */
    private String seriesSql(final String entityColumn, final String entityFilter, final String whereClause,
                             final long start, final long end, final long step) {
        final boolean byEntity = entityColumn != null;
        final String innerEntity = byEntity ? entityColumn + ", " : "";                 // raw column, innermost select
        final String midEntity = byEntity ? "toString(" + entityColumn + ") AS e, " : ""; // stringified in the middle
        final String entityGroup = byEntity ? "e, " : "";
        final String appFilter = (entityFilter != null && !entityFilter.isEmpty()) ? " AND " + entityFilter : "";
        return "WITH " + start + " AS q_start, " + end + " AS q_end, " + step + " AS q_step "
                + "SELECT " + entityGroup + "d, b, sum(share) AS bytes FROM ("
                + "  SELECT " + midEntity + "direction AS d, (q_start + i * q_step) AS b,"
                + "    if(dur = 0,"
                + "       if((q_start + i * q_step) <= fs AND fs < least(q_start + (i + 1) * q_step, q_end), toFloat64(bytes_), 0),"
                + "       toFloat64(bytes_) * greatest(0, least(ls, least(q_start + (i + 1) * q_step, q_end)) - greatest(fs, q_start + i * q_step)) / dur) AS share"
                + "  FROM ("
                + "    SELECT " + innerEntity + "direction, bytes AS bytes_,"
                + "      toUnixTimestamp64Milli(first_switched) AS fs, toUnixTimestamp64Milli(last_switched) AS ls,"
                + "      (toUnixTimestamp64Milli(last_switched) - toUnixTimestamp64Milli(first_switched)) AS dur,"
                + "      greatest(toUnixTimestamp64Milli(first_switched), q_start) AS lo,"
                + "      least(toUnixTimestamp64Milli(last_switched), q_end - 1) AS hi,"
                + "      if(hi >= lo, range(toUInt64(intDiv(lo - q_start, q_step)), toUInt64(intDiv(hi - q_start, q_step)) + 1), emptyArrayUInt64()) AS idxs"
                + "    FROM " + table + " WHERE " + whereClause + appFilter + " AND direction IN ('ingress', 'egress')"
                + "  ) ARRAY JOIN idxs AS i"
                + ") WHERE share > 0 GROUP BY " + entityGroup + "d, b ORDER BY " + entityGroup + "d, b";
    }

    private static long[] timeRange(final List<Filter> filters) {
        if (filters != null) {
            for (final Filter f : filters) {
                if (f instanceof TimeRangeFilter t) {
                    return new long[]{t.getStart(), t.getEnd()};
                }
            }
        }
        throw new IllegalArgumentException("A TimeRangeFilter is required for time series queries.");
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
        final String col = fieldColumn(field);
        final String sql = "SELECT DISTINCT toString(" + col + ") AS v FROM " + table
                + " WHERE " + where(filters) + " ORDER BY " + col;
        final List<String> values = client.queryAll(sql).stream()
                .map(r -> r.getString("v"))
                .collect(Collectors.toList());
        return CompletableFuture.completedFuture(values);
    }

    @Override
    public CompletableFuture<List<TrafficSummary<String>>> getFieldSummaries(final LimitedCardinalityField field,
                                                                             final List<Filter> filters) {
        final String col = fieldColumn(field);
        // A limited-cardinality field returns every value, so there is no top-N / "Other".
        final String sql = "SELECT toString(" + col + ") AS e,"
                + " sumIf(bytes, direction = 'ingress') AS bin,"
                + " sumIf(bytes, direction = 'egress') AS bout"
                + " FROM " + table + " WHERE " + where(filters) + " GROUP BY " + col + " ORDER BY " + col;
        return CompletableFuture.completedFuture(summariesFrom(client.queryAll(sql)));
    }

    @Override
    public CompletableFuture<Table<Directional<String>, Long, Double>> getFieldSeries(
            final LimitedCardinalityField field, final long step, final List<Filter> filters) {
        final String col = fieldColumn(field);
        final long[] range = timeRange(filters);
        final Table<Directional<String>, Long, Double> table = HashBasedTable.create();
        for (final GenericRecord r : client.queryAll(seriesSql(col, null, where(filters), range[0], range[1], step))) {
            table.put(new Directional<>(r.getString("e"), "ingress".equals(r.getString("d"))),
                      r.getLong("b"), r.getDouble("bytes"));
        }
        return CompletableFuture.completedFuture(table);
    }

    /** Map a {@link LimitedCardinalityField} to its ClickHouse column. */
    private static String fieldColumn(final LimitedCardinalityField field) {
        switch (field) {
            case DSCP:
                return "dscp";
            default:
                throw new IllegalArgumentException("Unsupported field: " + field);
        }
    }
}
