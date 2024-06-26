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
package org.opennms.netmgt.alarmd.northbounder.syslog;

import org.graylog2.syslog4j.Syslog;
import org.graylog2.syslog4j.SyslogConfigIF;
import org.graylog2.syslog4j.SyslogConstants;
import org.graylog2.syslog4j.SyslogRuntimeException;
import org.graylog2.syslog4j.impl.net.tcp.TCPNetSyslogConfig;
import org.graylog2.syslog4j.impl.net.udp.UDPNetSyslogConfig;
import org.opennms.netmgt.alarmd.northbounder.syslog.SyslogDestination.SyslogFacility;
import org.opennms.netmgt.alarmd.northbounder.syslog.SyslogDestination.SyslogProtocol;
import org.opennms.netmgt.model.OnmsSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class SyslogUtils {

    /** The Constant LOG. */
    private static final Logger LOG = LoggerFactory.getLogger(SyslogUtils.class);

    /**
     * Creates the northbound instance.
     *
     * @param destination the destination
     * @throws SyslogRuntimeException the syslog runtime exception
     */
    public static void createNorthboundInstance(SyslogDestination destination) throws SyslogRuntimeException {
        LOG.info("Creating Syslog Northbound Instance {}", destination.getName());

        int facility = convertFacility(destination.getFacility());
        SyslogProtocol protocol = destination.getProtocol();
        SyslogConfigIF instanceConfiguration = createConfig(destination, protocol, facility);
        instanceConfiguration.setIdent("OpenNMS");
        instanceConfiguration.setCharSet(destination.getCharSet());
        instanceConfiguration.setMaxMessageLength(destination.getMaxMessageLength());
        instanceConfiguration.setSendLocalName(destination.isSendLocalName());
        instanceConfiguration.setSendLocalTimestamp(destination.isSendLocalTime());
        instanceConfiguration.setTruncateMessage(destination.isTruncateMessage());

        try {
            Syslog.createInstance(destination.getName(), instanceConfiguration);
        } catch (SyslogRuntimeException e) {
            LOG.error("Could not create northbound instance, '{}': {}", destination.getName(), e);
            throw e;
        }
    }

    /**
     * Creates the Syslog configuration object.
     *
     * @param dest the destination
     * @param protocol the protocol
     * @param fac the facility
     * @return the SyslogConfigIf object
     */
    public static SyslogConfigIF createConfig(final SyslogDestination dest, final SyslogProtocol protocol, int fac) {
        SyslogConfigIF config;
        switch (protocol) {
        case UDP:
            config = new UDPNetSyslogConfig(fac, dest.getHost(), dest.getPort());
            break;
        case TCP:
            config = new TCPNetSyslogConfig(fac, dest.getHost(), dest.getPort());
            break;
        default:
            config = new UDPNetSyslogConfig(fac, "localhost", 514);
        }
        return config;
    }

    /**
     * Convert facility.
     *
     * @param facility the facility
     * @return the integer version of the facility
     */
    public static int convertFacility(final SyslogFacility facility) {
        int fac;
        switch (facility) {
        case KERN:
            fac = SyslogConstants.FACILITY_KERN;
            break;
        case USER:
            fac = SyslogConstants.FACILITY_USER;
            break;
        case MAIL:
            fac = SyslogConstants.FACILITY_MAIL;
            break;
        case DAEMON:
            fac = SyslogConstants.FACILITY_DAEMON;
            break;
        case AUTH:
            fac = SyslogConstants.FACILITY_AUTH;
            break;
        case SYSLOG:
            fac = SyslogConstants.FACILITY_SYSLOG;
            break;
        case LPR:
            fac = SyslogConstants.FACILITY_LPR;
            break;
        case NEWS:
            fac = SyslogConstants.FACILITY_NEWS;
            break;
        case UUCP:
            fac = SyslogConstants.FACILITY_UUCP;
            break;
        case CRON:
            fac = SyslogConstants.FACILITY_CRON;
            break;
        case AUTHPRIV:
            fac = SyslogConstants.FACILITY_AUTHPRIV;
            break;
        case FTP:
            fac = SyslogConstants.FACILITY_FTP;
            break;
        case LOCAL0:
            fac = SyslogConstants.FACILITY_LOCAL0;
            break;
        case LOCAL1:
            fac = SyslogConstants.FACILITY_LOCAL1;
            break;
        case LOCAL2:
            fac = SyslogConstants.FACILITY_LOCAL2;
            break;
        case LOCAL3:
            fac = SyslogConstants.FACILITY_LOCAL3;
            break;
        case LOCAL4:
            fac = SyslogConstants.FACILITY_LOCAL4;
            break;
        case LOCAL5:
            fac = SyslogConstants.FACILITY_LOCAL5;
            break;
        case LOCAL6:
            fac = SyslogConstants.FACILITY_LOCAL6;
            break;
        case LOCAL7:
            fac = SyslogConstants.FACILITY_LOCAL7;
            break;
        default:
            fac = SyslogConstants.FACILITY_USER;
        }
        return fac;
    }

    /**
     * Determine log level.
     *
     * @param severity the severity
     * @return the integer version of the severity
     */
    public static int determineLogLevel(final OnmsSeverity severity) {
        int level;
        switch (severity) {
        case CRITICAL:
            level = SyslogConstants.LEVEL_CRITICAL;
            break;
        case MAJOR:
            level = SyslogConstants.LEVEL_ERROR;
            break;
        case MINOR:
            level = SyslogConstants.LEVEL_ERROR;
            break;
        case WARNING:
            level = SyslogConstants.LEVEL_WARN;
            break;
        case NORMAL:
            level = SyslogConstants.LEVEL_NOTICE;
            break;
        case CLEARED:
            level = SyslogConstants.LEVEL_INFO;
            break;
        case INDETERMINATE:
            level = SyslogConstants.LEVEL_DEBUG;
            break;
        default:
            level = SyslogConstants.LEVEL_WARN;
        }
        return level;
    }

}
