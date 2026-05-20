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
package org.opennms.netmgt.config.tokenauth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.Test;
import org.opennms.netmgt.collection.api.CollectionAgent;
import org.opennms.netmgt.collection.api.CollectorAdaptor;
import org.opennms.netmgt.collection.client.rpc.CollectorRequestBuilderImpl;

/**
 * Cross-module integration: exercises the chain
 * {@code CollectorRequestBuilderImpl.applyAdaptorsAndPropagate} →
 * {@link TokenAuthCollectorAdaptor#handleCollectionResult} →
 * {@code TokenProvider.invalidate(name)}.
 *
 * <p>This is the exact composition that lets a 401 from the backend rotate
 * the cached token. Each link has its own unit test; this one asserts they
 * actually work together so a future change to any single link can't
 * silently break rotation.</p>
 */
public class TokenAuthInvalidationIntegrationTest {

    /** Records every {@code invalidate(name)} call so the test can assert on them. */
    private static final class RecordingTokenProvider implements TokenProvider {
        final ConcurrentLinkedQueue<String> invalidated = new ConcurrentLinkedQueue<>();

        @Override
        public java.util.Optional<String> getToken(String authName) {
            return java.util.Optional.empty();
        }

        @Override
        public void invalidate(String authName) {
            invalidated.add(authName);
        }
    }

    @Test
    public void failedCollectionPropagatesInvalidationThroughRealAdaptor() {
        final RecordingTokenProvider provider = new RecordingTokenProvider();
        final TokenAuthCollectorAdaptor adaptor = new TokenAuthCollectorAdaptor(provider);
        final List<CollectorAdaptor> adaptors = Arrays.asList(adaptor);

        final CollectionAgent agent = mock(CollectionAgent.class);
        final Map<String, Object> dispatched = new HashMap<>();
        dispatched.put(TokenAuthCollectorAdaptor.AUTH_NAMES_USED_PARAM, "vault,catalyst-center");

        try {
            CollectorRequestBuilderImpl.applyAdaptorsAndPropagate(
                    agent, dispatched, adaptors, null, new IOException("HTTP 401 from test"));
            fail("expected the IOException to be rethrown after adaptors ran");
        } catch (RuntimeException re) {
            // ok — rethrow path
        }

        assertEquals("both auth names should have been invalidated",
                Arrays.asList("vault", "catalyst-center"),
                new java.util.ArrayList<>(provider.invalidated));
    }

    @Test
    public void succeededCollectionDoesNotInvalidate() {
        final RecordingTokenProvider provider = new RecordingTokenProvider();
        final TokenAuthCollectorAdaptor adaptor = new TokenAuthCollectorAdaptor(provider);
        final CollectionAgent agent = mock(CollectionAgent.class);
        final Map<String, Object> dispatched = new HashMap<>();
        dispatched.put(TokenAuthCollectorAdaptor.AUTH_NAMES_USED_PARAM, "vault");

        // Build a SUCCEEDED CollectionSet via the same path the framework uses.
        final org.opennms.netmgt.collection.api.CollectionSet ok =
                new org.opennms.netmgt.collection.support.builder.CollectionSetBuilder(agent).build();

        CollectorRequestBuilderImpl.applyAdaptorsAndPropagate(
                agent, dispatched, Arrays.asList(adaptor), ok, null);

        assertEquals("no invalidation on a SUCCEEDED result",
                Collections.<String>emptyList(),
                new java.util.ArrayList<>(provider.invalidated));
    }

    @Test
    public void failedCollectionWithNoAuthNamesIsNoop() {
        final RecordingTokenProvider provider = new RecordingTokenProvider();
        final TokenAuthCollectorAdaptor adaptor = new TokenAuthCollectorAdaptor(provider);
        final CollectionAgent agent = mock(CollectionAgent.class);
        final Map<String, Object> dispatched = new HashMap<>();
        // No AUTH_NAMES_USED_PARAM — nothing was substituted, so nothing to invalidate.

        try {
            CollectorRequestBuilderImpl.applyAdaptorsAndPropagate(
                    agent, dispatched, Arrays.asList((CollectorAdaptor) adaptor), null,
                    new IOException("HTTP 500"));
            fail();
        } catch (RuntimeException re) {
            // ok
        }

        assertEquals(Collections.<String>emptyList(),
                new java.util.ArrayList<>(provider.invalidated));
    }
}
