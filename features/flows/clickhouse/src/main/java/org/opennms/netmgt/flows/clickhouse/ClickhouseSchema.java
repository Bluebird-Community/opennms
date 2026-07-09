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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Loads the ClickHouse schema DDL bundled on the classpath and exposes it as an ordered list of
 * {@link Migration}s. Each DDL resource is one migration, versioned by its position; the bootstrap
 * records applied versions in a ledger table and only runs migrations it has not seen, so future
 * schema changes ship as new resources rather than being silently skipped (design D-BOOTSTRAP).
 *
 * <p>This class has no dependency on the ClickHouse client: it only reads, splits and templates the
 * DDL. {@code ClickhouseSchemaBootstrap} executes it against a server.
 */
public final class ClickhouseSchema {

    /** DDL resources, applied in order; index+1 is the migration version. */
    static final List<String> DDL_RESOURCES = List.of(
            "/ddl/01_flows.sql",
            "/ddl/02_rollups.sql",
            "/ddl/03_hostnames.sql");

    /** Placeholder in the DDL for the retention window; substituted from {@code ttlDays}. */
    private static final String TTL_PLACEHOLDER = "__TTL_DAYS__";

    /** Matches the whole {@code TTL … INTERVAL __TTL_DAYS__ DAY} clause so it can be dropped. */
    private static final Pattern TTL_CLAUSE =
            Pattern.compile("\\s*TTL\\b[^;]*INTERVAL " + TTL_PLACEHOLDER + " DAY", Pattern.CASE_INSENSITIVE);

    /** One ordered schema migration: a set of statements applied atomically-ish and recorded by version. */
    public record Migration(int version, String name, List<String> statements) {
    }

    private ClickhouseSchema() {
    }

    /**
     * @param ttlDays retention window in days; {@code <= 0} removes the {@code TTL} clause entirely
     *                (used by integration tests whose fixtures use ancient timestamps).
     * @return the ordered migrations that create the flow schema, with the retention TTL applied.
     */
    public static List<Migration> migrations(final int ttlDays) {
        final List<Migration> migrations = new ArrayList<>();
        for (int i = 0; i < DDL_RESOURCES.size(); i++) {
            final String resource = DDL_RESOURCES.get(i);
            final List<String> statements = splitStatements(readResource(resource)).stream()
                    .map(s -> applyTtl(s, ttlDays))
                    .collect(Collectors.toList());
            migrations.add(new Migration(i + 1, resource, statements));
        }
        return migrations;
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
