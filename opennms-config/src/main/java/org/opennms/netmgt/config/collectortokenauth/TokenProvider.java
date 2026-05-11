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

import java.util.Optional;

import org.opennms.core.mate.api.Scope;

/**
 * Resolves named token-auth definitions to a current token value. Used by
 * the XML/JSON and HTTP collectors to back the {@code ${token:<name>}}
 * placeholder when building outbound requests. Implementations are
 * responsible for fetching, caching, and refreshing tokens behind this
 * interface.
 *
 * <p>The interface and its implementation live in {@code opennms-config}.
 * Collectors consume it via the same package, so token resolution stays
 * local to the collector layer instead of leaking into the core metadata
 * DSL.</p>
 */
public interface TokenProvider {

    /**
     * Returns the current token for the named auth definition, or empty
     * if no definition is registered under that name.
     *
     * <p>Implementations may throw {@link RuntimeException} (typically
     * wrapping an {@link java.io.IOException}) if a definition exists but
     * acquisition fails. Callers should treat that as a hard failure --
     * surfacing the misconfiguration is preferable to silently producing
     * an empty token that will produce a confusing 401 downstream.</p>
     */
    Optional<String> getToken(String authName);

    /**
     * Variant that lets the token-auth definition's own fields (URL,
     * basic-auth username/password, content body, header values) resolve
     * against the {@code callingScope}. This is what the collector-side
     * resolver passes in so that {@code ${node:...}} or
     * {@code ${requisition:...}} placeholders inside an auth definition
     * pick up the per-node context of the request that triggered the
     * lookup.
     *
     * <p>Cache identity widens to include a fingerprint of the resolved
     * fields, so two requests that resolve to different URLs (or
     * different credentials) get distinct cache entries -- typical
     * "one logical auth, many regional endpoints" deployments end up
     * with one cache entry per distinct endpoint, not one per node.</p>
     *
     * <p>Default implementation ignores {@code callingScope} and falls
     * through to {@link #getToken(String)}.</p>
     */
    default Optional<String> getToken(final String authName, final Scope callingScope) {
        return getToken(authName);
    }

    /**
     * Result of a successful reverse-lookup invalidation: identifies the
     * auth that owned the matched token and surfaces the matched token text
     * itself, so callers performing in-place header rewrites can substitute
     * just the token portion and preserve any surrounding text (e.g. a
     * "Bearer " prefix).
     */
    final class InvalidationResult {
        private final String authName;
        private final String matchedTokenValue;

        public InvalidationResult(final String authName, final String matchedTokenValue) {
            this.authName = authName;
            this.matchedTokenValue = matchedTokenValue;
        }

        public String getAuthName() {
            return authName;
        }

        public String getMatchedTokenValue() {
            return matchedTokenValue;
        }
    }

    /**
     * Reverse-lookup invalidation: scans cached tokens for any whose value
     * appears as a substring of {@code headerValue}. If found, drops that
     * cache entry and returns the auth name and the matched token text.
     *
     * <p>Substring matching lets this work for both raw-token headers
     * ({@code X-Vault-Token: abc123}) and prefixed forms
     * ({@code Authorization: Bearer abc123}). Token strings are random and
     * long enough in practice that accidental substring collisions are not
     * a real concern.</p>
     *
     * <p>Used by HTTP retry paths that observe a 401 on a downstream
     * request and need to invalidate the right auth without having the
     * auth name on hand. Default implementation returns empty -- providers
     * that have no notion of a cache override this.</p>
     */
    default Optional<InvalidationResult> invalidateByTokenValue(String headerValue) {
        return Optional.empty();
    }
}
