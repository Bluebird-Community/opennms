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
package org.opennms.smoketest.sentinel;

import java.net.InetSocketAddress;

import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.opennms.smoketest.junit.SentinelTests;
import org.opennms.smoketest.stacks.IpcStrategy;
import org.opennms.smoketest.stacks.KafkaCompressionStrategy;
import org.opennms.smoketest.stacks.NetworkProtocol;
import org.opennms.smoketest.stacks.OpenNMSStack;
import org.opennms.smoketest.stacks.StackModel;
import org.opennms.smoketest.telemetry.FlowTestBuilder;
import org.opennms.smoketest.telemetry.FlowTester;
import org.opennms.smoketest.telemetry.Packets;
import org.opennms.smoketest.telemetry.Sender;

@Category(SentinelTests.class)
public class KafkaCompressionZSTDFlowIT {

    @ClassRule
    public static final OpenNMSStack stack = OpenNMSStack.withModel(StackModel.newBuilder()
            .withMinion()
            .withSentinel()
            .withTelemetryProcessing()
            .withIpcStrategy(IpcStrategy.KAFKA)
            .withKafkaCompressionStrategy(KafkaCompressionStrategy.ZSTD)
            .build());

    @Test
    public void verifySinglePort() throws Exception {
        // Determine endpoints
        final InetSocketAddress clickHouseAddress = stack.clickhouse().getRestAddress();
        final InetSocketAddress flowTelemetryAddress = stack.minion().getNetworkProtocolAddress(NetworkProtocol.FLOWS);

        // Now verify Flow creation
        final FlowTester tester = new FlowTestBuilder()
                .withFlowPackets(Packets.getFlowPackets(), Sender.udp(flowTelemetryAddress))
                .verifyBeforeSendingFlows((flowTester) -> flowTester.clickhouse().execute("TRUNCATE TABLE flows"))
                .build(clickHouseAddress);
        tester.verifyFlows();
    }

}
