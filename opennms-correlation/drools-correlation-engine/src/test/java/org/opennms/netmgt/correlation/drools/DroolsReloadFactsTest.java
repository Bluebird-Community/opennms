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
package org.opennms.netmgt.correlation.drools;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.hamcrest.Matchers;
import org.junit.Test;
import org.opennms.netmgt.dao.mock.MockEventIpcManager;
import org.opennms.netmgt.events.api.EventConstants;
import org.opennms.netmgt.model.events.EventBuilder;
import org.opennms.netmgt.xml.event.Event;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import com.codahale.metrics.MetricRegistry;

public class DroolsReloadFactsTest {

    static File DROOLS_SRC = new File("src/test/opennms-home/etc/drools-engine.d/droolsFusion/DroolsFusion.drl");

    /** Facts the DroolsFusion.drl rules put in working memory for one nodeLostService event. */
    private static final int EXPECTED_FACTS = 4;

    /**
     * Wait until working memory actually holds the facts we are about to assert on.
     *
     * The engine runs in stream mode, so initialize() starts a thread running
     * fireUntilHalt() that rewrites working memory in the background: measured locally at
     * 5 objects immediately after a restore and 9 once the rules have re-fired against the
     * restored facts. saveFacts() snapshots whatever happens to be there at that instant,
     * so without this wait the assertion samples a moving target. CI observed 2.
     *
     * Polling getObjects() on a session that is being fired can throw, so transient
     * exceptions are ignored rather than failing the test.
     */
    private static void awaitFactsSettled(DroolsCorrelationEngine engine) {
        await().ignoreExceptions()
               .atMost(30, TimeUnit.SECONDS)
               .until(() -> engine.getKieSessionObjects().size(),
                      Matchers.greaterThanOrEqualTo(EXPECTED_FACTS));
    }

    @Test
    public void verifySaveFacts() throws Exception {

        DroolsCorrelationEngine droolsCorrelationEngine = new DroolsCorrelationEngine("droolsFusion", new MetricRegistry(), new FileSystemResource(DROOLS_SRC), null);
        List<Resource> resources = new ArrayList<>();
        resources.add(new FileSystemResource(DROOLS_SRC));
        droolsCorrelationEngine.setRulesResources(resources);
        droolsCorrelationEngine.setAssertBehaviour("identity");
        droolsCorrelationEngine.setEventProcessingMode("stream");
        MockEventIpcManager eventIpcManager = new MockEventIpcManager();
        droolsCorrelationEngine.setEventIpcManager(eventIpcManager);
        droolsCorrelationEngine.initialize();
        // Correlate with node lost event.
        Event event = new EventBuilder(EventConstants.NODE_LOST_SERVICE_EVENT_UEI, "ICMP")
                .setNodeid(1)
                .getEvent();
        droolsCorrelationEngine.correlate(event);
        // Expect node up event.
        await().atMost(15, TimeUnit.SECONDS).until(() -> eventIpcManager.getEventAnticipator().getUnanticipatedEvents().size(), Matchers.greaterThanOrEqualTo(1));
        // The node up event only tells us the first rule fired. Wait for the facts
        // themselves before snapshotting them, because saveFacts() is a one-shot read: it
        // shuts the session down, so it cannot be polled.
        awaitFactsSettled(droolsCorrelationEngine);
        droolsCorrelationEngine.saveFacts();
        Map<byte[], Class<?>> factObjects = droolsCorrelationEngine.getFactObjects();
        assertThat(factObjects.size(), Matchers.greaterThanOrEqualTo(EXPECTED_FACTS));
        // Now initialize again. This restores the saved facts and restarts fireUntilHalt().
        droolsCorrelationEngine.initialize();
        // initialize() clears factObjects after re-inserting them, so this is deterministic.
        factObjects = droolsCorrelationEngine.getFactObjects();
        assertThat(factObjects.size(), Matchers.is(0));
        // Save facts from engine and verify that all saved facts are loaded properly.
        awaitFactsSettled(droolsCorrelationEngine);
        droolsCorrelationEngine.saveFacts();
        factObjects = droolsCorrelationEngine.getFactObjects();
        assertThat(factObjects.size(), Matchers.greaterThanOrEqualTo(EXPECTED_FACTS));

    }
}
