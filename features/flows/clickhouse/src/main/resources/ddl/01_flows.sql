-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Copyright 2026 The OpenNMS Group, Inc.
--
-- Created by Ronny Trommer <ronny@opennms.com>
--
-- Raw flow table (D-SCHEMA). Engine: MergeTree (D-DEDUP) — flows have no natural
-- unique key, so ReplacingMergeTree would collapse legitimately-distinct identical
-- flows. At-least-once duplicates from Path B are negligible for aggregate queries.
-- Retention is a per-table TTL (D-RETENTION), replacing the ES index strategy.

CREATE TABLE IF NOT EXISTS flows
(
    timestamp      DateTime64(3) CODEC(Delta, ZSTD),
    location       LowCardinality(String),
    application    LowCardinality(String),
    protocol       UInt8,
    src_addr       IPv6,
    src_port       UInt16,
    src_as         UInt32,
    src_mask_len   UInt8,
    dst_addr       IPv6,
    dst_port       UInt16,
    dst_as         UInt32,
    dst_mask_len   UInt8,
    direction      Enum8('unknown' = 0, 'ingress' = 1, 'egress' = 2),
    exporter_node  UInt32,
    input_snmp     UInt32,
    output_snmp    UInt32,
    bytes          UInt64,
    packets        UInt64,
    first_switched DateTime64(3),
    last_switched  DateTime64(3),
    dscp           UInt8,
    ecn            UInt8,
    tos            UInt8,
    vlan           UInt16,
    tcp_flags      UInt16,
    src_locality   Enum8('unknown' = 0, 'public' = 1, 'private' = 2),
    dst_locality   Enum8('unknown' = 0, 'public' = 1, 'private' = 2),
    flow_locality  Enum8('unknown' = 0, 'public' = 1, 'private' = 2),
    -- Hostnames and the canonical conversation key are denormalised onto each row at
    -- enrichment time (D-ENRICH); the query service reads them back rather than resolving
    -- via DAOs. src_hostname/dst_hostname pair with src_addr/dst_addr.
    src_hostname   String,
    dst_hostname   String,
    convo_key      String
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(timestamp)
ORDER BY (location, exporter_node, application, timestamp)
TTL toDateTime(timestamp) + INTERVAL __TTL_DAYS__ DAY;
