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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Date;

import org.junit.Test;
import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.snmp.TrapInformation;
import org.opennms.netmgt.snmp.snmp4j.Snmp4JTrapNotifier;
import org.snmp4j.PDU;
import org.snmp4j.PDUv1;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.IpAddress;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;

public class TrapDTOMapperTest {

	@Test
	public void object2dtoTest() throws UnknownHostException {
		long testStartTime = new Date().getTime();

		PDU snmp4JV2cTrapPdu = new PDU();
		snmp4JV2cTrapPdu.setType(PDU.TRAP);
		OID oid = new OID(".1.3.6.1.2.1.1.3.0.4");
		snmp4JV2cTrapPdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(5000)));
		snmp4JV2cTrapPdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, oid));
		snmp4JV2cTrapPdu.add(new VariableBinding(SnmpConstants.snmpTrapAddress,new IpAddress("127.0.0.1")));

		snmp4JV2cTrapPdu.add(new VariableBinding(new OID(oid), new OctetString("Trap Msg v2-1")));
		snmp4JV2cTrapPdu.add(new VariableBinding(new OID(oid), new OctetString("Trap Msg v2-2")));

		snmp4JV2cTrapPdu.add(new VariableBinding(new OID("1.3.6.1.2.1.1.5.0"),
				new OctetString("Trap v1 msg-1")));
		snmp4JV2cTrapPdu.add(new VariableBinding(new OID(".1.3.6.1.2.1.1.3"),
				new OctetString("Trap v1 msg-2")));
		snmp4JV2cTrapPdu.add(new VariableBinding(new OID(".1.3.6.1.6.3.1.1.4.1.1"), 
				new OctetString("Trap v1 msg-3")));
		snmp4JV2cTrapPdu.add(new VariableBinding(new OID(".1.3.6.1.4.1.733.6.3.18.1.5.0"),
				new Integer32(1))); 
		snmp4JV2cTrapPdu.add(new VariableBinding(new OID("1.3.6.1.2.1.1.5.0"),
				new Null())); 
		snmp4JV2cTrapPdu.add(new VariableBinding(new OID("1.3.6.1.2.1.1.5.1"),
				new Null(128)));
		snmp4JV2cTrapPdu.add(new VariableBinding(new OID("1.3.6.1.2.1.1.5.2"),
				new Null(129)));
		snmp4JV2cTrapPdu.add(new VariableBinding(new OID("1.3.6.1.2.1.1.5.3"),
				new Null(130)));

		TrapInformation snmp4JV2cTrap = new Snmp4JTrapNotifier.Snmp4JV2V3TrapInformation(
			InetAddressUtils.ONE_TWENTY_SEVEN,
			"public",
			snmp4JV2cTrapPdu
		);

		TrapDTO trapDto = new TrapDTO(snmp4JV2cTrap);
		System.out.println("trapDto is : " + trapDto);
		System.out.println("trapDto.getBody() is : " + trapDto.getRawMessage());
		System.out.println("trapDto.getCommunity() is : " + trapDto.getCommunity());

		assertEquals(".1.3.6.1.2.1.1.3", trapDto.getTrapIdentity().getEnterpriseId());
		assertEquals(".1.3.6.1.2.1.1.3.0.4", trapDto.getTrapIdentity().getTrapOID());
		assertEquals(6, trapDto.getTrapIdentity().getGeneric());
		assertEquals(4, trapDto.getTrapIdentity().getSpecific());
		assertEquals(InetAddressUtils.ONE_TWENTY_SEVEN, trapDto.getAgentAddress());
		assertEquals("public", trapDto.getCommunity());
		assertEquals(5000, trapDto.getTimestamp());
		// Trap and agent address are identical with SNMPv2
		assertEquals(InetAddressUtils.ONE_TWENTY_SEVEN, trapDto.getAgentAddress());
		assertEquals("v2", trapDto.getVersion());

		// Make sure that the message was created after the start of the test
		assertTrue(trapDto.getCreationTime() >= testStartTime);
	}

	@Test
	public void object2dtoTestV1() throws UnknownHostException {
		long testStartTime = new Date().getTime();

		PDUv1 snmp4JV1TrapPdu = new PDUv1();
		snmp4JV1TrapPdu.setType(PDU.V1TRAP);
		snmp4JV1TrapPdu.setEnterprise(new OID(".1.3.6.1.6.3.1.1.4.1.0"));
		snmp4JV1TrapPdu.setGenericTrap(10);
		snmp4JV1TrapPdu.setSpecificTrap(0);
		snmp4JV1TrapPdu.setTimestamp(5000);
		snmp4JV1TrapPdu.add(new VariableBinding(new OID("1.3.6.1.2.1.1.5.0"),
				new OctetString("mockhost")));
		snmp4JV1TrapPdu.add(new VariableBinding(new OID(".1.3.6.1.2.1.1.3"),
				new OctetString("mockhost")));
		snmp4JV1TrapPdu.add(new VariableBinding(new OID(
				".1.3.6.1.6.3.1.1.4.1.0"), new OctetString("mockhost")));

		InetAddress inetAddress= InetAddress.getByName("127.0.0.1");;
		TrapInformation snmp4JV1Trap = new Snmp4JTrapNotifier.Snmp4JV1TrapInformation(
				inetAddress, "public", snmp4JV1TrapPdu);


		TrapDTO trapDto = new TrapDTO(snmp4JV1Trap);
		System.out.println("trapDto is : " + trapDto);
		System.out.println("trapDto.getBody() is : " + trapDto.getRawMessage());
		System.out.println("trapDto.getCommunity() is : " + trapDto.getCommunity());

		assertEquals(".1.3.6.1.6.3.1.1.4.1.0", trapDto.getTrapIdentity().getEnterpriseId());
		assertEquals(10, trapDto.getTrapIdentity().getGeneric());
		assertEquals(0, trapDto.getTrapIdentity().getSpecific());
		assertEquals(InetAddressUtils.ONE_TWENTY_SEVEN, trapDto.getAgentAddress());
		assertEquals("public", trapDto.getCommunity());
		assertEquals(5000, trapDto.getTimestamp());
		// This is the "default" value from SNMP4J that indicates that the trap has not been forwarded
		assertEquals("v1", trapDto.getVersion());

		// Make sure that the message was created after the start of the test
		assertTrue(trapDto.getCreationTime() >= testStartTime);
	}
}
