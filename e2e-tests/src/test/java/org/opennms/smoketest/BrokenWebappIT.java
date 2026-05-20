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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.ClassRule;
import org.junit.Test;
import org.opennms.smoketest.containers.OpenNMSContainer;
import org.opennms.smoketest.stacks.OpenNMSStack;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;

public class BrokenWebappIT {
    @ClassRule
    public static final OpenNMSStack BROKEN_WEBAPP = OpenNMSStack.minimal(
            b -> b.withFile(getBrokenLdapXml(), "jetty-webapps/opennms/WEB-INF/spring-security.d/ldap.xml"),
            b -> b.withWaitStrategy(c -> new AbstractWaitStrategy() {
                        @Override
                        protected void waitUntilReady() {
                        }
                    }));

    public static URL getBrokenLdapXml() {
        try {
            var source = Path.of("../opennms-webapp/src/main/webapp/WEB-INF/spring-security.d/ldap.xml.disabled");
            var xml = Files.readString(source);

            // Remove an uncommented <beans:entry> element to create an XML syntax error
            var brokenXml = xml.replaceFirst("(?m)<beans:entry>(\\s*<!-- Name of the LDAP group for OpenNMS administrators -->)", "$1");
            if (xml.equals(brokenXml)) {
                fail("No substitutions were done in ldap.xml content\n" + xml);
            }

            var tmpDir = Files.createTempDirectory(BrokenWebappIT.class.getSimpleName());
            var ldapXml = Path.of(tmpDir + "/" + "ldap.xml");
            Files.writeString(ldapXml, brokenXml);

            return ldapXml.toUri().toURL();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testOpenNMSStartupFailsAsExpected() throws InterruptedException {
        var waitStrategy = new OpenNMSContainer.WaitForOpenNMS(BROKEN_WEBAPP.opennms());
        try {
            waitStrategy.waitUntilReady(BROKEN_WEBAPP.opennms());
        } catch (Exception e) {
            assertThat("Exception message", e.getMessage(), containsString("container is no longer running"));
            // We previously asserted on OpenNMS's Invoker error line
            //   "An error occurred invoking operation start on MBean OpenNMS:Name=JettyServer"
            // but with log4j-api 2.23.1 + JDK 21 that line is swallowed by an appender failure:
            // log4j's ThrowableProxyHelper does Class.forName("sun.reflect.misc.Trampoline")
            // while formatting the exception, and JDK 21 refuses (Trampoline must not be
            // defined by the bootstrap classloader). Fixed in log4j 2.24+, but we are
            // pinned below 2.24 for Elasticsearch 7.17 binary compat - see
            // openspec/changes/migrate-elasticsearch-9x/. Assert on Spring's lower-level
            // exception instead; it survives the appender failure and is what we actually
            // care about (the broken XML was detected).
            assertThat("Container logs after container exits",
                    BROKEN_WEBAPP.opennms().getLogs(),
                    containsString("spring-security.d/ldap.xml] is invalid"));
            return;
        }

        fail("Should have received an exception waiting for OpenNMS startup");
    }
}
