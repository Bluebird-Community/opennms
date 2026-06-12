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
package org.opennms.netmgt.trapd;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opennms.core.test.OpenNMSJUnit4ClassRunner;
import org.opennms.core.test.db.annotations.JUnitTemporaryDatabase;
import org.opennms.core.utils.InetAddressUtils;
import org.opennms.features.scv.api.Credentials;
import org.opennms.features.scv.api.SecureCredentialsVault;
import org.opennms.netmgt.config.EventConfTestUtil;
import org.opennms.netmgt.config.TrapdConfigFactory;
import org.opennms.netmgt.config.api.EventConfDao;
import org.opennms.netmgt.dao.mock.MockEventIpcManager;
import org.opennms.netmgt.events.api.EventConstants;
import org.opennms.netmgt.model.EventConfEvent;
import org.opennms.netmgt.model.EventConfGlobalSecurity;
import org.opennms.netmgt.model.events.EventBuilder;
import org.opennms.netmgt.snmp.SnmpInstId;
import org.opennms.netmgt.snmp.SnmpObjId;
import org.opennms.netmgt.snmp.SnmpTrapBuilder;
import org.opennms.netmgt.snmp.SnmpUtils;
import org.opennms.netmgt.snmp.SnmpV1TrapBuilder;
import org.opennms.netmgt.xml.event.Event;
import org.opennms.test.JUnitConfigurationEnvironment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.context.ContextConfiguration;

@RunWith(OpenNMSJUnit4ClassRunner.class)
@ContextConfiguration(locations={
        "classpath:/META-INF/opennms/applicationContext-soa.xml",
        "classpath:/META-INF/opennms/applicationContext-dao.xml",
        "classpath:/META-INF/opennms/applicationContext-mockConfigManager.xml",
        "classpath*:/META-INF/opennms/component-dao.xml",
        "classpath:/META-INF/opennms/mockEventIpcManager.xml",
        "classpath:/META-INF/opennms/applicationContext-commonConfigs.xml",
        "classpath:/META-INF/opennms/applicationContext-minimal-conf.xml",
        "classpath:/META-INF/opennms/applicationContext-daemon.xml",
        "classpath:/META-INF/opennms/applicationContext-trapDaemon.xml",
        // Overrides the port that Trapd binds to and sets newSuspectOnTrap to 'true'
        "classpath:/org/opennms/netmgt/trapd/applicationContext-trapDaemonTest.xml"
})
@JUnitConfigurationEnvironment
@JUnitTemporaryDatabase
public class TrapdIT {

    public static class MockSecureCredentialsVault implements SecureCredentialsVault {
        final Map<String, Credentials> credentials = new TreeMap<>();

        public MockSecureCredentialsVault() {
            setCredentials("auth-undefined", new Credentials("some-security-name-undefined", "0p3nNMSv3"));
            setCredentials("auth-noAuthNoPriv", new Credentials("some-security-name-noAuthNoPriv", "0p3nNMSv3"));
            setCredentials("auth-authNoPriv", new Credentials("some-security-name-authNoPriv", "0p3nNMSv3"));
            setCredentials("auth-authPriv", new Credentials("some-security-name-authPriv", "0p3nNMSv3"));
        }

        @Override
        public Set<String> getAliases() {
            return this.credentials.keySet();
        }

        @Override
        public Credentials getCredentials(String alias) {
            return this.credentials.get(alias);
        }

        @Override
        public Map<String, Credentials> getAllCredentials() {
            return this.credentials;
        }

        @Override
        public void setCredentials(String alias, Credentials credentials) {
            this.credentials.put(alias, credentials);
        }

        @Override
        public void deleteCredentials(String alias) {
            this.credentials.remove(alias);
        }
    }

    @Autowired
    private TrapdConfigFactory m_trapdConfig;

    @Autowired
    Trapd m_trapd;

    @Autowired
    MockEventIpcManager m_mockEventIpcManager;

    @Autowired
    private EventConfDao eventConfDao;

    private final InetAddress localAddr = InetAddressUtils.getLocalLoopbackAddress().get();
    private final String localhost = InetAddressUtils.toIpAddrString(localAddr);

    @Before
    public void setUp() throws Exception {
        List<EventConfEvent> events = EventConfTestUtil.parseResourcesAsEventConfEvents(new FileSystemResource("src/test/resources/org/opennms/netmgt/trapd/eventconf.xml"));
        List<EventConfGlobalSecurity> eventConfGlobalSecurityList = EventConfTestUtil.parseResourcesAsEventConfGlobalSecurities(new FileSystemResource("src/test/resources/org/opennms/netmgt/trapd/eventconf.xml"));
        // Load into DB
        eventConfDao.loadEventsFromDB(events, eventConfGlobalSecurityList);

        m_mockEventIpcManager.setSynchronous(true);
        m_trapd.setSecureCredentialsVault(new MockSecureCredentialsVault());
        m_trapd.onStart();
    }

    @After
    public void tearDown() {
        m_trapd.onStop();
        m_mockEventIpcManager.getEventAnticipator().verifyAnticipated(3000, 0, 0, 0, 0);
    }

    @Test
    public void testSnmpV1TrapSend() throws Exception {
        SnmpV1TrapBuilder pdu = SnmpUtils.getV1TrapBuilder();
        pdu.setEnterprise(SnmpObjId.get(".1.3.6.1.4.1.5813"));
        pdu.setGeneric(1);
        pdu.setSpecific(0);
        pdu.setTimeStamp(666L);
        pdu.setAgentAddress(localAddr);

        EventBuilder defaultTrapBuilder = new EventBuilder("uei.opennms.org/default/trap", "trapd");
        defaultTrapBuilder.setInterface(localAddr);
        defaultTrapBuilder.setSnmpVersion("v1");
        m_mockEventIpcManager.getEventAnticipator().anticipateEvent(defaultTrapBuilder.getEvent());

        EventBuilder newSuspectBuilder = new EventBuilder(EventConstants.NEW_SUSPECT_INTERFACE_EVENT_UEI, "trapd");
        newSuspectBuilder.setInterface(localAddr);
        m_mockEventIpcManager.getEventAnticipator().anticipateEvent(newSuspectBuilder.getEvent());

        pdu.send(localhost, m_trapdConfig.getSnmpTrapPort(), "public");

        // Wait until we received the expected events
        await().until(() -> m_mockEventIpcManager.getEventAnticipator().getAnticipatedEventsReceived(), hasSize(2));
    }

    @Test
    public void testSnmpV2cTrapSend() throws Exception {
        SnmpObjId enterpriseId = SnmpObjId.get(".1.3.6.1.4.1.5813.0.6");
        SnmpTrapBuilder pdu = SnmpUtils.getV2TrapBuilder();
        pdu.addVarBind(SnmpObjId.get(".1.3.6.1.2.1.1.3.0"), SnmpUtils.getValueFactory().getTimeTicks(0));
        pdu.addVarBind(SnmpObjId.get(".1.3.6.1.6.3.1.1.4.1.0"), SnmpUtils.getValueFactory().getObjectId(enterpriseId));

        EventBuilder defaultTrapBuilder = new EventBuilder("uei.opennms.org/default/trap", "trapd");
        defaultTrapBuilder.setInterface(localAddr);
        defaultTrapBuilder.setSnmpVersion("v2c");
        m_mockEventIpcManager.getEventAnticipator().anticipateEvent(defaultTrapBuilder.getEvent());

        EventBuilder newSuspectBuilder = new EventBuilder(EventConstants.NEW_SUSPECT_INTERFACE_EVENT_UEI, "trapd");
        newSuspectBuilder.setInterface(localAddr);
        newSuspectBuilder.setEnterpriseId(".1.3.6.1.4.1.5813");
        m_mockEventIpcManager.getEventAnticipator().anticipateEvent(newSuspectBuilder.getEvent());

        pdu.send(localhost, m_trapdConfig.getSnmpTrapPort(), "public");

        // Wait until we received the expected events
        await().until(() -> m_mockEventIpcManager.getEventAnticipator().getAnticipatedEventsReceived(), hasSize(2));
    }

    /**
     * Verifies that we can match event with trapoid mask element.
     * @throws Exception
     */
    @Test
    public void testSnmpV2cTrapWithTrapOID() throws Exception {
        SnmpObjId enterpriseId = SnmpObjId.get(".1.3.6.1.4.1.55.0.5");
        SnmpTrapBuilder pdu = SnmpUtils.getV2TrapBuilder();
        pdu.addVarBind(SnmpObjId.get(".1.3.6.1.2.1.1.3.0"), SnmpUtils.getValueFactory().getTimeTicks(0));
        pdu.addVarBind(SnmpObjId.get(".1.3.6.1.6.3.1.1.4.1.0"), SnmpUtils.getValueFactory().getObjectId(enterpriseId));

        // eventconf.xml has a matching event with trapoid mask element.
        EventBuilder defaultTrapBuilder = new EventBuilder("uei.opennms.org/IANA/Example/traps/exampleEnterpriseTrapWithOID", "trapd");
        defaultTrapBuilder.setInterface(localAddr);
        defaultTrapBuilder.setSnmpVersion("v2c");
        m_mockEventIpcManager.getEventAnticipator().anticipateEvent(defaultTrapBuilder.getEvent());

        EventBuilder newSuspectBuilder = new EventBuilder(EventConstants.NEW_SUSPECT_INTERFACE_EVENT_UEI, "trapd");
        newSuspectBuilder.setInterface(localAddr);
        m_mockEventIpcManager.getEventAnticipator().anticipateEvent(newSuspectBuilder.getEvent());

        pdu.send(localhost, m_trapdConfig.getSnmpTrapPort(), "public");

        // Wait until we received the expected events
        await().until(() -> m_mockEventIpcManager.getEventAnticipator().getAnticipatedEventsReceived(), hasSize(2));
        List<Event> eventList = m_mockEventIpcManager.getEventAnticipator().getAnticipatedEventsReceived();
        Assert.assertThat(eventList.size(), Matchers.equalTo(2));
        Optional<Event> eventWithSnmp = eventList.stream().filter(event -> event.getSnmp() != null).findFirst();
        assertTrue(eventWithSnmp.isPresent());
        Assert.assertThat(eventWithSnmp.get().getSnmp().getTrapOID(), Matchers.equalTo(".1.3.6.1.4.1.55.0.5"));
    }

    @Test
    public void testSnmpV1TrapWithTrapOID() throws Exception {
        // Verify for SNMP V1 Trap
        SnmpV1TrapBuilder pdu = SnmpUtils.getV1TrapBuilder();
        pdu.setEnterprise(SnmpObjId.get(".1.3.6.1.4.1.9.9.276"));
        pdu.setGeneric(6);
        pdu.setSpecific(4);
        pdu.setTimeStamp(0);
        pdu.setAgentAddress(localAddr);

        // eventconf.xml has a matching event with trapoid mask element.
        EventBuilder defaultTrapBuilder = new EventBuilder("uei.opennms.org/IANA/Example/traps/exampleTrapOIDForSNMPV1", "trapd");
        defaultTrapBuilder.setInterface(localAddr);
        defaultTrapBuilder.setSnmpVersion("v1");
        m_mockEventIpcManager.getEventAnticipator().anticipateEvent(defaultTrapBuilder.getEvent());
        EventBuilder newSuspectBuilder = new EventBuilder(EventConstants.NEW_SUSPECT_INTERFACE_EVENT_UEI, "trapd");
        newSuspectBuilder.setInterface(localAddr);
        m_mockEventIpcManager.getEventAnticipator().anticipateEvent(newSuspectBuilder.getEvent());

        pdu.send(localhost, m_trapdConfig.getSnmpTrapPort(), "public");

        // Wait until we received the expected events
        await().until(() -> m_mockEventIpcManager.getEventAnticipator().getAnticipatedEventsReceived(), hasSize(2));
        List<Event> eventList = m_mockEventIpcManager.getEventAnticipator().getAnticipatedEventsReceived();
        Assert.assertThat(eventList.size(), Matchers.equalTo(2));
        Optional<Event> eventWithSnmp = eventList.stream().filter(event -> event.getSnmp() != null).findFirst();
        assertTrue(eventWithSnmp.isPresent());
        Assert.assertThat(eventWithSnmp.get().getSnmp().getTrapOID(), Matchers.equalTo(".1.3.6.1.4.1.9.9.276.0.4"));
    }

    /**
     * Verifies that we can pull the agent address from the snmpTrapAddress
     * varbind in a SNMPv2 trap.
     */
    @Test
    public void testSnmpV2cTrapWithAddressFromVarbind() throws Exception {
        // Enable the feature (disabled by default)
        m_trapdConfig.getConfig().setUseAddressFromVarbind(true);

        InetAddress remoteAddr = InetAddress.getByName("10.255.1.1");

        SnmpObjId enterpriseId = SnmpObjId.get(".1.3.6.1.4.1.5813");
        SnmpObjId trapOID = SnmpObjId.get(enterpriseId, new SnmpInstId(1));
        SnmpTrapBuilder pdu = SnmpUtils.getV2TrapBuilder();
        pdu.addVarBind(SnmpObjId.get(".1.3.6.1.2.1.1.3.0"), SnmpUtils.getValueFactory().getTimeTicks(0));
        pdu.addVarBind(SnmpObjId.get(".1.3.6.1.6.3.1.1.4.1.0"), SnmpUtils.getValueFactory().getObjectId(trapOID));
        pdu.addVarBind(SnmpObjId.get(".1.3.6.1.6.3.1.1.4.3.0"), SnmpUtils.getValueFactory().getObjectId(enterpriseId));
        // The varbind with the address
        pdu.addVarBind(TrapUtils.SNMP_TRAP_ADDRESS_OID, SnmpUtils.getValueFactory().getIpAddress(InetAddress.getByName("10.255.1.1")));

        EventBuilder defaultTrapBuilder = new EventBuilder("uei.opennms.org/default/trap", "trapd");
        defaultTrapBuilder.setInterface(remoteAddr);
        defaultTrapBuilder.setSnmpVersion("v2c");
        m_mockEventIpcManager.getEventAnticipator().anticipateEvent(defaultTrapBuilder.getEvent());

        EventBuilder newSuspectBuilder = new EventBuilder(EventConstants.NEW_SUSPECT_INTERFACE_EVENT_UEI, "trapd");
        // The address in the newSuspect event should match the one specified in the varbind
        newSuspectBuilder.setInterface(remoteAddr);
        m_mockEventIpcManager.getEventAnticipator().anticipateEvent(newSuspectBuilder.getEvent());

        pdu.send(localhost, m_trapdConfig.getSnmpTrapPort(), "public");

        // Wait until we received the expected events
        await().until(() -> m_mockEventIpcManager.getEventAnticipator().getAnticipatedEventsReceived(), hasSize(2));
    }

    @Test
    public void testSnmpV2cTrapWithAddressFromVarbind2() throws Exception {
        // Enable the feature (disabled by default)
        m_trapdConfig.getConfig().setUseAddressFromVarbind(true);
        m_trapdConfig.getConfig().setBatchSize(2);
        m_trapdConfig.getConfig().setBatchInterval(1000);
        int interval = m_trapdConfig.getConfig().getBatchInterval();
        int batchSize = m_trapdConfig.getConfig().getBatchSize();
        System.out.printf("Batch size = %d interval = %d", batchSize, interval);

        InetAddress remoteAddr = InetAddress.getByName("10.255.1.1");

        SnmpObjId enterpriseId = SnmpObjId.get(".1.3.6.1.4.1.5813");
        SnmpObjId trapOID = SnmpObjId.get(enterpriseId, new SnmpInstId(1));
        SnmpTrapBuilder pdu = SnmpUtils.getV2TrapBuilder();
        pdu.addVarBind(SnmpObjId.get(".1.3.6.1.2.1.1.3.0"), SnmpUtils.getValueFactory().getTimeTicks(0));
        pdu.addVarBind(SnmpObjId.get(".1.3.6.1.6.3.1.1.4.1.0"), SnmpUtils.getValueFactory().getObjectId(trapOID));
        pdu.addVarBind(SnmpObjId.get(".1.3.6.1.6.3.1.1.4.3.0"), SnmpUtils.getValueFactory().getObjectId(enterpriseId));
        // The varbind with the address
        pdu.addVarBind(TrapUtils.SNMP_TRAP_ADDRESS_OID, SnmpUtils.getValueFactory().getIpAddress(InetAddress.getByName("10.255.1.1")));

        EventBuilder defaultTrapBuilder = new EventBuilder("uei.opennms.org/default/trap", "trapd");
        defaultTrapBuilder.setInterface(remoteAddr);
        defaultTrapBuilder.setSnmpVersion("v2c");
        m_mockEventIpcManager.getEventAnticipator().anticipateEvent(defaultTrapBuilder.getEvent());

        EventBuilder newSuspectBuilder = new EventBuilder(EventConstants.NEW_SUSPECT_INTERFACE_EVENT_UEI, "trapd");
        // The address in the newSuspect event should match the one specified in the varbind
        newSuspectBuilder.setInterface(remoteAddr);
        m_mockEventIpcManager.getEventAnticipator().anticipateEvent(newSuspectBuilder.getEvent());

        InetAddress secondaryRemoteAddr = InetAddress.getByName("10.255.1.2");

        SnmpObjId secondaryEnterpriseId = SnmpObjId.get(".1.3.6.1.4.1.5813");
        SnmpObjId secondaryTrapOID = SnmpObjId.get(secondaryEnterpriseId, new SnmpInstId(1));
        SnmpTrapBuilder secondaryPdu = SnmpUtils.getV2TrapBuilder();
        secondaryPdu.addVarBind(SnmpObjId.get(".1.3.6.1.2.1.1.3.0"), SnmpUtils.getValueFactory().getTimeTicks(0));
        secondaryPdu.addVarBind(SnmpObjId.get(".1.3.6.1.6.3.1.1.4.1.0"), SnmpUtils.getValueFactory().getObjectId(secondaryTrapOID));
        secondaryPdu.addVarBind(SnmpObjId.get(".1.3.6.1.6.3.1.1.4.3.0"), SnmpUtils.getValueFactory().getObjectId(secondaryEnterpriseId));

        // The varbind with the address
        secondaryPdu.addVarBind(TrapUtils.SNMP_TRAP_ADDRESS_OID, SnmpUtils.getValueFactory().getIpAddress(InetAddress.getByName("10.255.1.2")));

        EventBuilder secondaryTrapBuilder = new EventBuilder("uei.opennms.org/default/trap", "trapd");
        secondaryTrapBuilder.setInterface(secondaryRemoteAddr);
        secondaryTrapBuilder.setSnmpVersion("v2c");
        m_mockEventIpcManager.getEventAnticipator().anticipateEvent(secondaryTrapBuilder.getEvent());

        EventBuilder secondarySuspectBuilder = new EventBuilder(EventConstants.NEW_SUSPECT_INTERFACE_EVENT_UEI, "trapd");

        // The address in the secondarySuspect event should match the one specified in the varbind
        secondarySuspectBuilder.setInterface(secondaryRemoteAddr);
        m_mockEventIpcManager.getEventAnticipator().anticipateEvent(secondarySuspectBuilder.getEvent());
        pdu.send(localhost, m_trapdConfig.getSnmpTrapPort(), "public");
        secondaryPdu.send(localhost, m_trapdConfig.getSnmpTrapPort(), "public");

        await().until(() -> m_mockEventIpcManager.getEventAnticipator().getAnticipatedEventsReceived(), hasSize(4));
    }

}
