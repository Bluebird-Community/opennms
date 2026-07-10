/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.netmgt.flows.clickhouse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.locks.ReentrantLock;

import org.opennms.integration.api.v1.flows.Flow;
import org.opennms.integration.api.v1.flows.FlowException;
import org.opennms.integration.api.v1.flows.FlowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.data.ClickHouseFormat;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer.Context;

/**
 * Path A (Kafka-free) write path: buffers enriched flows and batch-inserts them into the ClickHouse
 * {@code flows} table via the client's JSONEachRow insert. Mirrors the shape of the former
 * {@code ElasticFlowRepository} (size- or timer-triggered flush, throughput metrics). Inserts are
 * synchronous, so a healthy backend gives at-least-once persistence; the buffer is capped
 * ({@link #setMaxBufferedFlows}) so a sustained backend outage drops the oldest buffered flows
 * (counted by the {@code dropped} meter) rather than growing the heap without bound.
 */
public class ClickhouseFlowRepository implements FlowRepository {

    private static final Logger LOG = LoggerFactory.getLogger(ClickhouseFlowRepository.class);

    private final Client client;
    private final String table;

    private final Meter flowsPersistedMeter;
    private final Meter droppedMeter;
    private final com.codahale.metrics.Timer logPersistingTimer;

    private int bulkSize = 1000;
    private int bulkFlushMs = 500;
    /** Hard cap on buffered rows so a persistently unavailable backend cannot grow the heap without bound. */
    private int maxBufferedFlows = 100_000;

    private final List<String> buffer = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile long lastPersist = 0;
    private volatile Timer flushTimer;

    public ClickhouseFlowRepository(final MetricRegistry metricRegistry,
                                    final Client client,
                                    final String table) {
        this.client = Objects.requireNonNull(client);
        this.table = Objects.requireNonNull(table);
        this.flowsPersistedMeter = metricRegistry.meter("flowsPersisted");
        this.droppedMeter = metricRegistry.meter("dropped");
        this.logPersistingTimer = metricRegistry.timer("logPersisting");
    }

    @Override
    public void persist(final Collection<? extends Flow> flows) throws FlowException {
        // Serialize outside the lock so producer threads don't contend on JSON building.
        final List<String> lines = new ArrayList<>(flows.size());
        for (final Flow flow : flows) {
            lines.add(FlowRowMapper.toJsonLine(flow));
        }
        lock.lock();
        try {
            buffer.addAll(lines);
            if (buffer.size() >= bulkSize) {
                try {
                    flushLocked();
                } catch (final FlowException e) {
                    // Bound the buffer so a persistently failing backend cannot grow the heap.
                    enforceCapLocked();
                    throw e;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** Drop the oldest buffered rows if the buffer exceeds its cap. Caller must hold {@link #lock}. */
    private void enforceCapLocked() {
        final int overflow = buffer.size() - maxBufferedFlows;
        if (overflow > 0) {
            buffer.subList(0, overflow).clear();
            droppedMeter.mark(overflow);
            LOG.warn("ClickHouse flow buffer exceeded {} rows; dropped {} oldest buffered flows "
                    + "(is the ClickHouse backend reachable?).", maxBufferedFlows, overflow);
        }
    }

    /** Flush the current buffer. Caller must hold {@link #lock}. */
    private void flushLocked() throws FlowException {
        if (buffer.isEmpty()) {
            return;
        }
        final String payload = String.join("\n", buffer);
        final int count = buffer.size();
        // The InsertResponse must be closed to release the connection for the next flush.
        try (final Context ctx = logPersistingTimer.time();
             final InsertResponse response = client.insert(table,
                     new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)),
                     ClickHouseFormat.JSONEachRow).get()) {
            flowsPersistedMeter.mark(count);
            buffer.clear();
            lastPersist = System.currentTimeMillis();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FlowException("Interrupted while persisting flows to ClickHouse.", e);
        } catch (final Exception e) {
            throw new FlowException("Failed to persist " + count + " flows to ClickHouse.", e);
        }
    }

    public void start() {
        stopTimer();
        flushTimer = new Timer("clickhouse-flow-flush", true);
        flushTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (System.currentTimeMillis() - lastPersist <= bulkFlushMs) {
                    return;
                }
                if (lock.tryLock()) {
                    try {
                        flushLocked();
                    } catch (final Throwable t) {
                        LOG.error("Error flushing buffered flows to ClickHouse.", t);
                        enforceCapLocked();
                    } finally {
                        lock.unlock();
                    }
                }
            }
        }, bulkFlushMs, bulkFlushMs);
    }

    public void stop() throws FlowException {
        stopTimer();
        lock.lock();
        try {
            flushLocked();
        } finally {
            lock.unlock();
        }
    }

    private void stopTimer() {
        if (flushTimer != null) {
            flushTimer.cancel();
            flushTimer = null;
        }
    }

    public int getBulkSize() {
        return bulkSize;
    }

    public void setBulkSize(final int bulkSize) {
        this.bulkSize = bulkSize;
    }

    public int getMaxBufferedFlows() {
        return maxBufferedFlows;
    }

    public void setMaxBufferedFlows(final int maxBufferedFlows) {
        this.maxBufferedFlows = maxBufferedFlows;
    }

    public int getBulkFlushMs() {
        return bulkFlushMs;
    }

    public void setBulkFlushMs(final int bulkFlushMs) {
        this.bulkFlushMs = bulkFlushMs;
        if (flushTimer != null) {
            start();
        }
    }
}
