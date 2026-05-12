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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.opennms.core.mate.api.EmptyScope;
import org.opennms.core.mate.api.EntityScopeProvider;
import org.opennms.core.mate.api.Scope;
import org.opennms.netmgt.collection.api.CollectionAgent;
import org.opennms.netmgt.collection.api.CollectionSet;
import org.opennms.netmgt.collection.api.CollectionStatus;

public class CollectorTokenAuthAdaptorTest {

    private TokenProvider tokenProvider;
    private EntityScopeProvider scopeProvider;
    private CollectorTokenAuthAdaptor adaptor;
    private CollectionAgent agent;

    @Before
    public void setUp() throws Exception {
        tokenProvider = mock(TokenProvider.class);
        scopeProvider = mock(EntityScopeProvider.class);
        when(scopeProvider.getScopeForNode(anyInt())).thenReturn(EmptyScope.EMPTY);
        when(scopeProvider.getScopeForInterface(anyInt(), anyString())).thenReturn(EmptyScope.EMPTY);
        adaptor = new CollectorTokenAuthAdaptor(tokenProvider, scopeProvider);

        agent = mock(CollectionAgent.class);
        when(agent.getNodeId()).thenReturn(42);
        when(agent.getAddress()).thenReturn(InetAddress.getByName("10.0.0.1"));
    }

    @Test
    public void beforeCollectReturnsInputUnchangedWhenNoPlaceholder() {
        final Map<String, Object> params = new HashMap<>();
        params.put("url", "https://example.com/data");
        params.put("ttl", "30000");

        final Map<String, Object> out = adaptor.beforeCollect(agent, params);

        assertSame("no allocation when nothing to substitute", params, out);
        verify(tokenProvider, never()).getToken(anyString(), any(Scope.class));
    }

    @Test
    public void beforeCollectSubstitutesPlaceholderAndStashesAuthName() {
        when(tokenProvider.getToken(eq("vault"), any(Scope.class))).thenReturn(Optional.of("tok-xyz"));

        final Map<String, Object> params = new HashMap<>();
        params.put("Authorization", "Bearer ${token:vault}");
        params.put("url", "https://example.com/data");

        final Map<String, Object> out = adaptor.beforeCollect(agent, params);

        assertEquals("Bearer tok-xyz", out.get("Authorization"));
        assertEquals("https://example.com/data", out.get("url"));

        @SuppressWarnings("unchecked")
        final Set<String> names = (Set<String>) out.get(CollectorTokenAuthAdaptor.AUTH_NAMES_USED_PARAM);
        assertTrue("auth name should be stashed for post-result invalidation",
                names != null && names.contains("vault"));
    }

    @Test
    public void beforeCollectUsesInlineFallbackWhenTokenAbsent() {
        when(tokenProvider.getToken(eq("missing"), any(Scope.class))).thenReturn(Optional.empty());

        final Map<String, Object> params = new HashMap<>();
        params.put("h", "X: ${token:missing|fallback-value}");

        final Map<String, Object> out = adaptor.beforeCollect(agent, params);

        assertEquals("X: fallback-value", out.get("h"));
    }

    @Test
    public void beforeCollectSubstitutesEmptyWhenTokenAbsentAndNoFallback() {
        when(tokenProvider.getToken(eq("missing"), any(Scope.class))).thenReturn(Optional.empty());

        final Map<String, Object> params = new HashMap<>();
        params.put("h", "X: ${token:missing}");

        final Map<String, Object> out = adaptor.beforeCollect(agent, params);

        assertEquals("X: ", out.get("h"));
    }

    @Test
    public void handleCollectionResultInvalidatesWhenFailedAndAuthsUsed() {
        final Map<String, Object> params = new HashMap<>();
        params.put(CollectorTokenAuthAdaptor.AUTH_NAMES_USED_PARAM, java.util.Set.of("vault", "f5"));
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
        params.put(CollectorTokenAuthAdaptor.AUTH_NAMES_USED_PARAM, java.util.Set.of("vault"));
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

}
