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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.opennms.core.mate.api.ContextKey;
import org.opennms.core.mate.api.EmptyScope;
import org.opennms.core.mate.api.Interpolator;
import org.opennms.core.mate.api.Scope;

public class TokenAuthScopeTest {

    private TokenProvider tokenProvider;
    private TokenAuthScope scope;

    @Before
    public void setUp() {
        TokenAuthScope.takeNamesUsed();
        tokenProvider = mock(TokenProvider.class);
        scope = new TokenAuthScope(tokenProvider, EmptyScope.EMPTY);
    }

    @After
    public void tearDown() {
        TokenAuthScope.takeNamesUsed();
    }

    @Test
    public void resolvesTokenContextKey() {
        when(tokenProvider.getToken(eq("vault"), any(Scope.class))).thenReturn(Optional.of("tok-xyz"));

        final Optional<Scope.ScopeValue> result = scope.get(new ContextKey("token", "vault"));

        assertTrue(result.isPresent());
        assertEquals("tok-xyz", result.get().value);
    }

    @Test
    public void ignoresUnrelatedContextKeys() {
        assertFalse(scope.get(new ContextKey("scv", "vault")).isPresent());
        assertFalse(scope.get(new ContextKey("requisition", "port")).isPresent());
    }

    @Test
    public void returnsEmptyWhenProviderHasNoToken() {
        when(tokenProvider.getToken(eq("missing"), any(Scope.class))).thenReturn(Optional.empty());

        assertFalse(scope.get(new ContextKey("token", "missing")).isPresent());
        assertTrue("absent tokens must not pollute the per-thread record",
                TokenAuthScope.takeNamesUsed().isEmpty());
    }

    @Test
    public void recordsResolvedNamesForThisThread() {
        when(tokenProvider.getToken(eq("vault"), any(Scope.class))).thenReturn(Optional.of("a"));
        when(tokenProvider.getToken(eq("f5"), any(Scope.class))).thenReturn(Optional.of("b"));

        scope.get(new ContextKey("token", "vault"));
        scope.get(new ContextKey("token", "f5"));

        final Set<String> drained = TokenAuthScope.takeNamesUsed();
        assertTrue(drained.contains("vault"));
        assertTrue(drained.contains("f5"));
        assertTrue("takeNamesUsed must drain", TokenAuthScope.takeNamesUsed().isEmpty());
    }

    @Test
    public void interpolatorSubstitutesTokenPlaceholder() {
        // End-to-end-ish: drive a real Mate Interpolator through TokenAuthScope
        // to confirm `${token:NAME}` substitutes as expected.
        when(tokenProvider.getToken(eq("vault"), any(Scope.class))).thenReturn(Optional.of("tok-xyz"));

        final String out = Interpolator.interpolate("Bearer ${token:vault}", scope).output;

        assertEquals("Bearer tok-xyz", out);
    }

    @Test
    public void interpolatorFallsBackThroughInlineDefaultWhenTokenAbsent() {
        when(tokenProvider.getToken(eq("missing"), any(Scope.class))).thenReturn(Optional.empty());

        final String out = Interpolator.interpolate("X: ${token:missing|fallback}", scope).output;

        assertEquals("X: fallback", out);
    }

    @Test
    public void bindReturnsScopeThatResolvesViaSuppliedAmbient() {
        // The receiver is constructed with EmptyScope; the bound copy
        // should pass `newAmbient` to TokenProvider rather than the
        // original ambient.
        final Scope newAmbient = mock(Scope.class);
        final Scope bound = scope.bind(newAmbient);

        when(tokenProvider.getToken(eq("vault"), org.mockito.ArgumentMatchers.same(newAmbient)))
                .thenReturn(Optional.of("tok"));

        final Optional<Scope.ScopeValue> v = bound.get(new ContextKey("token", "vault"));
        assertTrue(v.isPresent());
        assertEquals("tok", v.get().value);
    }

    @Test
    public void bindDoesNotMutateOriginal() {
        final Scope newAmbient = mock(Scope.class);
        final Scope bound = scope.bind(newAmbient);
        org.junit.Assert.assertNotSame("bind must return a new instance", scope, bound);
    }

    @Test
    public void boundCopyParticipatesInTheSamePerThreadRecord() {
        final Scope newAmbient = mock(Scope.class);
        final TokenAuthScope bound = (TokenAuthScope) scope.bind(newAmbient);

        when(tokenProvider.getToken(eq("vault"), org.mockito.ArgumentMatchers.same(newAmbient)))
                .thenReturn(Optional.of("tok"));

        bound.get(new ContextKey("token", "vault"));
        final Set<String> drained = TokenAuthScope.takeNamesUsed();
        assertTrue("name recorded by bound copy must be visible via static drain",
                drained.contains("vault"));
    }

    @Test
    public void bindWithNullAmbientReturnsReceiver() {
        final Scope bound = scope.bind(null);
        org.junit.Assert.assertSame(scope, bound);
    }
}
