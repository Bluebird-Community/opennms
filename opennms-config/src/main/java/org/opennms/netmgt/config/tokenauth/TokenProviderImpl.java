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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.opennms.core.mate.api.Scope;
import org.opennms.netmgt.config.tokenauth.TokenProvider;
import org.opennms.netmgt.config.TokenAuthConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link TokenProvider} implementation that bridges the
 * {@link TokenAuthConfigFactory} (for definitions) and the {@link TokenCache}
 * (for current values).
 *
 * <p>Returns {@link Optional#empty()} for an unknown auth name --
 * {@link TokenAuthCollectorAdaptor} treats that as "not our placeholder, fall through". If
 * a definition exists but acquisition fails, the underlying
 * {@link IOException} is rethrown wrapped in a {@link RuntimeException}
 * so the failing collection cycle surfaces a clear error rather than
 * sending an empty token downstream.</p>
 */
public class TokenProviderImpl implements TokenProvider {

    private static final Logger LOG = LoggerFactory.getLogger(TokenProviderImpl.class);

    private final Supplier<TokenAuthConfigFactory> configSupplier;
    private final TokenCache tokenCache;

    /**
     * Constructs the provider with a supplier for the config factory.
     * Using a supplier rather than a direct reference accommodates the
     * factory's singleton lifecycle and lets callers wire this up before
     * {@code TokenAuthConfigFactory.init()} has been called.
     */
    public TokenProviderImpl(final Supplier<TokenAuthConfigFactory> configSupplier,
                             final TokenCache tokenCache) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.tokenCache = Objects.requireNonNull(tokenCache, "tokenCache");
    }

    /**
     * Default constructor used by the Spring wiring. Calls
     * {@link TokenAuthConfigFactory#init()} lazily on first lookup. If
     * {@code etc/token-auth-configuration.xml} is absent, {@link #getToken} returns
     * empty rather than failing -- on a fresh OpenNMS install without
     * {@code <token-auth>} blocks defined, every {@code ${token:...}} placeholder
     * just falls through unmatched.
     */
    public TokenProviderImpl(final TokenCache tokenCache) {
        this(TokenProviderImpl::lenientFactoryLookup, tokenCache);
    }

    private static TokenAuthConfigFactory lenientFactoryLookup() {
        try {
            TokenAuthConfigFactory.init();
            return TokenAuthConfigFactory.getInstance();
        } catch (final FileNotFoundException e) {
            LOG.info("token-auth-configuration.xml not present; dynamic-auth disabled");
            return null;
        } catch (final IOException e) {
            throw new RuntimeException("failed to load token-auth-configuration.xml", e);
        } catch (final IllegalStateException e) {
            // Race or test scenario where init() has not yet completed.
            // Tokens silently fail to resolve until next call; surface
            // at debug so an operator chasing a "${token:foo} resolved
            // to empty" report can see this happened.
            LOG.debug("TokenAuthConfigFactory not yet initialised; ${token:...} will resolve as empty until init() completes", e);
            return null;
        }
    }

    @Override
    public Optional<String> getToken(final String authName) {
        return getToken(authName, null);
    }

    @Override
    public Optional<String> getToken(final String authName, final Scope callingScope) {
        if (authName == null || authName.isEmpty()) {
            return Optional.empty();
        }
        final TokenAuthConfigFactory factory = configSupplier.get();
        if (factory == null) {
            return Optional.empty();
        }
        return factory.getAuth(authName).map(auth -> {
            try {
                return tokenCache.getToken(auth, callingScope);
            } catch (final IOException e) {
                throw new RuntimeException(
                        "failed to acquire token for auth '" + authName + "'", e);
            }
        });
    }

    /**
     * Drops any cached token for {@code authName}. Intended to be called
     * from collection code paths that observe a 401 from a downstream call.
     */
    public void invalidate(final String authName) {
        tokenCache.invalidate(authName);
    }

    @Override
    public Optional<TokenProvider.InvalidationResult> invalidateByTokenValue(final String headerValue) {
        return tokenCache.invalidateByTokenValue(headerValue);
    }
}
