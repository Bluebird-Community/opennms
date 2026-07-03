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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.junit.Assert;
import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;

public class NodeDetailPageIT extends OpenNMSSeleniumIT {

    public static class NodeDetailPage {

        private final OpenNMSSeleniumIT testCase;
        private final String url;

        public NodeDetailPage(OpenNMSSeleniumIT testCase, int nodeId) {
            this.testCase = Objects.requireNonNull(testCase);
            this.url = Objects.requireNonNull(testCase.getBaseUrlInternal()) + "opennms/element/node.jsp?node=" + nodeId;
        }

        public NodeDetailPage open() {
            testCase.getDriver().get(url);
            return this;
        }
    }

    private void createNodeWithInterfaces(final String nodeLabel, final int numberOfInterfaces) throws Exception {
        final String foreignSource = "test";
        final String foreignId = nodeLabel;
        final String foreignSourceAndForeignId = foreignSource + ":" + foreignId;

        final String node = "<node type=\"A\" label=\""+nodeLabel+"\" foreignSource=\""+foreignSource+"\" foreignId=\""+foreignId+"\">" +
                "<labelSource>H</labelSource><sysContact>Ulf</sysContact><sysDescription>Test System</sysDescription><sysLocation>Fulda</sysLocation>"+
                "<sysName>"+nodeLabel+"</sysName><sysObjectId>.1.3.6.1.4.1.8072.3.2.255</sysObjectId><createTime>2019-03-11T07:12:46.421-04:00</createTime>" +
                "<lastCapsdPoll>2019-03-11T07:12:46.421-04:00</lastCapsdPoll></node>";

        sendPost("rest/nodes", node, 201);

        for (int i = 0; i < numberOfInterfaces; i++) {
            final String ipAddress = "192.168.1." + (i + 1);
            final String ipInterface = "<ipInterface isManaged=\"M\" snmpPrimary=\"P\">" +
                    "<ipAddress>"+ipAddress+"</ipAddress><hostName>"+nodeLabel+".local</hostName>" +
                    "</ipInterface>";

            sendPost("rest/nodes/"+foreignSourceAndForeignId+"/ipinterfaces", ipInterface, 201);
        }
    }

    @Test
    public void checkMaximumNumberOfInterfaces() throws Exception {
        try {
            createNodeWithInterfaces("nodeWith10Interfaces", 10);
            createNodeWithInterfaces("nodeWith11Interfaces", 11);
            
            getDriver().get(getBaseUrlInternal()+"opennms/element/node.jsp?node=test:nodeWith10Interfaces");
            waitForElement(By.id("onms-interfaces"));

            setImplicitWait(1, SECONDS);
            Assert.assertEquals(1, driver.findElements(By.id("availability-box")).size());
            Assert.assertEquals(1, driver.findElements(By.linkText("192.168.1.1")).size());
            Assert.assertEquals(1, driver.findElements(By.linkText("192.168.1.2")).size());
            Assert.assertEquals(1, driver.findElements(By.linkText("192.168.1.3")).size());
            Assert.assertEquals(1, driver.findElements(By.linkText("192.168.1.4")).size());
            Assert.assertEquals(1, driver.findElements(By.linkText("192.168.1.5")).size());
            Assert.assertEquals(1, driver.findElements(By.linkText("192.168.1.6")).size());
            Assert.assertEquals(1, driver.findElements(By.linkText("192.168.1.7")).size());
            Assert.assertEquals(1, driver.findElements(By.linkText("192.168.1.8")).size());
            Assert.assertEquals(1, driver.findElements(By.linkText("192.168.1.9")).size());
            Assert.assertEquals(1, driver.findElements(By.linkText("192.168.1.10")).size());
            Assert.assertEquals(0, driver.findElements(By.linkText("192.168.1.11")).size());
            setImplicitWait();

            driver.get(getBaseUrlInternal()+"opennms/element/node.jsp?node=test:nodeWith11Interfaces");
            waitForElement(By.id("onms-interfaces"));

            setImplicitWait(1, SECONDS);
            Assert.assertEquals(0, driver.findElements(By.id("availability-box")).size());
            Assert.assertEquals(0, driver.findElements(By.linkText("192.168.1.1")).size());
            Assert.assertEquals(0, driver.findElements(By.linkText("192.168.1.2")).size());
            Assert.assertEquals(0, driver.findElements(By.linkText("192.168.1.3")).size());
            Assert.assertEquals(0, driver.findElements(By.linkText("192.168.1.4")).size());
            Assert.assertEquals(0, driver.findElements(By.linkText("192.168.1.5")).size());
            Assert.assertEquals(0, driver.findElements(By.linkText("192.168.1.6")).size());
            Assert.assertEquals(0, driver.findElements(By.linkText("192.168.1.7")).size());
            Assert.assertEquals(0, driver.findElements(By.linkText("192.168.1.8")).size());
            Assert.assertEquals(0, driver.findElements(By.linkText("192.168.1.9")).size());
            Assert.assertEquals(0, driver.findElements(By.linkText("192.168.1.10")).size());
            Assert.assertEquals(0, driver.findElements(By.linkText("192.168.1.11")).size());
            setImplicitWait();
        } finally {
            sendDelete("rest/nodes/test:nodeWith10Interfaces", 202);
            sendDelete("rest/nodes/test:nodeWith11Interfaces", 202);
        }
    }

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

            driver.get(getBaseUrlInternal() + "opennms/element/node.jsp?node=smoketests:nodeForeignId");

            // Fail fast (and clearly) if the session bounced us to the login page instead
            // of the node page -- that's an environmental/auth issue, not a timeline one,
            // and without this guard the wait below would burn the full LOAD_TIMEOUT
            // hunting for timeline images a login page will never have. getCurrentUrl() is
            // immediate; testing for the login form via findElements would instead block
            // on the implicit wait whenever the form is (correctly) absent.
            final String currentUrl = getDriver().getCurrentUrl();
            Assert.assertFalse("Expected the node detail page but was redirected to the login page "
                    + "(session not authenticated); URL: " + currentUrl, currentUrl.contains("login.jsp"));

            // Use the suite's standard page-load budget rather than a tight 5s: node.jsp
            // injects the timeline <img>s via JS after load and the PNGs are rendered
            // server-side, which can take longer than 5s on a busy CI host. The DOM churns
            // while those images are added, so an <img> found in one poll can be replaced
            // before we read it -- ignore only that StaleElementReferenceException so a real
            // page-load/JS failure still surfaces immediately instead of after the timeout.
            await().atMost(Duration.ofMillis(LOAD_TIMEOUT))
                    .pollInterval(Duration.ofSeconds(1))
                    .ignoreExceptionsInstanceOf(StaleElementReferenceException.class)
                    .until(() -> {
                        final List<WebElement> timelineImages = getDriver().findElements(By.tagName("img")).stream()
                                .filter(i -> {
                                    final String src = i.getAttribute("src");
                                    return src != null && src.contains("timeline");
                                })
                                .collect(Collectors.toList());
                        if (timelineImages.size() < 2) {
                            return false;
                        }
                        for (final WebElement timelineImage : timelineImages) {
                            final Object result = ((JavascriptExecutor) driver).executeScript("return arguments[0].complete && typeof arguments[0].naturalWidth != \"undefined\" && arguments[0].naturalWidth > 0", timelineImage);
                            if (!(result instanceof Boolean) || !((Boolean) result).booleanValue()) {
                                return false;
                            }
                        }
                        return true;
                    });
        } finally {
            sendDelete("rest/nodes/smoketests:nodeForeignId", 202);
        }
    }

    // See NMS-10679
    @Test
    public void verifyNodeNotFoundMessageIsShown() {
        final String NODE_NOT_FOUND = "Node Not Found";
        driver.get(getBaseUrlInternal() + "opennms/element/node.jsp?node=12345");
        pageContainsText(NODE_NOT_FOUND);

        final String NODE_ID_NOT_FOUND = "Node ID Not Found";
        driver.get(getBaseUrlInternal() + "opennms/element/node.jsp?node=abc");
        pageContainsText(NODE_ID_NOT_FOUND);
        driver.get(getBaseUrlInternal() + "opennms/element/node.jsp?node=ab:cd");
        pageContainsText(NODE_ID_NOT_FOUND);
    }
}
