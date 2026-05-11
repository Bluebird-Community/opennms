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
package org.opennms.netmgt.config.collectortokenauth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Optional;

import org.junit.Test;
import org.opennms.netmgt.config.CollectorTokenAuthConfigFactory;

public class TokenProviderImplTest {

    private static CollectorTokenAuthConfigFactory factoryWith(final TokenAuth... auths) {
        final CollectorTokenAuthConfiguration cfg = new CollectorTokenAuthConfiguration();
        cfg.setAuths(java.util.Arrays.asList(auths));
        return new CollectorTokenAuthConfigFactory(cfg);
    }

    private static TokenAuth simpleAuth(final String name) {
        final TokenAuth a = new TokenAuth();
        a.setName(name);
        a.setUrl("https://example.com/auth");
        final TokenFrom tf = new TokenFrom();
        tf.setJsonpath("Token");
        a.setTokenFrom(tf);
        return a;
    }

    @Test
    public void resolvesTokenForKnownAuth() throws IOException {
        final TokenAuth auth = simpleAuth("a");
        final CollectorTokenAuthConfigFactory factory = factoryWith(auth);

        final TokenAcquirer scriptedAcquirer = new TokenAcquirer() {
            @Override
            public CachedToken acquire(final TokenAuth a) {
                return new CachedToken("tok-1", null);
            }
        };
        final TokenCache cache = new TokenCache(scriptedAcquirer);
        final TokenProviderImpl provider = new TokenProviderImpl(() -> factory, cache);

        final Optional<String> token = provider.getToken("a");
        assertTrue(token.isPresent());
        assertEquals("tok-1", token.get());
    }

    @Test
    public void unknownAuthReturnsEmpty() {
        final CollectorTokenAuthConfigFactory factory = factoryWith();  // empty
        final TokenProviderImpl provider = new TokenProviderImpl(() -> factory, new TokenCache(new TokenAcquirer()));
        assertFalse(provider.getToken("missing").isPresent());
    }

    @Test
    public void nullOrEmptyNameReturnsEmpty() {
        final TokenProviderImpl provider = new TokenProviderImpl(() -> factoryWith(),
                new TokenCache(new TokenAcquirer()));
        assertFalse(provider.getToken(null).isPresent());
        assertFalse(provider.getToken("").isPresent());
    }

    @Test
    public void factorySupplierMayReturnNull() {
        // Useful when CollectorTokenAuthConfigFactory.init() hasn't been called yet.
        final TokenProviderImpl provider = new TokenProviderImpl(() -> null,
                new TokenCache(new TokenAcquirer()));
        assertFalse(provider.getToken("anything").isPresent());
    }

    @Test
    public void acquisitionFailureSurfacesAsRuntimeException() {
        final TokenAuth auth = simpleAuth("a");
        final CollectorTokenAuthConfigFactory factory = factoryWith(auth);

        final TokenAcquirer failing = new TokenAcquirer() {
            @Override
            public CachedToken acquire(final TokenAuth a) throws IOException {
                throw new IOException("transient");
            }
        };
        final TokenCache cache = new TokenCache(failing);
        final TokenProviderImpl provider = new TokenProviderImpl(() -> factory, cache);

        final RuntimeException ex = assertThrows(RuntimeException.class,
                () -> provider.getToken("a"));
        assertTrue(ex.getMessage().contains("'a'"));
        assertTrue(ex.getCause() instanceof IOException);
    }
}
