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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FocusedVertexIT extends OpenNMSSeleniumIT {

    private TopologyIT.TopologyUIPage topologyUIPage;

    @BeforeEach
    public void setUp() {
        topologyUIPage = new TopologyIT.TopologyUIPage(this, "");
    }

    @Test
    public void testEqualsAndHashCode() {
        TopologyIT.FocusedVertex focusedVertex1 = new TopologyIT.FocusedVertex(topologyUIPage, "namespace1", "id");
        TopologyIT.FocusedVertex focusedVertex2 = new TopologyIT.FocusedVertex(topologyUIPage, "namespace1", "id");
        TopologyIT.FocusedVertex focusedVertex3 = new TopologyIT.FocusedVertex(topologyUIPage, "namespace2", "id");

        Assertions.assertEquals(focusedVertex1, focusedVertex1);
        Assertions.assertEquals(focusedVertex1, focusedVertex2);
        Assertions.assertNotEquals(focusedVertex1, focusedVertex3);

        Assertions.assertEquals(focusedVertex1.hashCode(), focusedVertex2.hashCode());
        Assertions.assertNotEquals(focusedVertex1.hashCode(), focusedVertex3.hashCode());
    }
}