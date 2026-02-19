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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.openqa.selenium.By;

@TestMethodOrder(MethodOrderer.MethodName.class)
public class InstrumentationLogReaderPageIT extends OpenNMSSeleniumIT {
    @BeforeEach
    public void setUp() throws Exception {
        adminPage();
        findElementByLink("Instrumentation Log Reader").click();
    }

    @Test
    public void testInstrumentationLogReaderPage() {
        enterText(By.name("searchString"), "test");
        assertEquals("test", findElementByName("searchString").getAttribute("value"));
        findElementByXpath("//button[@type='submit' and text()='Submit']").click();
        findElementByXpath("//button[@type='submit' and text()='Reset']").click();
        assertEquals("", findElementByName("searchString").getAttribute("value"));
    }

    @Test
    public void testSortingLinks() {
        findElementByLink("Collections").click();
        findElementByXpath("//a[text()='Collections ^']").click();
        findElementByXpath("//a[text()='Collections v']").click();
        findElementByLink("Average Successful Collection Time").click();
        findElementByXpath("//a[text()='Average Successful Collection Time ^']").click();
        findElementByXpath("//a[text()='Average Successful Collection Time v']").click();
        findElementByLink("Average Persistence Time").click();
        findElementByXpath("//a[text()='Average Persistence Time ^']").click();
        findElementByXpath("//a[text()='Average Persistence Time v']").click();
    }
}
