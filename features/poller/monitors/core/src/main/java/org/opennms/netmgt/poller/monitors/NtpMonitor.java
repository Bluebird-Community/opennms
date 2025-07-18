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
package org.opennms.netmgt.poller.monitors;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.util.Map;

import org.opennms.core.utils.ParameterMap;
import org.opennms.core.utils.TimeoutTracker;
import org.opennms.netmgt.poller.MonitoredService;
import org.opennms.netmgt.poller.PollStatus;
import org.opennms.netmgt.poller.monitors.support.NtpMessage;
import org.opennms.netmgt.poller.support.AbstractServiceMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <P>
 * This class is designed to be used by the service poller framework to test the
 * availability of the NTP service on remote interfaces. The class implements
 * the ServiceMonitor interface that allows it to be used along with other
 * plug-ins by the service poller framework.
 * </P>
 *
 * @author <A HREF="mailto:tarus@opennms.org">Tarus Balog </A>
 * @author <A HREF="http://www.opennms.org/">OpenNMS </A>
 */
final public class NtpMonitor extends AbstractServiceMonitor {
    private static final Logger LOG = LoggerFactory.getLogger(NtpMonitor.class);
    /**
     * Default NTP port.
     */
    private static final int DEFAULT_PORT = 123;

    /**
     * Default retries.
     */
    private static final int DEFAULT_RETRY = 0;

    /**
     * Default timeout. Specifies how long (in milliseconds) to block waiting
     * for data from the monitored interface.
     */
    private static final int DEFAULT_TIMEOUT = 5000;

    /**
     * {@inheritDoc}
     *
     * <P>
     * Poll the specified address for NTP service availability.
     * </P>
     *
     * <P>
     * During the poll an NTP request query packet is generated. The query is
     * sent via UDP socket to the interface at the specified port (by default
     * UDP port 123). If a response is received, it is parsed and validated. If
     * the NTP was successful the service status is set to SERVICE_AVAILABLE and
     * the method returns.
     * </P>
     */
    @Override
    public PollStatus poll(MonitoredService svc, Map<String, Object> parameters) {
        // get the parameters
        //
        TimeoutTracker tracker = new TimeoutTracker(parameters, DEFAULT_RETRY, DEFAULT_TIMEOUT);

        int port = ParameterMap.getKeyedInteger(parameters, "port", DEFAULT_PORT);

        // get the address and NTP address request
        //
        InetAddress ipAddr = svc.getAddress();

        final String noResponseReason = "The monitor did not receive a response packet within the specified timeout (" + tracker.getTimeoutInMillis() + " ms)";

        PollStatus serviceStatus = PollStatus.unavailable(noResponseReason);
        DatagramSocket socket = null;
        double responseTime = -1.0;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(tracker.getSoTimeout()); // will force the
            // InterruptedIOException

            for (tracker.reset(); tracker.shouldRetry() && !serviceStatus.isAvailable(); tracker.nextAttempt()) {
                try {
                    // Send NTP request
                    //
                    byte[] data = new NtpMessage().toByteArray();
                    DatagramPacket outgoing = new DatagramPacket(data, data.length, ipAddr, port);

                    tracker.startAttempt();

                    socket.send(outgoing);

                    // Get NTP Response
                    //
                    // byte[] buffer = new byte[512];
                    DatagramPacket incoming = new DatagramPacket(data, data.length);
                    socket.receive(incoming);
                    responseTime = tracker.elapsedTimeInMillis();
                    double destinationTimestamp = (System.currentTimeMillis() / 1000.0) + 2208988800.0;

                    // Validate NTP Response
                    // IOException thrown if packet does not decode as expected.
                    NtpMessage msg = new NtpMessage(incoming.getData());
                    double localClockOffset = ((msg.receiveTimestamp - msg.originateTimestamp) + (msg.transmitTimestamp - destinationTimestamp)) / 2;


                    LOG.debug("poll: valid NTP request received the local clock offset is {}, responseTime= {}ms", localClockOffset, responseTime);
                    LOG.debug("poll: NTP message : {}", msg);
                    serviceStatus = PollStatus.available(responseTime);
                } catch (InterruptedIOException ex) {
                    // Ignore, no response received.
                }
            }

            LOG.debug(noResponseReason);
        } catch (NoRouteToHostException e) {
        	String reason = "No route to host exception for address: " + ipAddr;
            LOG.debug(reason, e);
            serviceStatus = PollStatus.unavailable(reason);
        } catch (ConnectException e) {
        	String reason = "Connection exception for address: " + ipAddr;
            LOG.debug(reason, e);
            serviceStatus = PollStatus.unavailable(reason);
        } catch (IOException e) {
        	String reason = "IOException while polling address: " + ipAddr;
            LOG.debug(reason, e);
            serviceStatus = PollStatus.unavailable(reason);
        } finally {
            if (socket != null)
                socket.close();
        }

        // 
        //
        // return the status of the service
        //
        return serviceStatus;
    }

}
