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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;
import org.opennms.netmgt.config.datacollection.DatacollectionGroup;
import org.opennms.netmgt.config.datacollection.Group;
import org.opennms.netmgt.config.datacollection.MibObj;

/**
 * Tests for {@link SnmpDataCollectionDefaultsUpdate} that don't need a database.
 * DB behavior (ledger, insert/append, no-resurrection) is covered by
 * SnmpDataCollectionDefaultsUpdateIT.
 */
public class SnmpDataCollectionDefaultsUpdateTest {

    private static final String NETSNMP_XML =
            "../../../opennms-base-assembly/src/main/filtered/etc/datacollection/netsnmp.xml";

    @Test
    public void bundledGroupParses() throws SQLException {
        final Group group = new SnmpDataCollectionDefaultsUpdate().loadBundledGroup(
                SnmpDataCollectionDefaultsUpdate.UCD_MEMORY_X_RESOURCE,
                SnmpDataCollectionDefaultsUpdate.UCD_MEMORY_X_GROUP_NAME);

        assertEquals("ucd-memory-X", group.getName());
        assertEquals("ignore", group.getIfType());
        assertEquals(9, group.getMibObjs().size());

        final List<String> aliases = group.getMibObjs().stream()
                .map(MibObj::getAlias)
                .collect(Collectors.toList());
        assertEquals(List.of("memTotalSwapX", "memAvailSwapX", "memTotalRealX", "memAvailRealX",
                "memTotalFreeX", "memSharedX", "memBufferX", "memCachedX", "memSysAvail"), aliases);
    }

    /**
     * The DB delta and the XML migration must produce identical rows: the
     * bundled fragment has to stay in sync with the group shipped in
     * etc/datacollection/netsnmp.xml.
     */
    @Test
    public void bundledGroupMatchesShippedNetsnmpXml() throws SQLException {
        final File netsnmpXml = new File(NETSNMP_XML);
        assertTrue("expected shipped netsnmp.xml at " + netsnmpXml.getAbsolutePath(), netsnmpXml.exists());

        final DatacollectionGroup shippedDcGroup =
                new SnmpDataCollectionMigration().unmarshal(DatacollectionGroup.class, netsnmpXml);
        final Group shipped = shippedDcGroup.getGroups().stream()
                .filter(g -> SnmpDataCollectionDefaultsUpdate.UCD_MEMORY_X_GROUP_NAME.equals(g.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ucd-memory-X group missing from shipped netsnmp.xml"));

        final Group bundled = new SnmpDataCollectionDefaultsUpdate().loadBundledGroup(
                SnmpDataCollectionDefaultsUpdate.UCD_MEMORY_X_RESOURCE,
                SnmpDataCollectionDefaultsUpdate.UCD_MEMORY_X_GROUP_NAME);

        // Compare via the same mapping the SQL inserts use.
        assertEquals(DataCollectionGroupMapper.mapMibObjects(shipped),
                DataCollectionGroupMapper.mapMibObjects(bundled));
        assertEquals(shipped.getIfType(), bundled.getIfType());
        assertEquals(DataCollectionGroupMapper.mapIncludeGroups(shipped),
                DataCollectionGroupMapper.mapIncludeGroups(bundled));
        assertEquals(DataCollectionGroupMapper.mapMibObjProperties(shipped),
                DataCollectionGroupMapper.mapMibObjProperties(bundled));
    }

    /**
     * The shipped systemDef must reference the new group, so the XML-migration
     * path matches what appendGroupToSystemDef produces on the DB path.
     */
    @Test
    public void shippedSystemDefReferencesNewGroup() throws SQLException {
        final DatacollectionGroup shippedDcGroup =
                new SnmpDataCollectionMigration().unmarshal(DatacollectionGroup.class, new File(NETSNMP_XML));
        final var systemDef = shippedDcGroup.getSystemDefs().stream()
                .filter(sd -> SnmpDataCollectionDefaultsUpdate.NET_SNMP_SYSTEMDEF_NAME.equals(sd.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Net-SNMP systemDef missing from shipped netsnmp.xml"));

        final List<String> includeGroups = systemDef.getCollect().getIncludeGroups();
        final int memIdx = includeGroups.indexOf(SnmpDataCollectionDefaultsUpdate.UCD_MEMORY_GROUP_NAME);
        final int memXIdx = includeGroups.indexOf(SnmpDataCollectionDefaultsUpdate.UCD_MEMORY_X_GROUP_NAME);
        assertTrue("ucd-memory should be referenced", memIdx >= 0);
        assertEquals("ucd-memory-X should directly follow ucd-memory", memIdx + 1, memXIdx);
    }

    @Test
    public void parseStringArrayHandlesEdgeCases() throws SQLException {
        assertTrue(SnmpDataCollectionDefaultsUpdate.parseStringArray(null).isEmpty());
        assertTrue(SnmpDataCollectionDefaultsUpdate.parseStringArray("").isEmpty());
        assertTrue(SnmpDataCollectionDefaultsUpdate.parseStringArray("  ").isEmpty());

        final List<String> parsed = SnmpDataCollectionDefaultsUpdate.parseStringArray("[\"a\",\"b\"]");
        assertNotNull(parsed);
        assertEquals(List.of("a", "b"), parsed);
        // returned list must be mutable — appendGroupToSystemDef inserts into it
        parsed.add("c");
        assertEquals(3, parsed.size());
    }
}
