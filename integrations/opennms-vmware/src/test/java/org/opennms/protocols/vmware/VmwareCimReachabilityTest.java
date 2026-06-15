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
package org.opennms.protocols.vmware;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.net.ServerSocket;

import org.junit.Test;

import com.vmware.vim25.HostServiceTicket;
import com.vmware.vim25.mo.HostSystem;

/**
 * Verifies that {@link VmwareViJavaAccess#checkCimReachable(String, int)} bounds the
 * connection to a host's CIM service so that an unreachable or firewalled CIM port
 * fails fast instead of blocking the calling thread.
 */
public class VmwareCimReachabilityTest {

    private final VmwareViJavaAccess access = new VmwareViJavaAccess("host", "user", "pass");

    /**
     * A closed local port should be rejected (connection refused) promptly.
     */
    @Test
    public void failsWhenConnectionRefused() throws Exception {
        final int closedPort;
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            closedPort = serverSocket.getLocalPort();
        }
        // The server socket is now closed, so nothing is listening on closedPort.

        final long start = System.currentTimeMillis();
        try {
            access.checkCimReachable("https://127.0.0.1:" + closedPort, 5000);
            fail("expected a ConnectException for a closed CIM port");
        } catch (ConnectException expected) {
            final long elapsed = System.currentTimeMillis() - start;
            assertTrue("connection refused should be reported promptly, took " + elapsed + " ms", elapsed < 5000);
        }
    }

    /**
     * An unroutable host (RFC 5737 TEST-NET-1) must not block longer than the configured
     * timeout -- the SBLIM client would otherwise block for the OS connect timeout.
     */
    @Test
    public void boundsConnectForUnreachableHost() {
        final int timeout = 1000;
        final long start = System.currentTimeMillis();
        try {
            access.checkCimReachable("https://192.0.2.1:5989", timeout);
            fail("expected a ConnectException for an unreachable CIM host");
        } catch (ConnectException expected) {
            final long elapsed = System.currentTimeMillis() - start;
            assertTrue("connect should be bounded by the timeout, took " + elapsed + " ms",
                    elapsed < timeout + 4000);
        }
    }

    /**
     * A malformed agent address is left for the CIM client to surface (no exception here).
     */
    @Test
    public void ignoresMalformedAddress() throws Exception {
        access.checkCimReachable("not-a-url", 1000);
    }

    /**
     * Exercises the full {@code queryCimObjects} path with a mocked vCenter ticket (as a real
     * vCenter would mint) but an unreachable host CIM port. The query must fail within
     * cimTimeout rather than blocking on the SBLIM client's unbounded connect, and it must
     * not reach the CIM client at all.
     */
    @Test
    public void queryCimObjectsBoundsUnreachableCimConnect() throws Exception {
        access.setCimTimeout(800);

        final HostServiceTicket ticket = new HostServiceTicket();
        ticket.setSessionId("sessionId");
        final HostSystem hostSystem = mock(HostSystem.class);
        when(hostSystem.acquireCimServicesTicket()).thenReturn(ticket);

        final long start = System.currentTimeMillis();
        try {
            // 192.0.2.1 is RFC 5737 TEST-NET-1 (unroutable).
            access.queryCimObjects(hostSystem, "CIM_NumericSensor", "192.0.2.1");
            fail("expected a ConnectException for an unreachable CIM host");
        } catch (ConnectException expected) {
            final long elapsed = System.currentTimeMillis() - start;
            assertTrue("queryCimObjects should be bounded by cimTimeout, took " + elapsed + " ms",
                    elapsed < 5000);
        }
    }

    /**
     * Each call with an explicit address must probe that address, not a value cached from an
     * earlier call. The importer relies on this to try each of a host's interface addresses
     * in turn; caching the first one would wrongly mark a host with a reachable secondary
     * address as unreachable.
     */
    @Test
    public void queryCimObjectsUsesEachExplicitAddress() throws Exception {
        access.setCimTimeout(800);
        final HostServiceTicket ticket = new HostServiceTicket();
        ticket.setSessionId("sessionId");
        final HostSystem hostSystem = mock(HostSystem.class);
        when(hostSystem.acquireCimServicesTicket()).thenReturn(ticket);

        try {
            access.queryCimObjects(hostSystem, "CIM_NumericSensor", "192.0.2.1");
            fail("expected a ConnectException");
        } catch (ConnectException expected) {
            assertTrue("first probe should report 192.0.2.1: " + expected.getMessage(),
                    expected.getMessage().contains("192.0.2.1"));
        }

        try {
            access.queryCimObjects(hostSystem, "CIM_NumericSensor", "192.0.2.2");
            fail("expected a ConnectException");
        } catch (ConnectException expected) {
            assertTrue("second probe must use the supplied address, not a cached one: " + expected.getMessage(),
                    expected.getMessage().contains("192.0.2.2"));
        }
    }
}
