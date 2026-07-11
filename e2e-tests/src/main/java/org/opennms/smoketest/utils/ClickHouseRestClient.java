/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.smoketest.utils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal raw-HTTP client for talking to ClickHouse over its HTTP interface.
 * <p>
 * ClickHouse accepts SQL as the request body of an HTTP POST to {@code http://host:port/} and
 * returns the result (TSV) as the response body. Authentication uses the {@code default} user
 * with no password.
 * <p>
 * Intentionally uses only the JDK's {@link HttpClient}, no additional client library.
 */
public class ClickHouseRestClient {

    private final String baseUrl;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public ClickHouseRestClient(final InetSocketAddress address) {
        this.baseUrl = String.format("http://%s:%d", address.getHostString(), address.getPort());
    }

    /**
     * Execute the given SQL and parse the trimmed response body as a {@code long}.
     */
    public long count(final String sql) {
        return Long.parseLong(query(sql).trim());
    }

    /**
     * Execute the given SQL, ignoring the response body.
     */
    public void execute(final String sql) {
        query(sql);
    }

    private String query(final String sql) {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/"))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(sql))
                .build();
        try {
            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("ClickHouse query failed with HTTP " + response.statusCode()
                        + " for SQL [" + sql + "]: " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
