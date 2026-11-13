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

import org.junit.Assert;
import org.junit.Test;
import org.opennms.smoketest.ui.NodeDetailsPage;
import org.openqa.selenium.By;
import org.openqa.selenium.support.ui.ExpectedConditions;

/**
 * The Vue Node Details page. The legacy element/node.jsp is now a redirect to it (NMS-20441).
 */
public class NodeDetailPageIT extends OpenNMSSeleniumIT {

    @Test
    public void verifyTimeline() throws Exception {
        try {
            final String node = "<node type=\"A\" label=\"myNode\" foreignSource=\"smoketests\" foreignId=\"nodeForeignId\">" +
                    "<labelSource>H</labelSource><sysContact>Ulf</sysContact><sysDescription>Test System</sysDescription><sysLocation>Fulda</sysLocation>" +
                    "<sysName>myNode</sysName><sysObjectId>.1.3.6.1.4.1.8072.3.2.255</sysObjectId><createTime>2019-03-11T07:12:46.421-04:00</createTime>" +
                    "<lastCapsdPoll>2019-03-11T07:12:46.421-04:00</lastCapsdPoll></node>";

            sendPost("rest/nodes", node, 201);

            final String ipInterface = "<ipInterface isManaged=\"M\" snmpPrimary=\"P\"><ipAddress>192.168.10.254</ipAddress><hostName>myNode.local</hostName></ipInterface>";

            sendPost("rest/nodes/smoketests:nodeForeignId/ipinterfaces", ipInterface, 201);

            final String service = "<service status=\"A\"><serviceType id=\"1\"><name>ICMP</name></serviceType></service>";

            sendPost("rest/nodes/smoketests:nodeForeignId/ipinterfaces/192.168.10.254/services", service, 201);

            new NodeDetailsPage(this).open("smoketests:nodeForeignId");

            // The Availability panel draws its timeline in the browser: one row per service,
            // under its interface.
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                    "//*[@data-test='availability-timeline']//tr[@data-test='timeline-service-row']//a[text()='ICMP']")));
            Assert.assertEquals("The availability panel reported a load failure.",
                    0, getDriver().findElements(By.cssSelector("[data-test='availability-error']")).size());
        } finally {
            sendDelete("rest/nodes/smoketests:nodeForeignId", 202);
        }
    }

    // See NMS-10679. Goes through the legacy URL, which stored links still use.
    @Test
    public void verifyNodeNotFoundMessageIsShown() {
        driver.get(getBaseUrlInternal() + "opennms/element/node.jsp?node=12345");
        wait.until(pageContainsText("Node Not Found"));

        driver.get(getBaseUrlInternal() + "opennms/element/node.jsp?node=abc");
        wait.until(pageContainsText("Invalid Node ID"));

        driver.get(getBaseUrlInternal() + "opennms/element/node.jsp?node=ab:cd");
        wait.until(pageContainsText("Node Not Found"));
    }
}
