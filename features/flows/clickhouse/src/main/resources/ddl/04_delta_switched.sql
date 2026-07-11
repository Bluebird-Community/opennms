-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Copyright 2026 The OpenNMS Group, Inc.
--
-- Created by Ronny Trommer <ronny@opennms.com>
--
-- Migration v4: clock-skew-corrected effective flow start (D-PROPORTION). Fresh installs already
-- get this from 01_flows.sql; this migration adds it to deployments created before the column
-- existed, defaulting it to first_switched (they are identical absent clock skew). IF NOT EXISTS
-- keeps it idempotent.

ALTER TABLE flows ADD COLUMN IF NOT EXISTS delta_switched DateTime64(3) DEFAULT first_switched AFTER first_switched;
