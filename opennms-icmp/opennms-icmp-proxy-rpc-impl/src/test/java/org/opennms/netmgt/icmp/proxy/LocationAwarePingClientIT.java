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
package org.opennms.netmgt.icmp.proxy;

import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opennms.core.spring.BeanUtils;
import org.opennms.core.test.OpenNMSJUnit4ClassRunner;
import org.opennms.test.JUnitConfigurationEnvironment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;

/**
 * Exercises the {@link LocationAwarePingClient} against an in-process (broker-free) RPC
 * client. Requests to a remote (Minion) location over a transport are covered by the
 * gRPC/Kafka IPC integration tests; here the mock RPC client runs the pinger locally.
 */
@RunWith(OpenNMSJUnit4ClassRunner.class)
@ContextConfiguration(locations={
        "classpath:/META-INF/opennms/applicationContext-soa.xml",
        "classpath:/META-INF/opennms/applicationContext-mockDao.xml",
        "classpath:/META-INF/opennms/applicationContext-rpc-client-mock.xml",
        "classpath:/META-INF/opennms/applicationContext-rpc-icmp.xml",
        "classpath:/META-INF/opennms/applicationContext-tracer-registry.xml",
        "classpath:/pinger.xml"
})
@JUnitConfigurationEnvironment(
        systemProperties = {"org.opennms.netmgt.icmp.pingerClass=org.opennms.netmgt.icmp.TestPinger"}
)
public class LocationAwarePingClientIT {

    private static final String REMOTE_LOCATION_NAME = "remote";

    @Autowired
    private LocationAwarePingClient locationAwarePingClient;

    @Before
    public void setUp() throws Exception {
        BeanUtils.assertAutowiring(this);
    }

    /**
     * Verifies that Pings are successful when invoked from localhost (JVM mode)
     */
    @Test
    public void canPingViaLocalhost() throws InterruptedException, ExecutionException {
        CompletableFuture<PingSummary> future = locationAwarePingClient.ping(InetAddress.getLoopbackAddress()).execute();
        PingSummary pingSummary = future.get();
        verify(pingSummary, 1);
    }

    /**
     * Verifies that Pings are successful when invoked with a location (executed in-process
     * by the mock RPC client).
     */
    @Test
    public void canPingViaRemoteLocation() throws InterruptedException, ExecutionException {
        final CompletableFuture<PingSummary> future = locationAwarePingClient.ping(
                InetAddress.getLoopbackAddress()).withLocation(REMOTE_LOCATION_NAME).execute();
        PingSummary pingSummary = future.get();
        verify(pingSummary, 1);
    }

    @Test
    public void canMultiPingViaRemoteLocation() throws ExecutionException, InterruptedException {
        final Integer[] invoked = new Integer[]{0};
        verify(locationAwarePingClient.ping(InetAddress.getLoopbackAddress())
                .withLocation(REMOTE_LOCATION_NAME)
                .withNumberOfRequests(10)
                .withProgressCallback((newSequence, summary) -> {
                    invoked[0]++;
                }).execute().get(), 10);
        // Since the default retries are 2 for every invocation
        Assert.assertEquals(Integer.valueOf(20), invoked[0]);
    }

    private void verify(PingSummary pingSummary, int numberSequences) {
        Assert.assertEquals(Boolean.TRUE, pingSummary.isSuccess());
        Assert.assertEquals(Boolean.TRUE, pingSummary.isComplete());
        Assert.assertEquals(numberSequences, pingSummary.getSequences().size());
        pingSummary.getSequences().forEach(eachSequence -> {
            Assert.assertTrue(eachSequence.getResponse().getRtt() != Double.POSITIVE_INFINITY);
        });
    }
}
