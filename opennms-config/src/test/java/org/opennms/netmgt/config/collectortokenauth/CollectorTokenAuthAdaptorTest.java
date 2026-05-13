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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.opennms.core.mate.api.ContextKey;
import org.opennms.core.mate.api.EmptyScope;
import org.opennms.netmgt.collection.api.CollectionAgent;
import org.opennms.netmgt.collection.api.CollectionSet;
import org.opennms.netmgt.collection.api.CollectionStatus;

public class CollectorTokenAuthAdaptorTest {

    private TokenProvider tokenProvider;
    private CollectorTokenAuthAdaptor adaptor;
    private CollectionAgent agent;

    @Before
    public void setUp() {
        // Drain anything a prior test (or stray thread state) left behind.
        TokenAuthScope.takeNamesUsed();

        tokenProvider = mock(TokenProvider.class);
        adaptor = new CollectorTokenAuthAdaptor(tokenProvider);

        agent = mock(CollectionAgent.class);
        when(agent.getNodeId()).thenReturn(42);
    }

    @After
    public void tearDown() {
        TokenAuthScope.takeNamesUsed();
    }

    @Test
    public void beforeCollectReturnsMapWithoutAuthNamesWhenScopeDidNotResolveAnything() {
        final Map<String, Object> params = new HashMap<>();
        params.put("url", "https://example.com/data");

        final Map<String, Object> out = adaptor.beforeCollect(agent, params);

        assertEquals("https://example.com/data", out.get("url"));
        assertEquals("no AUTH_NAMES_USED_PARAM when nothing was resolved",
                null, out.get(CollectorTokenAuthAdaptor.AUTH_NAMES_USED_PARAM));
    }

    @Test
    public void beforeCollectStashesAuthNamesResolvedByScope() {
        // Simulate Interpolator having invoked TokenAuthScope.get(token:vault)
        // earlier in the pipeline. After the substitution succeeds, the
        // scope leaves "vault" in the per-thread record.
        final TokenAuthScope scope = new TokenAuthScope(tokenProvider, EmptyScope.EMPTY);
        when(tokenProvider.getToken(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of("tok-xyz"));
        scope.get(new ContextKey("token", "vault"));

        final Map<String, Object> params = new HashMap<>();
        params.put("Authorization", "Bearer tok-xyz");
        final Map<String, Object> out = adaptor.beforeCollect(agent, params);

        @SuppressWarnings("unchecked")
        final Set<String> names = (Set<String>) out.get(CollectorTokenAuthAdaptor.AUTH_NAMES_USED_PARAM);
        assertTrue("auth name should be carried in the request map",
                names != null && names.contains("vault"));
    }

    @Test
    public void beforeCollectDrainsThreadLocalSoLaterCollectionsAreClean() {
        final TokenAuthScope scope = new TokenAuthScope(tokenProvider, EmptyScope.EMPTY);
        when(tokenProvider.getToken(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of("tok"));
        scope.get(new ContextKey("token", "vault"));
        adaptor.beforeCollect(agent, new HashMap<>());

        // Second collection: scope didn't resolve anything for this one.
        final Map<String, Object> next = new HashMap<>();
        next.put("k", "v");
        final Map<String, Object> out = adaptor.beforeCollect(agent, next);
        assertEquals("must not leak names from the previous collection",
                null, out.get(CollectorTokenAuthAdaptor.AUTH_NAMES_USED_PARAM));
    }

    @Test
    public void handleCollectionResultInvalidatesWhenFailedAndAuthsUsed() {
        final Map<String, Object> params = new HashMap<>();
        params.put(CollectorTokenAuthAdaptor.AUTH_NAMES_USED_PARAM, Set.of("vault", "f5"));
        final CollectionSet failed = mock(CollectionSet.class);
        when(failed.getStatus()).thenReturn(CollectionStatus.FAILED);

        final CollectionSet returned = adaptor.handleCollectionResult(agent, params, failed);

        assertSame(failed, returned);
        verify(tokenProvider).invalidate("vault");
        verify(tokenProvider).invalidate("f5");
    }

    @Test
    public void handleCollectionResultDoesNotInvalidateOnSuccess() {
        final Map<String, Object> params = new HashMap<>();
        params.put(CollectorTokenAuthAdaptor.AUTH_NAMES_USED_PARAM, Set.of("vault"));
        final CollectionSet ok = mock(CollectionSet.class);
        when(ok.getStatus()).thenReturn(CollectionStatus.SUCCEEDED);

        adaptor.handleCollectionResult(agent, params, ok);

        verify(tokenProvider, never()).invalidate(anyString());
    }

    @Test
    public void handleCollectionResultDoesNotInvalidateWhenNoAuthsParticipated() {
        final Map<String, Object> params = new HashMap<>();
        final CollectionSet failed = mock(CollectionSet.class);
        when(failed.getStatus()).thenReturn(CollectionStatus.FAILED);

        adaptor.handleCollectionResult(agent, params, failed);

        verify(tokenProvider, never()).invalidate(anyString());
    }

    @Test
    public void handleCollectionResultIgnoresNullParameters() {
        final CollectionSet failed = mock(CollectionSet.class);
        when(failed.getStatus()).thenReturn(CollectionStatus.FAILED);

        final CollectionSet returned = adaptor.handleCollectionResult(agent, null, failed);

        assertSame(failed, returned);
        verify(tokenProvider, never()).invalidate(anyString());
    }
}
