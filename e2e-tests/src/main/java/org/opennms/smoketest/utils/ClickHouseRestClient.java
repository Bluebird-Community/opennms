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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.opennms.smoketest.containers.ClickHouseContainer;

/**
 * Minimal raw-HTTP client for talking to ClickHouse over its HTTP interface.
 * <p>
 * ClickHouse accepts SQL as the request body of an HTTP POST to {@code http://host:port/} and
 * returns the result (TSV) as the response body. Requests authenticate over HTTP Basic auth with
 * the same credentials the {@link ClickHouseContainer} is provisioned with; otherwise ClickHouse
 * rejects the query with HTTP 403.
 * <p>
 * Intentionally uses only the JDK's {@link HttpClient}, no additional client library.
 */
public class ClickHouseRestClient {

    private final String baseUrl;
    private final String basicAuth = "Basic " + Base64.getEncoder().encodeToString(
            (ClickHouseContainer.USERNAME + ":" + ClickHouseContainer.PASSWORD).getBytes(StandardCharsets.UTF_8));
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
                .header("Authorization", basicAuth)
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
