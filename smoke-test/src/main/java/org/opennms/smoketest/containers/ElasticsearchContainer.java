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
package org.opennms.smoketest.containers;

import java.net.InetSocketAddress;

import org.opennms.smoketest.utils.TestContainerUtils;
import org.testcontainers.containers.Network;

public class ElasticsearchContainer extends org.testcontainers.elasticsearch.ElasticsearchContainer {

    public ElasticsearchContainer() {
        super("docker.elastic.co/elasticsearch/elasticsearch:7.17.9");
                 withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withEnv("xpack.security.enabled", "false")
                .withNetwork(Network.SHARED)
                .withNetworkAliases(OpenNMSContainer.ELASTIC_ALIAS)
                .withCreateContainerCmdModifier(TestContainerUtils::setGlobalMemAndCpuLimits);
    }

    public InetSocketAddress getRestAddress() {
        return InetSocketAddress.createUnresolved(getContainerIpAddress(), getMappedPort(9200));
    }

    public String getRestAddressString() {
        final InetSocketAddress elasticRestAddress = getRestAddress();
        final String addressString = String.format("http://%s:%d", elasticRestAddress.getHostString(), elasticRestAddress.getPort());
        return addressString;
    }
}
