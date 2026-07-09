-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Copyright 2026 The OpenNMS Group, Inc.
--
-- Created by Ronny Trommer <ronny@opennms.com>
--
-- Per-minute rollups (D-SCHEMA), maintained in-database by materialized views on
-- every insert to `flows` — the replacement for the external Nephron/Flink job.
-- The application rollup follows the design exemplar exactly; the host and
-- conversation rollups are provisional and MUST be aligned with the query methods
-- in phase 3 (and validated against the proportional-sum oracle, spike 0.2).

-- Application ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flows_by_app_1m
(
    bucket        DateTime,
    location      LowCardinality(String),
    exporter_node UInt32,
    application   LowCardinality(String),
    direction     Enum8('unknown' = 0, 'ingress' = 1, 'egress' = 2),
    bytes         SimpleAggregateFunction(sum, UInt64),
    packets       SimpleAggregateFunction(sum, UInt64)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMMDD(bucket)
ORDER BY (location, exporter_node, application, direction, bucket)
TTL bucket + INTERVAL __TTL_DAYS__ DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_flows_by_app TO flows_by_app_1m AS
SELECT
    toStartOfMinute(timestamp) AS bucket,
    location,
    exporter_node,
    application,
    direction,
    sum(bytes)   AS bytes,
    sum(packets) AS packets
FROM flows
GROUP BY bucket, location, exporter_node, application, direction;

-- Host (provisional) --------------------------------------------------------
-- A flow contributes to two hosts (source and destination); the MV fans each
-- flow into two rows via ARRAY JOIN.
CREATE TABLE IF NOT EXISTS flows_by_host_1m
(
    bucket        DateTime,
    location      LowCardinality(String),
    exporter_node UInt32,
    host_addr     IPv6,
    direction     Enum8('unknown' = 0, 'ingress' = 1, 'egress' = 2),
    bytes         SimpleAggregateFunction(sum, UInt64),
    packets       SimpleAggregateFunction(sum, UInt64)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMMDD(bucket)
ORDER BY (location, exporter_node, host_addr, direction, bucket)
TTL bucket + INTERVAL __TTL_DAYS__ DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_flows_by_host TO flows_by_host_1m AS
SELECT
    toStartOfMinute(timestamp) AS bucket,
    location,
    exporter_node,
    host_addr,
    direction,
    sum(bytes)   AS bytes,
    sum(packets) AS packets
FROM flows
ARRAY JOIN [src_addr, dst_addr] AS host_addr
GROUP BY bucket, location, exporter_node, host_addr, direction;

-- Conversation (provisional) ------------------------------------------------
-- Conversation identity is (protocol, lower_ip, upper_ip, application); the
-- endpoints are normalised so both directions map to one conversation.
CREATE TABLE IF NOT EXISTS flows_by_conversation_1m
(
    bucket        DateTime,
    location      LowCardinality(String),
    exporter_node UInt32,
    protocol      UInt8,
    lower_ip      IPv6,
    upper_ip      IPv6,
    application   LowCardinality(String),
    direction     Enum8('unknown' = 0, 'ingress' = 1, 'egress' = 2),
    bytes         SimpleAggregateFunction(sum, UInt64),
    packets       SimpleAggregateFunction(sum, UInt64)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMMDD(bucket)
ORDER BY (location, exporter_node, protocol, lower_ip, upper_ip, application, direction, bucket)
TTL bucket + INTERVAL __TTL_DAYS__ DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_flows_by_conversation TO flows_by_conversation_1m AS
SELECT
    toStartOfMinute(timestamp) AS bucket,
    location,
    exporter_node,
    protocol,
    least(src_addr, dst_addr)    AS lower_ip,
    greatest(src_addr, dst_addr) AS upper_ip,
    application,
    direction,
    sum(bytes)   AS bytes,
    sum(packets) AS packets
FROM flows
GROUP BY bucket, location, exporter_node, protocol, lower_ip, upper_ip, application, direction;
