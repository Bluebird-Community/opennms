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

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.opennms.core.mate.api.BoundableScope;
import org.opennms.core.mate.api.ContextKey;
import org.opennms.core.mate.api.Scope;

/**
 * Mate {@link Scope} that resolves {@code ${token:<name>}} placeholders
 * against {@link TokenProvider} so Mate's {@code Interpolator} can
 * substitute them during parameter expansion.
 *
 * <p>Lookups go through {@link TokenProvider#getToken}; the inner
 * {@code ambientScope} is what {@link TokenProvider} hands to
 * {@code TokenAcquirer} for resolving placeholders inside the
 * token-auth definition itself ({@code ${scv:...}}, {@code ${env:...}},
 * and -- when this scope is bound per call by {@code EntityScopeProvider}
 * via {@link BoundableScope#bind} -- {@code ${node:...}},
 * {@code ${interface:...}}, {@code ${asset:...}} too).</p>
 *
 * <p>Names that resolve successfully are recorded in a per-thread set
 * so {@link CollectorTokenAuthAdaptor#handleCollectionResult} can
 * invalidate the right cache entries on failure. The Interpolator runs
 * on the same thread that subsequently invokes the adaptors, so a
 * {@link ThreadLocal} is the natural carrier.</p>
 */
public class TokenAuthScope implements Scope, BoundableScope {

    public static final String CONTEXT = "token";

    private static final ThreadLocal<Set<String>> NAMES_USED =
            ThreadLocal.withInitial(HashSet::new);

    private final TokenProvider tokenProvider;
    private final Scope ambientScope;

    public TokenAuthScope(final TokenProvider tokenProvider, final Scope ambientScope) {
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
        this.ambientScope = Objects.requireNonNull(ambientScope, "ambientScope");
    }

    @Override
    public Optional<ScopeValue> get(final ContextKey contextKey) {
        if (contextKey == null || !CONTEXT.equals(contextKey.getContext())) {
            return Optional.empty();
        }
        final String authName = contextKey.getKey();
        return tokenProvider.getToken(authName, ambientScope)
                .map(token -> {
                    NAMES_USED.get().add(authName);
                    return new ScopeValue(ScopeName.NODE, token);
                });
    }

    /**
     * Returns a new {@code TokenAuthScope} that resolves token-auth
     * definitions against {@code newAmbient} (typically the per-call
     * composite scope built by {@code EntityScopeProvider}). The
     * receiver is unchanged; per-thread name tracking stays shared so
     * the adaptor sees names recorded against any bound instance.
     */
    @Override
    public Scope bind(final Scope newAmbient) {
        if (newAmbient == null) {
            return this;
        }
        return new TokenAuthScope(tokenProvider, newAmbient);
    }

    @Override
    public Set<ContextKey> keys() {
        return Collections.emptySet();
    }

    /**
     * Drain and return the set of token-auth names resolved on the
     * current thread. Intended for adaptors that need to know which
     * auths participated in a request so they can invalidate the cache
     * on a failed response.
     */
    public static Set<String> takeNamesUsed() {
        final Set<String> snapshot = new HashSet<>(NAMES_USED.get());
        NAMES_USED.get().clear();
        return snapshot;
    }
}
