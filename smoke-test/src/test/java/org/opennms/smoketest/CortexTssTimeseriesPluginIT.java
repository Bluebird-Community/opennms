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
package org.opennms.smoketest;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.opennms.smoketest.stacks.OpenNMSStack;
import org.opennms.smoketest.utils.KarafShell;
import org.opennms.smoketest.utils.KarafShellUtils;

@Tag("FlakyTests")
public class CortexTssTimeseriesPluginIT {
    @RegisterExtension
    public static OpenNMSStack stack = OpenNMSStack.minimal(
            b -> b.withInstallFeature("opennms-timeseries-api"),
            b -> b.withInstallFeature("opennms-plugins-cortex-tss", "opennms-cortex-tss-plugin"));

    protected KarafShell karafShell = new KarafShell(stack.opennms().getSshAddress());

    @BeforeEach
    public void setUp() throws IOException, InterruptedException {
        // Make sure the Karaf shell is healthy before we start
        KarafShellUtils.awaitHealthCheckSucceeded(stack.opennms());
    }

    @Test
    public void everythingHappy() throws Exception {
        karafShell.checkFeature("opennms-timeseries-api", "Started|Uninstalled", Duration.ofSeconds(30)); // NMS-15329
        karafShell.checkFeature("opennms-plugins-cortex-tss", "Started", Duration.ofSeconds(30));
    }
}
