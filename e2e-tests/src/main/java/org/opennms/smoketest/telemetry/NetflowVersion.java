/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.smoketest.telemetry;

import com.google.gson.annotations.SerializedName;

/**
 * Flow protocol tag for the e2e telemetry harness — identifies which sample payload a
 * {@link FlowPacket} carries. Kept local to the smoke tests so the harness does not depend on any
 * flow persistence module (previously imported from the Elasticsearch flow module).
 *
 * <p>The {@link SerializedName} values are load-bearing: {@code FlowTester} serializes this enum via
 * gson into the {@code netflow.version} Elasticsearch term query, and flow documents are indexed with
 * these exact strings — so they must match the previous elastic enum verbatim.
 */
public enum NetflowVersion {
    @SerializedName("Netflow v5")
    V5,
    @SerializedName("Netflow v9")
    V9,
    @SerializedName("IPFIX")
    IPFIX,
    @SerializedName("SFLOW")
    SFLOW;
}
