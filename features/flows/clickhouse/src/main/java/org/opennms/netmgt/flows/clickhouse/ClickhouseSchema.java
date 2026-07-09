/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.netmgt.flows.clickhouse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Loads the ClickHouse schema DDL bundled on the classpath and exposes it as an
 * ordered list of executable statements.
 *
 * <p>This is the single source of truth for the flow schema (raw {@code flows} table plus
 * the per-minute rollup tables and their materialized views) used by both write paths;
 * see design decision D-BOOTSTRAP. All statements are written {@code IF NOT EXISTS} so a
 * bootstrap that runs them is idempotent and safe to re-run on reconnect.
 *
 * <p>This class deliberately has no dependency on the ClickHouse client: it only reads and
 * splits the DDL. The component that executes the statements against a server is
 * {@code ClickhouseSchemaBootstrap} (task 1.5).
 */
public final class ClickhouseSchema {

    /** DDL resources, applied in order. */
    static final List<String> DDL_RESOURCES = List.of(
            "/ddl/01_flows.sql",
            "/ddl/02_rollups.sql");

    private ClickhouseSchema() {
    }

    /** Placeholder in the DDL for the retention window; substituted from {@code ttlDays}. */
    private static final String TTL_PLACEHOLDER = "__TTL_DAYS__";

    /** Matches the whole {@code TTL … INTERVAL __TTL_DAYS__ DAY} clause so it can be dropped. */
    private static final java.util.regex.Pattern TTL_CLAUSE =
            java.util.regex.Pattern.compile("\\s*TTL\\b[^;]*INTERVAL " + TTL_PLACEHOLDER + " DAY",
                                            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * @param ttlDays retention window in days; {@code <= 0} removes the {@code TTL} clause entirely
     *                (used by integration tests whose fixtures use ancient timestamps).
     * @return the ordered list of DDL statements that create the flow schema, comments and blank
     *         lines stripped, split on the statement terminator, with the retention TTL applied.
     */
    public static List<String> statements(final int ttlDays) {
        final List<String> statements = new ArrayList<>();
        for (final String resource : DDL_RESOURCES) {
            for (final String statement : splitStatements(readResource(resource))) {
                statements.add(applyTtl(statement, ttlDays));
            }
        }
        return statements;
    }

    private static String applyTtl(final String statement, final int ttlDays) {
        if (ttlDays > 0) {
            return statement.replace(TTL_PLACEHOLDER, Integer.toString(ttlDays));
        }
        // No retention: strip the TTL clause so ancient-timestamp fixtures survive.
        return TTL_CLAUSE.matcher(statement).replaceAll("");
    }

    private static String readResource(final String resource) {
        try (final InputStream in = ClickhouseSchema.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled DDL resource: " + resource);
            }
            try (final BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines()
                        // drop SQL line comments so the terminator split is not confused by ';' in prose
                        .filter(line -> !line.trim().startsWith("--"))
                        .collect(Collectors.joining("\n"));
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read DDL resource: " + resource, e);
        }
    }

    private static List<String> splitStatements(final String sql) {
        return Arrays.stream(sql.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
