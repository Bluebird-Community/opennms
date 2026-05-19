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

import java.util.Optional;

import org.opennms.core.mate.api.Scope;

/**
 * Resolves named token-auth definitions to a current token value, backing
 * the {@code ${token:<name>}} placeholder used by the XML/JSON and HTTP
 * collectors.
 */
public interface TokenProvider {

    Optional<String> getToken(String authName);

    /**
     * Variant that lets the auth definition's own fields resolve against
     * {@code callingScope}, so {@code ${node:...}} or {@code ${scv:...}}
     * inside an auth definition pick up per-node context.
     */
    default Optional<String> getToken(final String authName, final Scope callingScope) {
        return getToken(authName);
    }

    /**
     * Result of a successful {@link #invalidateByTokenValue} call. Carries
     * the auth name plus the token text that matched, so callers can
     * rewrite just the token portion of a header (preserving any
     * surrounding "Bearer " prefix).
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
     * Reverse-lookup invalidation: drops the cached token whose value
     * appears as a substring of {@code headerValue}, and returns the auth
     * name plus matched text. Default returns empty for providers without
     * a cache.
     */
    default Optional<InvalidationResult> invalidateByTokenValue(String headerValue) {
        return Optional.empty();
    }

    default void invalidate(final String authName) {
        // no-op
    }
}
