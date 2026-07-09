-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Copyright 2026 The OpenNMS Group, Inc.
--
-- Created by Ronny Trommer <ronny@opennms.com>
--
-- Migration v3: denormalised hostname + conversation-key columns on the raw flows table
-- (D-ENRICH). Fresh installs already get these from 01_flows.sql; this migration adds them
-- to deployments created before the columns existed. IF NOT EXISTS keeps it idempotent.

ALTER TABLE flows ADD COLUMN IF NOT EXISTS src_hostname String;
ALTER TABLE flows ADD COLUMN IF NOT EXISTS dst_hostname String;
ALTER TABLE flows ADD COLUMN IF NOT EXISTS convo_key String;
