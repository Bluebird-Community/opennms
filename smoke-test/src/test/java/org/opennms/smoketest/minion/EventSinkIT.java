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
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Date;

import org.opennms.netmgt.model.OnmsEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.opennms.smoketest.junit.SentinelTests;
import org.opennms.smoketest.stacks.IpcStrategy;
import org.opennms.smoketest.stacks.OpenNMSStack;
import org.opennms.smoketest.stacks.StackModel;
import org.opennms.core.criteria.CriteriaBuilder;
import org.opennms.netmgt.dao.api.EventDao;
import org.opennms.netmgt.dao.hibernate.EventDaoHibernate;
import org.opennms.smoketest.utils.DaoUtils;
import org.opennms.smoketest.utils.HibernateDaoFactory;
import org.opennms.smoketest.utils.SshClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Tag("SentinelTests")
public class EventSinkIT {

    private static final Logger LOG = LoggerFactory.getLogger(EventSinkIT.class);

    @RegisterExtension
    public static final OpenNMSStack stack = OpenNMSStack.withModel(StackModel.newBuilder()
            .withMinion()
            .withSentinel()
            .withIpcStrategy(IpcStrategy.KAFKA)
            .build());

    @Test
    public void canReceiveEventsFromMinion() {
        Date startOfTest = new Date();
        assertTrue(sendEventFromMinion(), "failed to send event from Minion");
        HibernateDaoFactory daoFactory = stack.postgres().getDaoFactory();
        EventDao eventDao = daoFactory.getDao(EventDaoHibernate.class);
        final OnmsEvent onmsEvent = await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(10))
                .until(DaoUtils.findMatchingCallable(eventDao,
                        new CriteriaBuilder(OnmsEvent.class).eq("eventUei", "uei.opennms.org/alarms/trigger")
                                .eq("eventSource", "KarafShell_send-event").ge("eventCreateTime", startOfTest)
                                .toCriteria()),
                        notNullValue());

        assertNotNull(onmsEvent, "The event sent is not received at OpenNMS");
    }

    @Test
    public void canReceiveEventsFromSentinel() {
        Date startOfTest = new Date();
        assertTrue(sendEventFromSentinel(), "failed to send event from Sentinel");
        HibernateDaoFactory daoFactory = stack.postgres().getDaoFactory();
        EventDao eventDao = daoFactory.getDao(EventDaoHibernate.class);
        final OnmsEvent onmsEvent = await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(10))
                .until(DaoUtils.findMatchingCallable(eventDao, new CriteriaBuilder(OnmsEvent.class)
                        .eq("eventUei", "uei.opennms.org/threshold/relativeChangeExceeded")
                        .eq("eventSource", "KarafShell_send-event").ge("eventCreateTime", startOfTest).toCriteria()),
                        notNullValue());

        assertNotNull(onmsEvent, "The event sent is not received at OpenNMS");
    }

    private boolean sendEventFromMinion() {
        try (final SshClient sshClient = stack.minion().ssh()) {
            // Issue events:send command
            PrintStream pipe = sshClient.openShell();
            pipe.println("opennms:send-event 'uei.opennms.org/alarms/trigger'");
            pipe.println("logout");

            await().atMost(Duration.ofMinutes(1)).until(sshClient.isShellClosedCallable());
            // Grab the output
            String shellOutput = sshClient.getStdout();
            LOG.info("opennms:send-event output: {}", shellOutput);
            // Verify
            return shellOutput.contains("sent");
        } catch (Exception e) {
            LOG.error("Failed to send event from Minion", e);
        }
        return false;
    }

    private boolean sendEventFromSentinel() {
        try (final SshClient sshClient = stack.sentinel().ssh()) {
            // Issue events:send command
            PrintStream pipe = sshClient.openShell();
            pipe.println("opennms:send-event 'uei.opennms.org/threshold/relativeChangeExceeded'");
            pipe.println("logout");

            await().atMost(Duration.ofMinutes(1)).until(sshClient.isShellClosedCallable());
            // Grab the output
            String shellOutput = sshClient.getStdout();
            LOG.info("opennms:send-event output: {}", shellOutput);
            // Verify
            return shellOutput.contains("sent");
        } catch (Exception e) {
            LOG.error("Failed to send event from Sentinel", e);
        }
        return false;
    }

}
