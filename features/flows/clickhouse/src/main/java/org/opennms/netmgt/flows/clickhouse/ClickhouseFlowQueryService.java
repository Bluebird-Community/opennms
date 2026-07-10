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
import org.opennms.netmgt.flows.api.ConversationKey;
import org.opennms.netmgt.flows.api.Directional;
import org.opennms.netmgt.flows.api.FlowQueryService;
import org.opennms.netmgt.flows.api.Host;
import org.opennms.netmgt.flows.api.LimitedCardinalityField;
import org.opennms.netmgt.flows.api.TrafficSummary;
import org.opennms.netmgt.flows.filter.api.ExporterNodeFilter;
import org.opennms.netmgt.flows.filter.api.Filter;
import org.opennms.netmgt.flows.filter.api.TimeRangeFilter;
import org.opennms.netmgt.flows.processing.ConversationKeyUtils;

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

    // Rollup routing (D-QUERY). Defaults mirror the ES SmartQueryService default (alwaysUseRaw=true):
    // queries use the exact raw table; the rollup path is opt-in. Rollups back SUMMARIES only — their
    // point-in-time bucketing does not reproduce the proportional series — and only when the filters
    // reference columns the rollup carries (time range + exporter node).
    // NOTE: currently wired for the APPLICATION summaries only; host/conversation/field summaries and
    // all series stay on the raw path regardless of these flags (host/conversation rollup summaries
    // are a follow-on). The flags/threshold therefore affect application summaries today.
    private String appRollupTable = "flows_by_app_1m";
    private boolean alwaysUseRawForQueries = true;
    private boolean alwaysUseAggForQueries = false;
    private long aggregateThresholdMs = 120_000L;

    public ClickhouseFlowQueryService(final Client client, final String table) {
        this.client = Objects.requireNonNull(client);
        this.table = Objects.requireNonNull(table);
    }

    public void setAppRollupTable(final String appRollupTable) {
        this.appRollupTable = appRollupTable;
    }

    public void setAlwaysUseRawForQueries(final boolean alwaysUseRawForQueries) {
        this.alwaysUseRawForQueries = alwaysUseRawForQueries;
    }

    public void setAlwaysUseAggForQueries(final boolean alwaysUseAggForQueries) {
        this.alwaysUseAggForQueries = alwaysUseAggForQueries;
    }

    public void setAggregateThresholdMs(final long aggregateThresholdMs) {
        this.aggregateThresholdMs = aggregateThresholdMs;
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
        final String[] target = appTarget(filters);
        final String from = target[0];
        final String w = target[1];
        final String sql = appSummarySelect(from, w) + " GROUP BY application ORDER BY (bin + bout) DESC, e LIMIT " + n;
        final List<TrafficSummary<String>> summaries = summariesFrom(client.queryAll(sql));
        if (includeOther) {
            appendOther(summaries, from, w);
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
        final String[] target = appTarget(filters);
        final String from = target[0];
        final String w = target[1];
        final String sql = appSummarySelect(from, w) + " AND application IN (" + quoteAll(applications) + ")"
                + " GROUP BY application ORDER BY e";
        final List<TrafficSummary<String>> summaries = summariesFrom(client.queryAll(sql));
        if (includeOther) {
            appendOther(summaries, from, w);
        }
        return CompletableFuture.completedFuture(summaries);
    }

    /** Returns {fromTable, whereClause} for an application summary, routed to the rollup when enabled. */
    private String[] appTarget(final List<Filter> filters) {
        return useAppRollup(filters)
                ? new String[]{appRollupTable, rollupWhere(filters)}
                : new String[]{table, where(filters)};
    }

    private static String appSummarySelect(final String from, final String whereClause) {
        return "SELECT application AS e,"
                + " sumIf(bytes, direction = 'ingress') AS bin,"
                + " sumIf(bytes, direction = 'egress') AS bout"
                + " FROM " + from + " WHERE " + whereClause;
    }

    /**
     * Choose the per-minute application rollup over the raw table when routing is enabled and the
     * filters only reference columns the rollup carries (time range + exporter node). Summaries only
     * (D-QUERY); series always use the raw proportional path.
     */
    private boolean useAppRollup(final List<Filter> filters) {
        if (alwaysUseRawForQueries || !rollupCompatible(filters)) {
            return false;
        }
        if (alwaysUseAggForQueries) {
            return true;
        }
        if (filters != null) {
            for (final Filter f : filters) {
                if (f instanceof TimeRangeFilter t && t.getDurationMs() > aggregateThresholdMs) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The rollup tables carry only time (bucket) + exporter_node, so any other filter forces raw. */
    static boolean rollupCompatible(final List<Filter> filters) {
        if (filters == null) {
            return true;
        }
        for (final Filter f : filters) {
            if (!(f instanceof TimeRangeFilter) && !(f instanceof ExporterNodeFilter)) {
                return false;
            }
        }
        return true;
    }

    /** WHERE fragment against the rollup columns: bucket range + exporter_node. */
    static String rollupWhere(final List<Filter> filters) {
        if (filters == null || filters.isEmpty()) {
            return "1 = 1";
        }
        final List<String> parts = new ArrayList<>();
        for (final Filter f : filters) {
            if (f instanceof TimeRangeFilter t) {
                // Round the lower bound down to the minute bucket that CONTAINS the range start, so
                // the first (partial) bucket is included rather than dropped; milli precision matches
                // the raw path. Point-in-time rollups are inherently minute-granular at the edges.
                parts.add("(bucket >= toStartOfMinute(fromUnixTimestamp64Milli(" + t.getStart()
                        + ")) AND bucket < fromUnixTimestamp64Milli(" + t.getEnd() + "))");
            } else if (f instanceof ExporterNodeFilter en) {
                final Integer nodeId = en.getCriteria().getNodeId();
                if (nodeId == null) {
                    throw new UnsupportedOperationException(
                            "ExporterNodeFilter with foreignSource:foreignId is not supported on the rollup path.");
                }
                parts.add("exporter_node = " + nodeId);
            }
        }
        return parts.isEmpty() ? "1 = 1" : String.join(" AND ", parts);
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
    private void appendOther(final List<TrafficSummary<String>> summaries, final String from, final String whereClause) {
        final List<GenericRecord> grand = client.queryAll(
                "SELECT sumIf(bytes, direction = 'ingress') AS bin, sumIf(bytes, direction = 'egress') AS bout"
                        + " FROM " + from + " WHERE " + whereClause);
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
        for (final GenericRecord r : client.queryAll(seriesSql(this.table, "application", appFilter, w, range[0], range[1], step))) {
            final boolean ingress = "ingress".equals(r.getString("d"));
            final long bucket = r.getLong("b");
            final double bytes = r.getDouble("bytes");
            table.put(new Directional<>(r.getString("e"), ingress), bucket, bytes);
            if (includeOther) {
                selectedByBucket.computeIfAbsent(bucket, k -> new double[2])[ingress ? 0 : 1] += bytes;
            }
        }

        if (includeOther) {
            for (final GenericRecord r : client.queryAll(seriesSql(this.table, null, null, w, range[0], range[1], step))) {
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
    private String seriesSql(final String fromExpr, final String entityColumn, final String entityFilter,
                             final String whereClause, final long start, final long end, final long step) {
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
                + "    FROM " + fromExpr + " WHERE " + whereClause + appFilter + " AND direction IN ('ingress', 'egress')"
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
        // Match against the stored convo_key JSON: ["location",protocol,"lowerIp","upperIp",app|null]
        String appPat = applicationPattern;
        if (".*".equals(appPat)) {
            appPat = "(\"" + appPat + "\"|null)";
        } else if (!"null".equals(appPat)) {
            appPat = "\"" + appPat + "\"";
        }
        final String regex = "\\[\"" + locationPattern + "\"," + protocolPattern + ",\"" + lowerIPPattern
                + "\",\"" + upperIPPattern + "\"," + appPat + "\\]";
        final String sql = "SELECT DISTINCT convo_key AS e FROM " + table + " WHERE " + where(filters)
                + " AND convo_key != '' AND match(convo_key, " + quote(regex) + ") ORDER BY e LIMIT " + limit;
        final List<String> keys = client.queryAll(sql).stream().map(r -> r.getString("e")).collect(Collectors.toList());
        return CompletableFuture.completedFuture(keys);
    }

    @Override
    public CompletableFuture<List<TrafficSummary<Conversation>>> getTopNConversationSummaries(
            final int n, final boolean includeOther, final List<Filter> filters) {
        final String w = where(filters);
        final String sql = convoSummarySelect(w) + " GROUP BY e ORDER BY (bin + bout) DESC, e LIMIT " + n;
        final List<TrafficSummary<Conversation>> summaries = convoSummariesFrom(client.queryAll(sql));
        if (includeOther) {
            appendOtherConvo(summaries, w);
        }
        return CompletableFuture.completedFuture(summaries);
    }

    @Override
    public CompletableFuture<List<TrafficSummary<Conversation>>> getConversationSummaries(
            final Set<String> conversations, final boolean includeOther, final List<Filter> filters) {
        if (conversations.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
        final String w = where(filters);
        final String sql = convoSummarySelect(w) + " WHERE e IN (" + quoteAll(conversations) + ") GROUP BY e ORDER BY e";
        final List<TrafficSummary<Conversation>> summaries = convoSummariesFrom(client.queryAll(sql));
        if (includeOther) {
            appendOtherConvo(summaries, w);
        }
        return CompletableFuture.completedFuture(summaries);
    }

    @Override
    public CompletableFuture<Table<Directional<Conversation>, Long, Double>> getConversationSeries(
            final Set<String> conversations, final long step, final boolean includeOther, final List<Filter> filters) {
        return CompletableFuture.completedFuture(convoSeries(conversations, step, includeOther, filters));
    }

    @Override
    public CompletableFuture<Table<Directional<Conversation>, Long, Double>> getTopNConversationSeries(
            final int n, final long step, final boolean includeOther, final List<Filter> filters) {
        return CompletableFuture.completedFuture(convoSeries(topNConversations(n, where(filters)), step, includeOther, filters));
    }

    // --- conversation helpers ---------------------------------------------

    /** Projects each flow to its conversation key plus the lower/upper endpoint hostname. */
    private String convoProjection(final String whereClause) {
        return "SELECT convo_key AS e,"
                + " if(src_addr <= dst_addr, src_hostname, dst_hostname) AS lo_hn,"
                + " if(src_addr <= dst_addr, dst_hostname, src_hostname) AS up_hn,"
                + " direction, bytes"
                + " FROM " + table + " WHERE " + whereClause + " AND convo_key != ''";
    }

    private String convoSummarySelect(final String whereClause) {
        return "SELECT e, anyIf(lo_hn, lo_hn != '') AS lower_hn, anyIf(up_hn, up_hn != '') AS upper_hn,"
                + " sumIf(bytes, direction = 'ingress') AS bin, sumIf(bytes, direction = 'egress') AS bout"
                + " FROM (" + convoProjection(whereClause) + ")";
    }

    private static List<TrafficSummary<Conversation>> convoSummariesFrom(final List<GenericRecord> rows) {
        final List<TrafficSummary<Conversation>> out = new ArrayList<>(rows.size());
        for (final GenericRecord r : rows) {
            final Conversation c = conversation(r.getString("e"), r.getString("lower_hn"), r.getString("upper_hn"));
            if (c != null) {
                out.add(TrafficSummary.from(c).withBytes(r.getLong("bin"), r.getLong("bout")).build());
            }
        }
        return out;
    }

    private void appendOtherConvo(final List<TrafficSummary<Conversation>> summaries, final String whereClause) {
        final List<GenericRecord> grand = client.queryAll(
                "SELECT sumIf(bytes, direction = 'ingress') AS bin, sumIf(bytes, direction = 'egress') AS bout FROM "
                        + table + " WHERE " + whereClause + " AND convo_key != ''");
        final long grandIn = grand.isEmpty() ? 0L : grand.get(0).getLong("bin");
        final long grandOut = grand.isEmpty() ? 0L : grand.get(0).getLong("bout");
        final long otherIn = grandIn - summaries.stream().mapToLong(TrafficSummary::getBytesIn).sum();
        final long otherOut = grandOut - summaries.stream().mapToLong(TrafficSummary::getBytesOut).sum();
        if (otherIn > 0 || otherOut > 0) {
            summaries.add(TrafficSummary.from(Conversation.forOther().build()).withBytes(otherIn, otherOut).build());
        }
    }

    private Set<String> topNConversations(final int n, final String whereClause) {
        final String sql = "SELECT convo_key AS e FROM " + table + " WHERE " + whereClause
                + " AND convo_key != '' GROUP BY convo_key ORDER BY sum(bytes) DESC, e LIMIT " + n;
        return client.queryAll(sql).stream().map(r -> r.getString("e"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Table<Directional<Conversation>, Long, Double> convoSeries(final Set<String> conversations, final long step,
                                                                       final boolean includeOther, final List<Filter> filters) {
        final Table<Directional<Conversation>, Long, Double> table = HashBasedTable.create();
        if (conversations.isEmpty()) {
            return table;
        }
        final long[] range = timeRange(filters);
        final String w = where(filters);
        final Map<String, String[]> hostnames = resolveConvoHostnames(conversations, w);
        final Map<Long, double[]> selectedByBucket = includeOther ? new HashMap<>() : null;
        final String filter = "convo_key IN (" + quoteAll(conversations) + ")";
        for (final GenericRecord r : client.queryAll(seriesSql(this.table, "convo_key", filter, w, range[0], range[1], step))) {
            final String key = r.getString("e");
            final String[] hn = hostnames.get(key);
            final Conversation c = conversation(key, hn != null ? hn[0] : null, hn != null ? hn[1] : null);
            if (c == null) {
                continue;
            }
            final boolean ingress = "ingress".equals(r.getString("d"));
            final long bucket = r.getLong("b");
            final double bytes = r.getDouble("bytes");
            table.put(new Directional<>(c, ingress), bucket, bytes);
            if (includeOther) {
                selectedByBucket.computeIfAbsent(bucket, k -> new double[2])[ingress ? 0 : 1] += bytes;
            }
        }
        if (includeOther) {
            for (final GenericRecord r : client.queryAll(
                    seriesSql(this.table, null, null, w + " AND convo_key != ''", range[0], range[1], step))) {
                final boolean ingress = "ingress".equals(r.getString("d"));
                final long bucket = r.getLong("b");
                final double other = r.getDouble("bytes")
                        - selectedByBucket.getOrDefault(bucket, new double[2])[ingress ? 0 : 1];
                if (Math.abs(other) > 1e-9) {
                    table.put(new Directional<>(Conversation.forOther().build(), ingress), bucket, other);
                }
            }
        }
        return table;
    }

    private Map<String, String[]> resolveConvoHostnames(final Set<String> conversations, final String whereClause) {
        final Map<String, String[]> map = new HashMap<>();
        final String sql = "SELECT e, anyIf(lo_hn, lo_hn != '') AS lower_hn, anyIf(up_hn, up_hn != '') AS upper_hn FROM ("
                + convoProjection(whereClause) + ") WHERE e IN (" + quoteAll(conversations) + ") GROUP BY e";
        for (final GenericRecord r : client.queryAll(sql)) {
            map.put(r.getString("e"), new String[]{r.getString("lower_hn"), r.getString("upper_hn")});
        }
        return map;
    }

    /** Build a {@link Conversation} from its stored key JSON, attaching lower/upper hostnames. */
    private static Conversation conversation(final String convoKey, final String lowerHn, final String upperHn) {
        final ConversationKey key;
        try {
            key = ConversationKeyUtils.fromJsonString(convoKey);
        } catch (final RuntimeException e) {
            return null; // malformed key -> skip rather than fail the whole result
        }
        final Conversation.Builder b = Conversation.from(key);
        if (lowerHn != null && !lowerHn.isEmpty()) {
            b.withLowerHostname(lowerHn);
        }
        if (upperHn != null && !upperHn.isEmpty()) {
            b.withUpperHostname(upperHn);
        }
        return b.build();
    }

    @Override
    public CompletableFuture<List<String>> getHosts(final String regex, final long limit, final List<Filter> filters) {
        final String sql = "SELECT DISTINCT toString(host) AS e FROM " + hostUnion(where(filters))
                + " WHERE match(toString(host), " + quote(regex) + ") ORDER BY e LIMIT " + limit;
        final List<String> hosts = client.queryAll(sql).stream()
                .map(r -> r.getString("e")).collect(Collectors.toList());
        return CompletableFuture.completedFuture(hosts);
    }

    @Override
    public CompletableFuture<List<TrafficSummary<Host>>> getTopNHostSummaries(
            final int n, final boolean includeOther, final List<Filter> filters) {
        final String w = where(filters);
        final String sql = hostSummarySelect(w) + " GROUP BY host ORDER BY (bin + bout) DESC, e LIMIT " + n;
        final List<TrafficSummary<Host>> summaries = hostSummariesFrom(client.queryAll(sql));
        if (includeOther) {
            appendOtherHost(summaries, w);
        }
        return CompletableFuture.completedFuture(summaries);
    }

    @Override
    public CompletableFuture<List<TrafficSummary<Host>>> getHostSummaries(
            final Set<String> hosts, final boolean includeOther, final List<Filter> filters) {
        if (hosts.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
        final String w = where(filters);
        final String sql = hostSummarySelect(w) + " WHERE toString(host) IN (" + quoteAll(hosts) + ")"
                + " GROUP BY host ORDER BY e";
        final List<TrafficSummary<Host>> summaries = hostSummariesFrom(client.queryAll(sql));
        if (includeOther) {
            appendOtherHost(summaries, w);
        }
        return CompletableFuture.completedFuture(summaries);
    }

    @Override
    public CompletableFuture<Table<Directional<Host>, Long, Double>> getHostSeries(
            final Set<String> hosts, final long step, final boolean includeOther, final List<Filter> filters) {
        return CompletableFuture.completedFuture(hostSeries(hosts, step, includeOther, filters));
    }

    @Override
    public CompletableFuture<Table<Directional<Host>, Long, Double>> getTopNHostSeries(
            final int n, final long step, final boolean includeOther, final List<Filter> filters) {
        return CompletableFuture.completedFuture(hostSeries(topNHosts(n, where(filters)), step, includeOther, filters));
    }

    // --- host helpers -----------------------------------------------------

    /** src+dst endpoints unioned so each flow contributes to both its source and destination host. */
    private String hostUnion(final String whereClause) {
        return "(SELECT src_addr AS host, src_hostname AS host_hostname, direction, bytes, first_switched, last_switched"
                + " FROM " + table + " WHERE " + whereClause
                + " UNION ALL SELECT dst_addr AS host, dst_hostname AS host_hostname, direction, bytes, first_switched, last_switched"
                + " FROM " + table + " WHERE " + whereClause + ")";
    }

    private String hostSummarySelect(final String whereClause) {
        return "SELECT toString(host) AS e, anyIf(host_hostname, host_hostname != '') AS hn,"
                + " sumIf(bytes, direction = 'ingress') AS bin, sumIf(bytes, direction = 'egress') AS bout"
                + " FROM " + hostUnion(whereClause);
    }

    private static List<TrafficSummary<Host>> hostSummariesFrom(final List<GenericRecord> rows) {
        final List<TrafficSummary<Host>> out = new ArrayList<>(rows.size());
        for (final GenericRecord r : rows) {
            out.add(TrafficSummary.from(host(r.getString("e"), r.getString("hn")))
                    .withBytes(r.getLong("bin"), r.getLong("bout")).build());
        }
        return out;
    }

    private void appendOtherHost(final List<TrafficSummary<Host>> summaries, final String whereClause) {
        final List<GenericRecord> grand = client.queryAll(
                "SELECT sumIf(bytes, direction = 'ingress') AS bin, sumIf(bytes, direction = 'egress') AS bout FROM "
                        + hostUnion(whereClause));
        final long grandIn = grand.isEmpty() ? 0L : grand.get(0).getLong("bin");
        final long grandOut = grand.isEmpty() ? 0L : grand.get(0).getLong("bout");
        final long otherIn = grandIn - summaries.stream().mapToLong(TrafficSummary::getBytesIn).sum();
        final long otherOut = grandOut - summaries.stream().mapToLong(TrafficSummary::getBytesOut).sum();
        if (otherIn > 0 || otherOut > 0) {
            summaries.add(TrafficSummary.from(Host.forOther().build()).withBytes(otherIn, otherOut).build());
        }
    }

    private Set<String> topNHosts(final int n, final String whereClause) {
        final String sql = "SELECT toString(host) AS e FROM " + hostUnion(whereClause)
                + " GROUP BY host ORDER BY sum(bytes) DESC, e LIMIT " + n;
        return client.queryAll(sql).stream().map(r -> r.getString("e"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Table<Directional<Host>, Long, Double> hostSeries(final Set<String> hosts, final long step,
                                                              final boolean includeOther, final List<Filter> filters) {
        final Table<Directional<Host>, Long, Double> table = HashBasedTable.create();
        if (hosts.isEmpty()) {
            return table;
        }
        final long[] range = timeRange(filters);
        final String w = where(filters);
        final Map<String, String> hostnames = resolveHostHostnames(hosts, w);
        final Map<Long, double[]> selectedByBucket = includeOther ? new HashMap<>() : null;
        final String filter = "toString(host) IN (" + quoteAll(hosts) + ")";
        for (final GenericRecord r : client.queryAll(seriesSql(hostUnion(w), "host", filter, "1 = 1", range[0], range[1], step))) {
            final boolean ingress = "ingress".equals(r.getString("d"));
            final long bucket = r.getLong("b");
            final double bytes = r.getDouble("bytes");
            table.put(new Directional<>(host(r.getString("e"), hostnames.get(r.getString("e"))), ingress), bucket, bytes);
            if (includeOther) {
                selectedByBucket.computeIfAbsent(bucket, k -> new double[2])[ingress ? 0 : 1] += bytes;
            }
        }
        if (includeOther) {
            for (final GenericRecord r : client.queryAll(seriesSql(hostUnion(w), null, null, "1 = 1", range[0], range[1], step))) {
                final boolean ingress = "ingress".equals(r.getString("d"));
                final long bucket = r.getLong("b");
                final double other = r.getDouble("bytes")
                        - selectedByBucket.getOrDefault(bucket, new double[2])[ingress ? 0 : 1];
                if (Math.abs(other) > 1e-9) {
                    table.put(new Directional<>(Host.forOther().build(), ingress), bucket, other);
                }
            }
        }
        return table;
    }

    private Map<String, String> resolveHostHostnames(final Set<String> hosts, final String whereClause) {
        final Map<String, String> map = new HashMap<>();
        final String sql = "SELECT toString(host) AS e, anyIf(host_hostname, host_hostname != '') AS hn FROM "
                + hostUnion(whereClause) + " WHERE toString(host) IN (" + quoteAll(hosts) + ") GROUP BY host";
        for (final GenericRecord r : client.queryAll(sql)) {
            final String hn = r.getString("hn");
            if (hn != null && !hn.isEmpty()) {
                map.put(r.getString("e"), hn);
            }
        }
        return map;
    }

    private static Host host(final String ip, final String hostname) {
        return (hostname != null && !hostname.isEmpty()) ? new Host(ip, hostname) : new Host(ip);
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
        for (final GenericRecord r : client.queryAll(seriesSql(this.table, col, null, where(filters), range[0], range[1], step))) {
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
