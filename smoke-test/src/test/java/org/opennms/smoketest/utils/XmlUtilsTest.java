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
package org.opennms.smoketest.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class XmlUtilsTest {

    /**
     * Verifies that we can filter attributes from XML.
     *
     * @throws Exception on error
     */
    @Test
    public void canFilterAttributesFromXml(@TempDir Path temporaryFolder) throws Exception {
        String xmlIn = "<ipInterface isDown=\"true\" hasFlows=\"false\" monitoredServiceCount=\"0\" snmpPrimary=\"N\">\n"
                +
                "   <ipAddress>192.168.1.1</ipAddress>\n" +
                "   <hostName>192.168.1.1</hostName>\n" +
                "   <nodeId>1</nodeId>\n" +
                "</ipInterface>";
        File target = temporaryFolder.resolve("target").toFile();
        assertTrue(target.mkdirs());
        String expectedFilteredXml = "<ipInterface monitoredServiceCount=\"0\" snmpPrimary=\"N\">\n" +
                "   <ipAddress>192.168.1.1</ipAddress>\n" +
                "   <hostName>192.168.1.1</hostName>\n" +
                "   <nodeId>1</nodeId>\n" +
                "</ipInterface>";
        String actualFilteredXml = XmlUtils.filterAttributesFromXml(xmlIn, "isDown", "hasFlows");
        assertThat(actualFilteredXml, equalTo(expectedFilteredXml));
    }
}
