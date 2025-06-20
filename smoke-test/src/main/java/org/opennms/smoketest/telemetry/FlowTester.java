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
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import io.searchbox.client.JestResult;
import io.searchbox.indices.template.GetTemplate;
import org.opennms.features.elastic.client.DefaultElasticRestClient;
import org.opennms.netmgt.flows.elastic.NetflowVersion;
import org.opennms.features.jest.client.SearchResultUtils;
import org.opennms.smoketest.utils.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import io.searchbox.client.JestClient;
import io.searchbox.client.JestClientFactory;
import io.searchbox.client.config.HttpClientConfig;
import io.searchbox.core.Search;
import io.searchbox.core.SearchResult;

/**
 * Simple helper which sends a defined set of {@link FlowPacket}s to OpenNMS or Minion and afterwards verifies
 * the data at the elastic endpoints.
 *
 * Optionally it can also run verifications before sending flows or check the results at the OpenNMS ReST endpoint as well.
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

    private static final String TEMPLATE_NAME = "netflow";

    private static Logger LOG = LoggerFactory.getLogger(FlowTester.class);

    private static final Gson gson = new Gson();

    /** The packets to send */
    private final List<Delivery> deliveries;

    private final List<Consumer<FlowTester>> runBefore = new ArrayList<>();
    private final List<Consumer<FlowTester>> runAfter = new ArrayList<>();

    private final InetSocketAddress elasticRestAddress;
    private final int totalFlowCount;
    private DefaultElasticRestClient elasticRestClient;

    private JestClient client;

    public FlowTester(InetSocketAddress elasticAddress, InetSocketAddress opennmsWebAddress, List<Delivery> deliveries) {
        this.elasticRestAddress = Objects.requireNonNull(elasticAddress);
        this.deliveries = Objects.requireNonNull(deliveries);
        this.totalFlowCount = deliveries.stream().mapToInt(delivery -> delivery.packet.getFlowCount()).sum();

        if (totalFlowCount <= 0) {
            throw new IllegalStateException("Cannot verify flow creation/procession, as total flow count is <= 0, but must be > 0");
        }

        if (opennmsWebAddress != null) {
            final RestClient restclient = new RestClient(opennmsWebAddress);

            // No flows should be present
            runBefore.add(flowTester -> assertEquals(Long.valueOf(0L), restclient.getFlowCount(0L, System.currentTimeMillis())));


            // Verify the flow count via the REST API
            runAfter.add(flowTester -> {
                with().pollInterval(15, SECONDS).await().atMost(1, MINUTES)
                    .until(() -> restclient.getFlowCount(0L, System.currentTimeMillis()), equalTo((long) totalFlowCount));
            });
        }


        if (opennmsWebAddress != null) {
            final RestClient restClient = new RestClient(opennmsWebAddress);

            // No flows should be present
            runBefore.add((flowTester) -> assertEquals(Long.valueOf(0L), restClient.getFlowCount(0L, System.currentTimeMillis())));


            // Verify the flow count via the REST API
            runAfter.add((flowTester) -> with().pollInterval(15, SECONDS).await().atMost(1, MINUTES)
                                     .until(() -> restClient.getFlowCount(0L, System.currentTimeMillis()), equalTo((long) totalFlowCount)));
        }
    }

    public void verifyFlows() throws IOException {
        final String elasticRestUrl = String.format("http://%s:%d", elasticRestAddress.getHostString(), elasticRestAddress.getPort());

        // Build the Elastic Rest Client
        final JestClientFactory factory = new JestClientFactory();
        factory.setHttpClientConfig(new HttpClientConfig.Builder(elasticRestUrl)
                .connTimeout(5000)
                .readTimeout(10000)
                .multiThreaded(true).build());

        elasticRestClient = new DefaultElasticRestClient(elasticRestUrl, null, null);
        try {
            client = factory.getObject();
            runBefore.forEach(rb -> rb.accept(this));

            // Group the packets by protocol
            final Map<NetflowVersion, List<Delivery>> delivieriesByProtocol = deliveries.stream()
                                                                                        .collect(Collectors.groupingBy(delivery -> delivery.packet.getNetflowVersion()));
            LOG.info("Verifying flows. Expecting to persist {} flows across protocols: {}",
                    totalFlowCount, delivieriesByProtocol.keySet());

            // Send all the packets once
            for (Delivery delivery : deliveries) {
                LOG.info("Sending packet payload from {} containing {} flows to: {}",
                        delivery.packet.getPayload(), delivery.packet.getFlowCount(),
                        delivery.sender);
                delivery.send();
            }

            for (NetflowVersion netflowVersion : delivieriesByProtocol.keySet()) {
                final List<Delivery> deliveriesForProtocol = delivieriesByProtocol.get(netflowVersion);
                final int numFlowsExpected = deliveriesForProtocol.stream().mapToInt(delivery -> delivery.packet.getFlowCount()).sum();

                LOG.info("Verifying flows for {}", netflowVersion);
                verify(() -> {
                    // Verify directly in Elasticsearch that the flows have been created
                    final String query = "{\"query\":{\"term\":{\"netflow.version\":{\"value\":"
                            + gson.toJson(netflowVersion)
                            + "}}}}";
                    LOG.info("Executing query: {}", query);
                    final SearchResult response = client.execute(new Search.Builder(query)
                            .addIndex("netflow-*")
                            .build());
                    LOG.info("Response {} with {} flow documents: {}", response.isSucceeded() ? "successful" : "failed", SearchResultUtils.getTotal(response), response.getJsonString());
                    final boolean foundAllFlowsForProtocol = response.isSucceeded() && SearchResultUtils.getTotal(response) >= numFlowsExpected;

                    if (!foundAllFlowsForProtocol) {
                        // If we haven't found them all yet, try sending all the packets for this protocol again.
                        // We do this since the flows are UDP packages and aren't 100% reliable.
                        // This test is only concerned that they eventually do make their way into ES.
                        for (Delivery delivery : deliveriesForProtocol) {
                            LOG.info("Sending packet payload from {} containing {} flows to: {}",
                                    delivery.packet.getPayload(), delivery.packet.getFlowCount(),
                                    delivery.sender);
                            delivery.send();
                        }
                    }
                    return foundAllFlowsForProtocol;
                });
            }

            LOG.info("Ensuring that the index template was created...");
            verify(() -> {
                Map<String, String> indexTemplates = elasticRestClient.listTemplates();
                boolean hasIndexTemplate = indexTemplates.keySet().stream()
                        .anyMatch(name -> name.contains(TEMPLATE_NAME));
                if (!hasIndexTemplate) {
                    final JestResult result = client.execute(new GetTemplate.Builder(TEMPLATE_NAME).build());
                    return result.isSucceeded() && result.getJsonObject().get(TEMPLATE_NAME) != null;
                } else {
                    return true;
                }
            });

            runAfter.forEach(ra -> ra.accept(this));
        } finally {
            if (client != null) {
                client.close();
            }
            if (elasticRestClient != null) {
                elasticRestClient.close();
            }
        }
    }

    public void setRunBefore(List<Consumer<FlowTester>> runBefore) {
        this.runBefore.clear();
        this.runBefore.addAll(runBefore);
    }

    public void setRunAfter(List<Consumer<FlowTester>> runAfter) {
        this.runAfter.clear();
        this.runAfter.addAll(runAfter);
    }

    public JestClient getJestClient() {
        return Objects.requireNonNull(this.client);
    }

    public interface Block {
        boolean test() throws Exception;
    }

    // Helper method to execute the defined block n-times or fail if not successful
    public static void verify(Block verifyCallback) {
        Objects.requireNonNull(verifyCallback);

        // Verify
        with().pollInterval(15, SECONDS).await().atMost(5, MINUTES).until(() -> {
            try {
                LOG.info("Querying elastic search");
                return verifyCallback.test();
            } catch (Exception e) {
                LOG.error("Error while querying to elastic search", e);
            }
            return false;
        });
    }
}
