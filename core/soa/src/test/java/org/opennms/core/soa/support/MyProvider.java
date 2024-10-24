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
package org.opennms.core.soa.support;

import java.io.IOException;

public class MyProvider implements Hello, Goodbye {
    
    String m_name;
    int m_helloSaid = 0;
    int m_goodbyeSaid = 0;
    
    public MyProvider() {
        this("provider");
    }
    
    public MyProvider(String name) {
        m_name = name;
    }

    @Override
    public void sayHello() throws IOException {
        m_helloSaid++;
    }

    @Override
    public void sayGoodbye() throws IOException {
        m_goodbyeSaid++;
    }
    
    public int helloSaid() {
        return m_helloSaid;
    }
    
    public int goodbyeSaid() {
        return m_goodbyeSaid;
    }
    
    @Override
    public String toString() {
        return m_name;
    }
    
    
    
}