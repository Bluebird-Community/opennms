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
package org.opennms.smoketest.minion;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.opennms.smoketest.minion.RpcOverKafkaIT.addRequisition;

import java.time.Duration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.opennms.smoketest.junit.MinionTests;
import org.opennms.smoketest.stacks.IpcStrategy;
import org.opennms.smoketest.stacks.OpenNMSStack;
import org.opennms.smoketest.stacks.StackModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Tag("MinionTests")
public class IpcOverGrpcIT {

    private static final Logger LOG = LoggerFactory.getLogger(IpcOverGrpcIT.class);

    @RegisterExtension
    public static final OpenNMSStack stack = OpenNMSStack.withModel(StackModel.newBuilder()
            .withMinion()
            .withIpcStrategy(IpcStrategy.GRPC)
            .build());

    private static final String LOCALHOST = "127.0.0.1";

    @Test
    public void verifyGrpcRpcWithTcpServiceDetection() {
        // Add node and interface with minion location.
        addRequisition(stack.opennms().getRestClient(), stack.minion().getLocation(), LOCALHOST);
        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(15))
                .until(() -> RpcOverKafkaIT.detectTcpAtLocationMinion(stack),
                        containsString("'TCP' WAS detected on 127.0.0.1"));
    }

}
