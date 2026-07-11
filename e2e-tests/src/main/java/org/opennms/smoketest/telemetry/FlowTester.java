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
package org.opennms.smoketest.telemetry;

import static org.awaitility.Awaitility.with;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.opennms.smoketest.utils.ClickHouseRestClient;
import org.opennms.smoketest.utils.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple helper which sends a defined set of {@link FlowPacket}s to OpenNMS or Minion and afterwards verifies
 * the data at the ClickHouse endpoint.
 * <p>
 * Optionally, it can also run verifications before sending flows or check the results at the OpenNMS ReST endpoint as well.
 *
 * @author mvrueden
 */
public class FlowTester {

    public static class Delivery {
        private final FlowPacket packet;
        private final Sender sender;

        public Delivery(final FlowPacket packet, final Sender sender) {
            this.packet = Objects.requireNonNull(packet);
            this.sender = Objects.requireNonNull(sender);
        }

        public void send() throws IOException {
            this.packet.send(this.sender);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(FlowTester.class);

    /** The packets to send */
    private final List<Delivery> deliveries;

    private final List<Consumer<FlowTester>> runBefore = new ArrayList<>();
    private final List<Consumer<FlowTester>> runAfter = new ArrayList<>();

    private final int totalFlowCount;
    private final ClickHouseRestClient clickhouse;

    public FlowTester(InetSocketAddress clickHouseAddress, InetSocketAddress opennmsWebAddress, List<Delivery> deliveries) {
        this.clickhouse = new ClickHouseRestClient(Objects.requireNonNull(clickHouseAddress));
        this.deliveries = Objects.requireNonNull(deliveries);
        this.totalFlowCount = deliveries.stream().mapToInt(delivery -> delivery.packet.getFlowCount()).sum();

        if (totalFlowCount <= 0) {
            throw new IllegalStateException("Cannot verify flow creation/procession, as total flow count is <= 0, but must be > 0");
        }

        if (opennmsWebAddress != null) {
            final RestClient restClient = new RestClient(opennmsWebAddress);

            // No flows should be present
            runBefore.add(flowTester -> assertEquals(Long.valueOf(0L), restClient.getFlowCount(0L, System.currentTimeMillis())));

            // Verify the flow count via the REST API
            runAfter.add(flowTester -> with().pollInterval(5, SECONDS).await().atMost(1, MINUTES)
                    .until(() -> restClient.getFlowCount(0L, System.currentTimeMillis()), equalTo((long) totalFlowCount)));
        }
    }

    public void verifyFlows() throws IOException {
        LOG.info("Verifying flows. Expecting to persist {} flows.", totalFlowCount);

        runBefore.forEach(rb -> rb.accept(this));

        // Send all the packets once
        for (Delivery delivery : deliveries) {
            LOG.info("Sending packet payload from {} containing {} flows to: {}",
                    delivery.packet.getPayload(), delivery.packet.getFlowCount(),
                    delivery.sender);
            delivery.send();
        }

        verify(() -> {
            // Verify directly in ClickHouse that the flows have been created.
            // The ClickHouse flows table has no netflow-version column, so all deliveries collapse to a total row count.
            final long persistedFlowCount = clickhouse.count("SELECT count() FROM flows");
            LOG.info("Found {} of expected {} flows in ClickHouse", persistedFlowCount, totalFlowCount);
            final boolean foundAllFlows = persistedFlowCount >= totalFlowCount;

            if (!foundAllFlows) {
                // If we haven't found them all yet, try sending all the packets again.
                // We do this since the flows are UDP packages and aren't 100% reliable.
                // This test is only concerned that they eventually do make their way into ClickHouse.
                for (Delivery delivery : deliveries) {
                    LOG.info("Sending packet payload from {} containing {} flows to: {}",
                            delivery.packet.getPayload(), delivery.packet.getFlowCount(),
                            delivery.sender);
                    delivery.send();
                }
            }
            return foundAllFlows;
        });

        runAfter.forEach(ra -> ra.accept(this));
    }

    public void setRunBefore(List<Consumer<FlowTester>> runBefore) {
        this.runBefore.clear();
        this.runBefore.addAll(runBefore);
    }

    public void setRunAfter(List<Consumer<FlowTester>> runAfter) {
        this.runAfter.clear();
        this.runAfter.addAll(runAfter);
    }

    public ClickHouseRestClient clickhouse() {
        return clickhouse;
    }

    public interface Block {
        boolean test() throws Exception;
    }

    // Helper method to execute the defined block n-times or fail if not successful
    public static void verify(Block verifyCallback) {
        Objects.requireNonNull(verifyCallback);

        // Verify
        with().pollInterval(5, SECONDS).await().atMost(5, MINUTES).until(() -> {
            try {
                LOG.info("Querying ClickHouse");
                return verifyCallback.test();
            } catch (Exception e) {
                LOG.error("Error while querying ClickHouse", e);
            }
            return false;
        });
    }
}
