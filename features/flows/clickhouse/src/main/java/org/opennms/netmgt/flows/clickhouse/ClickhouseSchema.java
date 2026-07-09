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

    /**
     * @return the ordered list of DDL statements that create the flow schema, comments and
     *         blank lines stripped, split on the statement terminator.
     */
    public static List<String> statements() {
        final List<String> statements = new ArrayList<>();
        for (final String resource : DDL_RESOURCES) {
            statements.addAll(splitStatements(readResource(resource)));
        }
        return statements;
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
