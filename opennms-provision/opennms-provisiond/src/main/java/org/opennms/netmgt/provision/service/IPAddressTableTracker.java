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
package org.opennms.netmgt.provision.service;

import static org.opennms.core.utils.InetAddressUtils.getInetAddress;
import static org.opennms.core.utils.InetAddressUtils.str;

import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.model.OnmsIpInterface;
import org.opennms.netmgt.model.OnmsSnmpInterface;
import org.opennms.netmgt.snmp.RowCallback;
import org.opennms.netmgt.snmp.SnmpInstId;
import org.opennms.netmgt.snmp.SnmpObjId;
import org.opennms.netmgt.snmp.SnmpResult;
import org.opennms.netmgt.snmp.SnmpRowResult;
import org.opennms.netmgt.snmp.SnmpValue;
import org.opennms.netmgt.snmp.TableTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PhysInterfaceTableTracker
 *
 * @author brozow
 * @version $Id: $
 */
public class IPAddressTableTracker extends TableTracker {
    private static final Logger LOG = LoggerFactory.getLogger(IPAddressTableTracker.class);
    
	public static final SnmpObjId IP_ADDRESS_PREFIX_TABLE_ENTRY = SnmpObjId.get(".1.3.6.1.2.1.4.32.1");
    public static final SnmpObjId IP_ADDRESS_TABLE_ENTRY = SnmpObjId.get(".1.3.6.1.2.1.4.34.1");
    
    public static final SnmpObjId IP_ADDRESS_IF_INDEX = SnmpObjId.get(IP_ADDRESS_TABLE_ENTRY, "3");
    public static final SnmpObjId IP_ADDRESS_TYPE_INDEX = SnmpObjId.get(IP_ADDRESS_TABLE_ENTRY, "4");
    public static final SnmpObjId IP_ADDRESS_PREFIX_INDEX = SnmpObjId.get(IP_ADDRESS_TABLE_ENTRY, "5");
    public static final SnmpObjId IP_ADDRESS_PREFIX_ORIGIN_INDEX = SnmpObjId.get(IP_ADDRESS_PREFIX_TABLE_ENTRY, "5");
    public static final SnmpObjId IP_ADDRESS_ORIGIN_INDEX = SnmpObjId.get(IP_ADDRESS_TABLE_ENTRY, "6");
    public static final SnmpObjId IP_ADDRESS_STATUS_INDEX = SnmpObjId.get(IP_ADDRESS_TABLE_ENTRY, "7");
    public static final SnmpObjId IP_ADDRESS_CREATED_INDEX = SnmpObjId.get(IP_ADDRESS_TABLE_ENTRY, "8");
    public static final SnmpObjId IP_ADDRESS_LAST_CHANGED_INDEX = SnmpObjId.get(IP_ADDRESS_TABLE_ENTRY, "9");
    public static final SnmpObjId IP_ADDRESS_ROW_STATUS_INDEX = SnmpObjId.get(IP_ADDRESS_TABLE_ENTRY, "10");
    public static final SnmpObjId IP_ADDRESS_STORAGE_TYPE_INDEX = SnmpObjId.get(IP_ADDRESS_TABLE_ENTRY, "11");
    public static final int TYPE_IPV4  = 1;
    public static final int TYPE_IPV6  = 2;
    public static final int TYPE_IPV4Z = 3;
    public static final int TYPE_IPV6Z = 4;
    public static final int TYPE_DNS   = 16;

    private static final int IP_ADDRESS_TYPE_UNICAST = 1;
    // private static final int IP_ADDRESS_TYPE_ANYCAST = 2;
    // private static final int IP_ADDRESS_TYPE_BROADCAST = 3;

    private static SnmpObjId[] s_tableColumns = new SnmpObjId[] {
        IP_ADDRESS_IF_INDEX,
        IP_ADDRESS_PREFIX_INDEX,
        IP_ADDRESS_TYPE_INDEX
    };

    public static class IPAddressRow extends SnmpRowResult {

        public IPAddressRow(final int columnCount, final SnmpInstId instance) {
            super(columnCount, instance);
            LOG.debug("column count = {}, instance = {}", columnCount, instance);
        }
        
        public Integer getIfIndex() {
        	final SnmpValue value = getValue(IP_ADDRESS_IF_INDEX);
        	return value.toInt();
        }
        
        public String getIpAddress() {
            final SnmpResult result = getResult(IP_ADDRESS_IF_INDEX);
            if (result == null) {
                LOG.warn("BAD AGENT: Device is missing IP-MIB::ipAddressIfIndex. Skipping.");
                return null;
            }

            SnmpInstId instance = result.getInstance();
            final int[] instanceIds = instance.getIds();

            final int addressType = instanceIds[0];
            int addressIndex = 2;
            int addressLength = instanceIds[1];
            // Begin NMS-4906 Lame Force 10 agent!
            LOG.debug("addressType: {}, addressLength: {}, instanceIds.length: {}, instance: {}", addressType, addressLength, instanceIds.length, instance.toString());

            if (addressType == TYPE_IPV4 && instanceIds.length != 6) {
                LOG.warn("IPV4: BAD AGENT: Does not conform to RFC 4001 Section 4.1 Table Indexing!!! Report them immediately.  Making a best guess!");
                if (instanceIds.length == (addressLength + addressIndex)) {
                    // NMS-6452: Some older Brocade Switches represent the IP address as a octet string.
                    try {
                        final InetAddress address = byteStringToInetAddress(instanceIds, addressIndex, addressLength);
                        return str(address);
                    } catch (Exception e) {
                        LOG.debug("IPV4: BAD AGENT: Could not parse raw oids as octet string", e);
                    }
                }
                // Some popular vendors append the ifIndex to the instance value, and argue it is RFC 4001 compliant.
                if (instanceIds[(instanceIds.length - 1)] == getIfIndex() && instanceIds.length > 6) {
                    LOG.debug("IPV4: BAD AGENT: Instance is longer than expected ({}) and last instance value matches the ifIndex ({}), assuming address start on index 2", instanceIds.length, getIfIndex());
                    addressIndex = 2;
                } else {
                    addressIndex = instanceIds.length - 4;
                }
                addressLength = 4;
            }
            if (addressType == TYPE_IPV6 && instanceIds.length != 18) {
                LOG.warn("IPV6: BAD AGENT: Does not conform to RFC 4001 Section 4.1 Table Indexing!!! Report them immediately.  Making a best guess!");
                if (instanceIds.length == (addressLength + addressIndex)) {
                    try {
                        final InetAddress address = byteStringToInetAddress(instanceIds, addressIndex, addressLength);
                        return str(address);
                    } catch (Exception e) {
                        LOG.debug("IPV6: BAD AGENT: Could not parse raw oids as octet string", e);
                    }
                }
                if (instanceIds[(instanceIds.length - 1)] == getIfIndex() && instanceIds.length > 18) {
                    LOG.debug("IPV6: BAD AGENT: Instance is longer than expected ({}) and last instance value matches the ifIndex ({}), assuming address start on index 2", instanceIds.length, getIfIndex());
                    addressIndex = 2;
                } else {
                    addressIndex = instanceIds.length - 16;
                }
                addressLength = 16;
            }
            // End NMS-4906 Lame Force 10 agent!

            // we ignore zones anyways, make sure we truncate to just the address part, since InetAddress doesn't know how to parse zone bytes
            if (addressType == TYPE_IPV4Z) {
                addressLength = 4;
            } else if (addressType == TYPE_IPV6Z) {
                addressLength = 16;
            }

            if (addressIndex < 0 || addressIndex + addressLength > instanceIds.length) {
                LOG.warn("BAD AGENT: Returned instanceId {} does not enough bytes to contain address!. Skipping.", instance);
                return null;
            }

            if (addressType == TYPE_IPV4 || addressType == TYPE_IPV6 || addressType == TYPE_IPV6Z) {
                try {
                    final InetAddress address = getInetAddress(instanceIds, addressIndex, addressLength);
                    return str(address);
                } catch (final IllegalArgumentException e) {
                    LOG.warn("Failed to parse address: {}, index {}, length {}", Arrays.toString(instanceIds), addressIndex, addressLength, e);
                }
            }
            return null;
        }

        private InetAddress byteStringToInetAddress(final int[] rawIds, int offset, int length) {
            final byte[] addressBytes = new byte[length];
            for (int i = 0; i < addressBytes.length; i++) {
                addressBytes[i] = Integer.valueOf(rawIds[i + offset]).byteValue();
            }
            String ipaddr = new String(addressBytes, StandardCharsets.UTF_8);
            return getInetAddress(ipaddr);
        }

        public Integer getType() {
            final SnmpValue value = getValue(IP_ADDRESS_TYPE_INDEX);
            return value.toInt();
        }

        private InetAddress getNetMask() {
            final SnmpValue value = getValue(IP_ADDRESS_PREFIX_INDEX);

            final SnmpInstId netmaskRef = value.toSnmpObjId().getInstance(IP_ADDRESS_PREFIX_ORIGIN_INDEX);

            // See bug NMS-5036
            // {@see http://issues.opennms.org/browse/NMS-5036}
            if (netmaskRef == null) {
                LOG.warn("BAD AGENT: Null netmask instanceId");
                return null;
            }

            final int[] rawIds = netmaskRef.getIds();
            final int addressType = rawIds[1];
            final int mask = rawIds[rawIds.length - 1];
            int addressLength = rawIds[2];
            int addressIndex = 3;

            // Begin NMS-4906 Lame Force 10 agent!
            if (addressType == TYPE_IPV4 && rawIds.length != 1+6+1) {
                LOG.warn("BAD AGENT: Does not conform to RFC 4001 Section 4.1 Table Indexing!!! Report them immediately.  Making a best guess! Length for ipv4 should be 8 but is {}", rawIds.length);
                addressIndex = rawIds.length - (4+1);
                addressLength = 4;
            }
            if (addressType == TYPE_IPV6 && rawIds.length != 1+18+1) {
                LOG.warn("BAD AGENT: Does not conform to RFC 4001 Section 4.1 Table Indexing!!! Report them immediately.  Making a best guess! Length for ipv6 should be 20 but is {}", rawIds.length);
                addressIndex = rawIds.length - (16 + 1);
                addressLength = 16;
            }
            // End NMS-4906 Lame Force 10 agent!

            if (addressIndex < 0 || addressIndex + addressLength > rawIds.length) {
                LOG.warn("BAD AGENT: Returned instanceId {} does not enough bytes to contain address!. Skipping.", netmaskRef);
                return null;
            }

            //final InetAddress address = getInetAddress(rawIds, addressIndex, addressLength);

            if (addressType == TYPE_IPV4) {
                return InetAddressUtils.convertCidrToInetAddressV4(mask);
            } else if (addressType == TYPE_IPV6) {
                return InetAddressUtils.convertCidrToInetAddressV6(mask);
            } else {
                LOG.warn("unknown address type, expected 1 (IPv4) or 2 (IPv6), but got {}", addressType);
                return null;
            }
        }

        public OnmsIpInterface createInterfaceFromRow() {

            final Integer ifIndex = getIfIndex();
            final String ipAddr = getIpAddress();
            final Integer type = getType();
            final InetAddress netMask = getNetMask();

            LOG.debug("createInterfaceFromRow: ifIndex = {}, ipAddress = {}, type = {}, netmask = {}", ifIndex, ipAddr, type, netMask);

            if (type != IP_ADDRESS_TYPE_UNICAST || ipAddr == null) {
                return null;
            }

            final InetAddress inetAddress = InetAddressUtils.addr(ipAddr);
            final OnmsIpInterface iface = new OnmsIpInterface(inetAddress, null);
            iface.setNetMask(netMask);

            if (ifIndex != null) {
                final OnmsSnmpInterface snmpIface = new OnmsSnmpInterface(null, ifIndex);
                snmpIface.setCollectionEnabled(true);
                iface.setSnmpInterface(snmpIface);
                iface.setIfIndex(ifIndex);
            }

            return iface;
        }

        private SnmpResult getResult(final SnmpObjId base) {
            for(final SnmpResult result : getResults()) {
                if (base.equals(result.getBase())) {
                    return result;
                }
            }
            
            return null;
        }

    }
    
    /**
     * <p>Constructor for IPInterfaceTableTracker.</p>
     */
    public IPAddressTableTracker() {
        super(s_tableColumns);
    }

    /**
     * <p>Constructor for IPInterfaceTableTracker.</p>
     *
     * @param rowProcessor a {@link org.opennms.netmgt.snmp.RowCallback} object.
     */
    public IPAddressTableTracker(final RowCallback rowProcessor) {
        super(rowProcessor, s_tableColumns);
    }
    
    /** {@inheritDoc} */
    @Override
    public SnmpRowResult createRowResult(final int columnCount, final SnmpInstId instance) {
        return new IPAddressRow(columnCount, instance);
    }

    /** {@inheritDoc} */
    @Override
    public void rowCompleted(final SnmpRowResult row) {
        processIPAddressRow((IPAddressRow)row);
    }

    /**
     * <p>processIPInterfaceRow</p>
     *
     * @param row a {@link org.opennms.netmgt.provision.service.IPAddressTableTracker.IPAddressRow} object.
     */
    public void processIPAddressRow(final IPAddressRow row) {
        
    }

}
