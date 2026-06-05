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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opennms.core.test.OpenNMSJUnit4ClassRunner;
import org.opennms.core.test.db.TemporaryDatabase;
import org.opennms.core.test.db.TemporaryDatabaseAware;
import org.opennms.core.test.db.TemporaryDatabaseExecutionListener;
import org.opennms.core.test.db.annotations.JUnitTemporaryDatabase;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;

/**
 * DB-level tests for {@link SnmpDataCollectionDefaultsUpdate}: ledger
 * semantics, delta application to a migrated database, and the
 * no-resurrection guarantee after an administrator deletes shipped content.
 */
@RunWith(OpenNMSJUnit4ClassRunner.class)
@TestExecutionListeners({TemporaryDatabaseExecutionListener.class})
@ContextConfiguration(locations = {
        "classpath:/META-INF/opennms/applicationContext-soa.xml",
        "classpath:/META-INF/opennms/applicationContext-minimal-conf.xml"})
@JUnitTemporaryDatabase
public class SnmpDataCollectionDefaultsUpdateIT implements TemporaryDatabaseAware<TemporaryDatabase> {

    private TemporaryDatabase database;
    private Connection connection;

    @Override
    public void setTemporaryDatabase(final TemporaryDatabase database) {
        this.database = database;
    }

    @Before
    public void setUp() throws SQLException {
        connection = database.getConnection();
        connection.setAutoCommit(false);
    }

    @After
    public void tearDown() throws SQLException {
        if (connection != null) {
            connection.rollback();
            connection.close();
        }
    }

    @Test
    public void appliesDeltaToMigratedDatabaseAndIsIdempotent() throws SQLException {
        seedMigratedDatabaseWithoutUcdMemoryX();

        new SnmpDataCollectionDefaultsUpdate().execute(connection);

        final int sourceId = SnmpDataCollectionSqlHelper.findSourceIdByName(
                connection, SnmpDataCollectionDefaultsUpdate.NET_SNMP_SOURCE_NAME);
        assertEquals(1, countMibGroups(sourceId, "ucd-memory-X"));

        final String mibObjects = selectMibGroupObjects(sourceId, "ucd-memory-X");
        assertNotNull(mibObjects);
        assertTrue(mibObjects.contains("memAvailRealX"));
        assertTrue(mibObjects.contains(".1.3.6.1.4.1.2021.4.27"));

        final List<String> groupNames = SnmpDataCollectionDefaultsUpdate.parseStringArray(
                selectSystemDefGroupNames(sourceId, "Net-SNMP"));
        final int memIdx = groupNames.indexOf("ucd-memory");
        assertTrue(memIdx >= 0);
        assertEquals("ucd-memory-X inserted directly after ucd-memory", memIdx + 1, groupNames.indexOf("ucd-memory-X"));

        assertTrue(isLedgerRecorded(SnmpDataCollectionDefaultsUpdate.UPDATE_ID_UCD_MEMORY_X));

        // Second run must not duplicate anything.
        new SnmpDataCollectionDefaultsUpdate().execute(connection);
        assertEquals(1, countMibGroups(sourceId, "ucd-memory-X"));
        final List<String> groupNamesAfter = SnmpDataCollectionDefaultsUpdate.parseStringArray(
                selectSystemDefGroupNames(sourceId, "Net-SNMP"));
        assertEquals(groupNames, groupNamesAfter);
    }

    @Test
    public void doesNotResurrectContentDeletedByAdmin() throws SQLException {
        seedMigratedDatabaseWithoutUcdMemoryX();

        new SnmpDataCollectionDefaultsUpdate().execute(connection);
        final int sourceId = SnmpDataCollectionSqlHelper.findSourceIdByName(
                connection, SnmpDataCollectionDefaultsUpdate.NET_SNMP_SOURCE_NAME);
        assertEquals(1, countMibGroups(sourceId, "ucd-memory-X"));

        // Admin deletes the group and the systemDef reference.
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM snmp_collection_mib_groups WHERE collection_source_id = ? AND name = ?")) {
            ps.setInt(1, sourceId);
            ps.setString(2, "ucd-memory-X");
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE snmp_collection_systemdefs SET mib_group_names = ? WHERE collection_source_id = ? AND name = ?")) {
            ps.setString(1, "[\"ucd-memory\"]");
            ps.setInt(2, sourceId);
            ps.setString(3, "Net-SNMP");
            ps.executeUpdate();
        }

        // The ledger must prevent re-application.
        new SnmpDataCollectionDefaultsUpdate().execute(connection);
        assertEquals(0, countMibGroups(sourceId, "ucd-memory-X"));
        assertFalse(SnmpDataCollectionDefaultsUpdate.parseStringArray(
                selectSystemDefGroupNames(sourceId, "Net-SNMP")).contains("ucd-memory-X"));
    }

    @Test
    public void recordsNoopWhenContentAlreadyPresent() throws SQLException {
        // Fresh-install shape: migration already imported the new XML.
        seedMigratedDatabaseWithoutUcdMemoryX();
        final int sourceId = SnmpDataCollectionSqlHelper.findSourceIdByName(
                connection, SnmpDataCollectionDefaultsUpdate.NET_SNMP_SOURCE_NAME);
        final SnmpDataCollectionDefaultsUpdate update = new SnmpDataCollectionDefaultsUpdate();
        SnmpDataCollectionSqlHelper.batchInsertMibGroups(connection, sourceId, List.of(
                update.loadBundledGroup(SnmpDataCollectionDefaultsUpdate.UCD_MEMORY_X_RESOURCE, "ucd-memory-X")));
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE snmp_collection_systemdefs SET mib_group_names = ? WHERE collection_source_id = ? AND name = ?")) {
            ps.setString(1, "[\"ucd-memory\",\"ucd-memory-X\",\"ucd-sysstat\"]");
            ps.setInt(2, sourceId);
            ps.setString(3, "Net-SNMP");
            ps.executeUpdate();
        }

        update.execute(connection);

        assertEquals(1, countMibGroups(sourceId, "ucd-memory-X"));
        assertEquals(List.of("ucd-memory", "ucd-memory-X", "ucd-sysstat"),
                SnmpDataCollectionDefaultsUpdate.parseStringArray(selectSystemDefGroupNames(sourceId, "Net-SNMP")));
        assertTrue(isLedgerRecorded(SnmpDataCollectionDefaultsUpdate.UPDATE_ID_UCD_MEMORY_X));
    }

    @Test
    public void defersUntilMigrationHasPopulatedTables() throws SQLException {
        // No profiles seeded: the XML->DB migration hasn't produced data yet.
        new SnmpDataCollectionDefaultsUpdate().execute(connection);
        assertFalse(isLedgerRecorded(SnmpDataCollectionDefaultsUpdate.UPDATE_ID_UCD_MEMORY_X));
    }

    @Test
    public void recordsAppliedWhenSourceWasRemovedByAdmin() throws SQLException {
        insertProfile();
        // No Net-SNMP source at all — admin removed it. Update must be a no-op
        // but still be recorded so it never runs again.
        new SnmpDataCollectionDefaultsUpdate().execute(connection);
        assertTrue(isLedgerRecorded(SnmpDataCollectionDefaultsUpdate.UPDATE_ID_UCD_MEMORY_X));
        assertEquals(-1, SnmpDataCollectionSqlHelper.findSourceIdByName(
                connection, SnmpDataCollectionDefaultsUpdate.NET_SNMP_SOURCE_NAME));
    }

    // --- seeding helpers ---

    /** Mimic a pre-NMS-18291 migrated database: profile + Net-SNMP source/systemDef, no ucd-memory-X. */
    private void seedMigratedDatabaseWithoutUcdMemoryX() throws SQLException {
        insertProfile();
        final int sourceId = SnmpDataCollectionSqlHelper.insertSource(
                connection, "Net-SNMP", "Net-SNMP", null, "system-migration");
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO snmp_collection_mib_groups "
                        + "(id, collection_source_id, name, if_type, mib_objects, enabled) "
                        + "VALUES (nextval('snmp_collection_mib_groups_id_seq'), ?, 'ucd-memory', 'ignore', '[]', true)")) {
            ps.setInt(1, sourceId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO snmp_collection_systemdefs "
                        + "(id, collection_source_id, name, sysoid_mask, mib_group_names, enabled) "
                        + "VALUES (nextval('snmp_collection_systemdefs_id_seq'), ?, 'Net-SNMP', '.1.3.6.1.4.1.8072.3.', ?, true)")) {
            ps.setInt(1, sourceId);
            ps.setString(2, "[\"mib2-interfaces\",\"ucd-memory\",\"ucd-sysstat\"]");
            ps.executeUpdate();
        }
    }

    private void insertProfile() throws SQLException {
        SnmpDataCollectionSqlHelper.insertProfile(connection, "default", 300, null, "select",
                "[\"Net-SNMP\"]", null);
    }

    // --- assertion helpers ---

    private int countMibGroups(final int sourceId, final String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM snmp_collection_mib_groups WHERE collection_source_id = ? AND name = ?")) {
            ps.setInt(1, sourceId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private String selectMibGroupObjects(final int sourceId, final String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT mib_objects FROM snmp_collection_mib_groups WHERE collection_source_id = ? AND name = ?")) {
            ps.setInt(1, sourceId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private String selectSystemDefGroupNames(final int sourceId, final String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT mib_group_names FROM snmp_collection_systemdefs WHERE collection_source_id = ? AND name = ?")) {
            ps.setInt(1, sourceId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private boolean isLedgerRecorded(final String updateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM snmp_collection_defaults_log WHERE update_id = ?")) {
            ps.setString(1, updateId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
