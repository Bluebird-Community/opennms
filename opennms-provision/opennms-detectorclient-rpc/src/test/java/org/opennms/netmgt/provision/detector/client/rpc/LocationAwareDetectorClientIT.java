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
package org.opennms.netmgt.provision.detector.client.rpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import org.apache.commons.beanutils.BeanUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opennms.core.test.OpenNMSJUnit4ClassRunner;
import org.opennms.netmgt.provision.LocationAwareDetectorClient;
import org.opennms.netmgt.provision.ServiceDetector;
import org.opennms.netmgt.provision.ServiceDetectorFactory;
import org.opennms.netmgt.provision.detector.loop.LoopDetector;
import org.opennms.netmgt.provision.detector.registry.api.ServiceDetectorRegistry;
import org.opennms.test.JUnitConfigurationEnvironment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;

/**
 * Exercises the {@link LocationAwareDetectorClient} against an in-process (broker-free)
 * RPC client. Requests to a remote (Minion) location over a transport are covered by the
 * gRPC/Kafka IPC integration tests; here the mock RPC client runs the detector locally.
 */
@RunWith(OpenNMSJUnit4ClassRunner.class)
@ContextConfiguration(locations={
        "classpath:/META-INF/opennms/applicationContext-soa.xml",
        "classpath*:/META-INF/opennms/detectors.xml",
        "classpath:/META-INF/opennms/applicationContext-mockDao.xml",
        "classpath:/META-INF/opennms/applicationContext-scan-executor.xml",
        "classpath:/META-INF/opennms/applicationContext-rpc-client-mock.xml",
        "classpath:/META-INF/opennms/applicationContext-rpc-detect.xml",
        "classpath:/META-INF/opennms/applicationContext-tracer-registry.xml"
})
@JUnitConfigurationEnvironment
@org.springframework.test.annotation.IfProfileValue(name="runFlappers", value="true")
public class LocationAwareDetectorClientIT {

    @Autowired
    private LocationAwareDetectorClient locationAwareDetectorClient;

    @Autowired
    private DetectorClientRpcModule detectorClientRpcModule;

    @Autowired
    private ServiceDetectorRegistry serviceDetectorRegistry;

    @Before
    public void setUp() throws Exception {
        detectorClientRpcModule.setExecutor(Executors.newSingleThreadExecutor());
    }

    /**
     * Verifies that a detector can be invoked using the current (local) location.
     */
    @Test(timeout=60000)
    public void canDetectViaCurrentLocation() throws InterruptedException, ExecutionException, UnknownHostException {
        boolean isDetected = locationAwareDetectorClient.detect()
                .withClassName(LoopDetector.class.getCanonicalName())
                .withAddress(InetAddress.getByName("127.0.0.1"))
                .withAttribute("ipMatch", "127.0.0.*")
                .execute().get();
        assertEquals(true, isDetected);
    }

    /**
     * Verifies detection results (success, failure, and error propagation) through the
     * location-aware client. With the in-process RPC client the detector runs locally.
     */
    @Test(timeout=60000)
    public void canDetect() throws Exception {
        // Was detected
        boolean isDetected = locationAwareDetectorClient.detect()
                .withClassName(LoopDetector.class.getCanonicalName())
                .withAddress(InetAddress.getByName("127.0.0.1"))
                .withAttribute("ipMatch", "127.0.0.*")
                .execute().get();
        assertEquals(true, isDetected);

        // Was not detected
        isDetected = locationAwareDetectorClient.detect()
                .withClassName(LoopDetector.class.getCanonicalName())
                .withAddress(InetAddress.getByName("10.0.1.10"))
                .withAttribute("ipMatch", "127.0.0.*")
                .execute().get();
        assertEquals(false, isDetected);

        // Error on detection with synchronous detector
        try {
            locationAwareDetectorClient.detect()
                .withClassName(ExceptionalSyncServiceDetector.class.getCanonicalName())
                .withAddress(InetAddress.getLoopbackAddress())
                .execute().get();
            fail("Exception was not thrown.");
        } catch (ExecutionException e) {
            final String message = e.getCause().getMessage();
            assertTrue(message, message.contains("Failure on sync detection."));
        }

        // Error on detection with asynchronous detector
        try {
            locationAwareDetectorClient.detect()
                .withClassName(ExceptionalAsyncServiceDetector.class.getCanonicalName())
                .withAddress(InetAddress.getLoopbackAddress())
                .execute().get();
            fail("Exception was not thrown.");
        } catch (ExecutionException e) {
            final String message = e.getCause().getMessage();
            assertTrue(message, message.contains("Failure on async detection."));
        }
    }

    @Test
    public void testNMS16360() throws Exception {
        for(final String service : serviceDetectorRegistry.getServiceNames()) {
            final String classname = serviceDetectorRegistry.getDetectorClassNameFromServiceName(service);
            final ServiceDetectorFactory<?> factory = serviceDetectorRegistry.getDetectorFactoryByClassName(classname);
            final ServiceDetector detector = factory.createDetector(Collections.emptyMap());
            if (detector instanceof ExceptionalSyncServiceDetector || detector instanceof ExceptionalAsyncServiceDetector) {
                continue;
            }
            BeanUtils.setProperty(detector, "collection", "foobar");
            assertEquals("foobar", BeanUtils.getProperty(detector, "collection"));
        }
    }
}
