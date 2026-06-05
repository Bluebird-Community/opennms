/*
 * Licensed to The OpenNMS Group, Inc (TOG) under one or more
 * contributor license agreements.  See the LICENSE.md file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * TOG licenses this file to You under the GNU Affero General
 * Public License Version 3 (the "License") or (at your option)
 * any later version.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at:
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.opennms.config.upgrade.datacollection;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.opennms.netmgt.config.datacollection.DatacollectionGroup;
import org.opennms.netmgt.config.datacollection.Group;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies shipped-default changes to the DB-resident SNMP data collection
 * config for installations that migrated from XML before those defaults
 * changed (see NMS-18291 review on the original XML-only change).
 *
 * <p>Runs at every startup, after {@link SnmpDataCollectionMigration}, so the
 * ordering works out for every install type in a single boot:
 * <ul>
 *   <li>fresh install / first boot after upgrading from a pre-DB 36.x: the
 *       migration has just imported the shipped XML (which already contains
 *       the new defaults), so each update is a content no-op and is only
 *       recorded in the ledger;</li>
 *   <li>already-migrated install: the update applies the delta to the DB.</li>
 * </ul>
 *
 * <p>Each update is identified by an id recorded in
 * {@code snmp_collection_defaults_log} once applied. The ledger — not a
 * content check — provides idempotency, so content an administrator has
 * deliberately deleted afterwards is never resurrected. Updates only ever add
 * missing rows or append missing references; they never overwrite existing
 * rows, which remain owned by the administrator.
 */
public class SnmpDataCollectionDefaultsUpdate {

    private static final Logger LOG = LoggerFactory.getLogger(SnmpDataCollectionDefaultsUpdate.class);

    static final String UPDATE_ID_UCD_MEMORY_X = "NMS-18291-ucd-memory-X";

    static final String NET_SNMP_SOURCE_NAME = "Net-SNMP";
    static final String NET_SNMP_SYSTEMDEF_NAME = "Net-SNMP";
    static final String UCD_MEMORY_GROUP_NAME = "ucd-memory";
    static final String UCD_MEMORY_X_GROUP_NAME = "ucd-memory-X";
    static final String UCD_MEMORY_X_RESOURCE = "/datacollection-defaults/nms-18291-ucd-memory-x.xml";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Apply all pending default updates. The caller owns the transaction
     * (commit/rollback), matching {@link SnmpDataCollectionMigration#execute}.
     */
    public void execute(final Connection conn) throws SQLException {
        if (!isLedgerAvailable(conn)) {
            LOG.warn("snmp_collection_defaults_log table not found — datacollection default updates skipped. "
                    + "Run the installer (bin/install) to bring the database schema up to date.");
            return;
        }
        if (!isMigrated(conn)) {
            // XML→DB migration hasn't produced any profiles yet (e.g. empty etc).
            // Don't record anything; retry on a later startup once data exists.
            LOG.info("SNMP data collection tables not populated yet — deferring default updates.");
            return;
        }

        if (!isApplied(conn, UPDATE_ID_UCD_MEMORY_X)) {
            applyUcdMemoryX(conn);
            recordApplied(conn, UPDATE_ID_UCD_MEMORY_X);
        }
    }

    /**
     * NMS-18291: add the ucd-memory-X MIB group (64-bit UCD-SNMP memory gauges)
     * to the "Net-SNMP" source and reference it from the "Net-SNMP" systemDef.
     */
    private void applyUcdMemoryX(final Connection conn) throws SQLException {
        final int sourceId = SnmpDataCollectionSqlHelper.findSourceIdByName(conn, NET_SNMP_SOURCE_NAME);
        if (sourceId < 0) {
            LOG.info("{}: source '{}' not present (removed by an administrator?) — nothing to update.",
                    UPDATE_ID_UCD_MEMORY_X, NET_SNMP_SOURCE_NAME);
            return;
        }

        if (mibGroupExists(conn, sourceId, UCD_MEMORY_X_GROUP_NAME)) {
            LOG.debug("{}: MIB group '{}' already present under source '{}'.",
                    UPDATE_ID_UCD_MEMORY_X, UCD_MEMORY_X_GROUP_NAME, NET_SNMP_SOURCE_NAME);
        } else {
            final Group group = loadBundledGroup(UCD_MEMORY_X_RESOURCE, UCD_MEMORY_X_GROUP_NAME);
            SnmpDataCollectionSqlHelper.batchInsertMibGroups(conn, sourceId, List.of(group));
            LOG.info("{}: added MIB group '{}' to source '{}'.",
                    UPDATE_ID_UCD_MEMORY_X, UCD_MEMORY_X_GROUP_NAME, NET_SNMP_SOURCE_NAME);
        }

        appendGroupToSystemDef(conn, sourceId, NET_SNMP_SYSTEMDEF_NAME,
                UCD_MEMORY_X_GROUP_NAME, UCD_MEMORY_GROUP_NAME);
    }

    /**
     * Append {@code groupName} to the systemDef's {@code mib_group_names} JSON
     * array if it isn't referenced yet. The new entry is placed directly after
     * {@code afterGroup} when present (mirroring the shipped XML ordering),
     * otherwise at the end.
     */
    private void appendGroupToSystemDef(final Connection conn,
                                        final int sourceId,
                                        final String systemDefName,
                                        final String groupName,
                                        final String afterGroup) throws SQLException {
        final SystemDefRow row = findSystemDef(conn, sourceId, systemDefName);
        if (row == null) {
            LOG.info("{}: systemDef '{}' not present under source '{}' (removed by an administrator?) — "
                    + "MIB group '{}' is not referenced anywhere.",
                    UPDATE_ID_UCD_MEMORY_X, systemDefName, NET_SNMP_SOURCE_NAME, groupName);
            return;
        }

        final List<String> groupNames = parseStringArray(row.mibGroupNamesJson);
        if (groupNames.contains(groupName)) {
            LOG.debug("{}: systemDef '{}' already references '{}'.",
                    UPDATE_ID_UCD_MEMORY_X, systemDefName, groupName);
            return;
        }

        final int anchor = groupNames.indexOf(afterGroup);
        if (anchor >= 0) {
            groupNames.add(anchor + 1, groupName);
        } else {
            groupNames.add(groupName);
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE snmp_collection_systemdefs SET mib_group_names = ? WHERE id = ?")) {
            ps.setString(1, DataCollectionGroupMapper.toJson(groupNames));
            ps.setInt(2, row.id);
            ps.executeUpdate();
        }
        LOG.info("{}: referenced MIB group '{}' from systemDef '{}'.",
                UPDATE_ID_UCD_MEMORY_X, groupName, systemDefName);
    }

    /**
     * Load a named group from a bundled datacollection-group XML resource.
     */
    Group loadBundledGroup(final String resourcePath, final String groupName) throws SQLException {
        try (InputStream stream = SnmpDataCollectionDefaultsUpdate.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new SQLException("Bundled datacollection resource not found on classpath: " + resourcePath);
            }
            final DatacollectionGroup dcGroup = SnmpDataCollectionMigration.unmarshal(
                    DatacollectionGroup.class, stream, resourcePath);
            return dcGroup.getGroups().stream()
                    .filter(g -> groupName.equals(g.getName()))
                    .findFirst()
                    .orElseThrow(() -> new SQLException(
                            "Group '" + groupName + "' not found in bundled resource " + resourcePath));
        } catch (IOException e) {
            throw new SQLException("Failed to read bundled resource " + resourcePath, e);
        }
    }

    private boolean isLedgerAvailable(final Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM snmp_collection_defaults_log LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean isMigrated(final Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM snmp_collection_profiles");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            LOG.debug("snmp_collection_profiles not accessible — default updates skipped: {}", e.getMessage());
            return false;
        }
    }

    private boolean isApplied(final Connection conn, final String updateId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM snmp_collection_defaults_log WHERE update_id = ?")) {
            ps.setString(1, updateId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void recordApplied(final Connection conn, final String updateId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO snmp_collection_defaults_log (update_id) VALUES (?)")) {
            ps.setString(1, updateId);
            ps.executeUpdate();
        }
        LOG.info("Recorded datacollection default update '{}' as applied.", updateId);
    }

    private boolean mibGroupExists(final Connection conn, final int sourceId, final String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM snmp_collection_mib_groups WHERE collection_source_id = ? AND name = ?")) {
            ps.setInt(1, sourceId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private SystemDefRow findSystemDef(final Connection conn, final int sourceId, final String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, mib_group_names FROM snmp_collection_systemdefs WHERE collection_source_id = ? AND name = ?")) {
            ps.setInt(1, sourceId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new SystemDefRow(rs.getInt(1), rs.getString(2));
                }
            }
        }
        return null;
    }

    /** Parse a JSON array of strings, tolerating null/blank as empty. Package-private for testing. */
    static List<String> parseStringArray(final String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            final List<String> parsed = OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {});
            return parsed == null ? new ArrayList<>() : new ArrayList<>(parsed);
        } catch (IOException e) {
            throw new SQLException("Failed to parse mib_group_names JSON: " + json, e);
        }
    }

    private static final class SystemDefRow {
        final int id;
        final String mibGroupNamesJson;

        SystemDefRow(final int id, final String mibGroupNamesJson) {
            this.id = id;
            this.mibGroupNamesJson = mibGroupNamesJson;
        }
    }
}
