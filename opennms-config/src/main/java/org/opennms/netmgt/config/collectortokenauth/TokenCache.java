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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.opennms.core.mate.api.Scope;
import org.opennms.netmgt.config.collectortokenauth.TokenProvider;

/**
 * In-memory cache of auth tokens. Keyed by ({@code authName},
 * {@code resolvedFingerprint}) so that one logical auth definition whose
 * URL or credentials are templated with per-node placeholders ends up
 * with one cache entry per distinct resolved shape -- N regional
 * endpoints get N entries, not N-times-number-of-nodes.
 *
 * <p>Concurrent calls that resolve to the same key serialize on the
 * per-key bin lock inherited from {@link ConcurrentHashMap#compute};
 * only one underlying acquire will run for a given key even under
 * contention. Calls for different keys proceed in parallel.</p>
 *
 * <p>The cache is purely in-memory. Reloading or restarting OpenNMS
 * drops all cached tokens.</p>
 */
public class TokenCache {

    private final TokenAcquirer acquirer;
    private final Clock clock;
    private final ConcurrentMap<CacheKey, CachedToken> cache = new ConcurrentHashMap<>();

    public TokenCache(final TokenAcquirer acquirer) {
        this(acquirer, Clock.systemUTC());
    }

    /**
     * Test-friendly constructor that accepts a {@link Clock} for controlling
     * the perceived current time.
     */
    public TokenCache(final TokenAcquirer acquirer, final Clock clock) {
        this.acquirer = Objects.requireNonNull(acquirer, "acquirer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns the cached token for {@code auth}, with no scope context
     * (so any per-node placeholders in the auth definition will not be
     * resolved). Equivalent to {@link #getToken(TokenAuth, Scope)} with a
     * null scope.
     */
    public String getToken(final TokenAuth auth) throws IOException {
        return getToken(auth, null);
    }

    /**
     * Returns the cached token for {@code auth}, resolving any per-node
     * placeholders against {@code callingScope}. The cache key includes
     * a fingerprint of the resolved auth fields, so two requests that
     * resolve to different URLs or credentials get distinct entries.
     *
     * @throws IOException if acquisition fails
     */
    public String getToken(final TokenAuth auth, final Scope callingScope) throws IOException {
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(auth.getName(), "auth.name");

        final String fingerprint = acquirer.fingerprint(auth, callingScope);
        final CacheKey key = new CacheKey(auth.getName(), fingerprint);
        final Instant now = clock.instant();
        try {
            // The acquire() call inside the compute() lambda performs
            // network I/O (up to socketTimeoutMs, default 30s). This
            // is technically against ConcurrentHashMap.compute's
            // contract, which warns mappings should be short. We keep
            // it inside compute deliberately so concurrent callers
            // requesting the SAME (authName, fingerprint) coalesce
            // onto a single in-flight HTTP request, which is the
            // whole point of the cache. Different keys hash to
            // different bins on average, so the per-bin lock
            // contention is small. Do not nest another compute() on
            // the same key inside acquire() -- that would deadlock.
            final CachedToken cached = cache.compute(key, (k, existing) -> {
                if (existing != null && !existing.isExpired(now)) {
                    return existing;
                }
                try {
                    return acquirer.acquire(auth, callingScope);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            return cached.getValue();
        } catch (final UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Drops every cached token for {@code authName} -- across all
     * fingerprint variants. The next {@link #getToken} call for that
     * name (under any context) will trigger a fresh acquire.
     */
    public void invalidate(final String authName) {
        if (authName != null) {
            cache.keySet().removeIf(k -> authName.equals(k.authName));
        }
    }

    /**
     * Reverse-lookup invalidation: scans the cache for an entry whose
     * token value appears as a substring of {@code headerValue}, removes
     * it if found, and returns the auth name plus the matched token text.
     *
     * <p>Substring matching is used so that this works for both raw-token
     * headers ({@code X-Vault-Token: abc123}) and prefixed forms
     * ({@code Authorization: Bearer abc123}). Returning the matched token
     * text lets callers do an in-place rewrite of the header without
     * losing any surrounding prefix or suffix.</p>
     *
     * <p>Safe to call with a null or empty value (returns empty).</p>
     */
    public Optional<TokenProvider.InvalidationResult> invalidateByTokenValue(final String headerValue) {
        if (headerValue == null || headerValue.isEmpty()) {
            return Optional.empty();
        }
        for (final Map.Entry<CacheKey, CachedToken> entry : cache.entrySet()) {
            final String token = entry.getValue().getValue();
            // Why substring match instead of exact-equals: the header
            // value typically wraps the token, e.g.
            // "Authorization: Bearer abc123". String.equals on the
            // header value misses the cached "abc123". Hardcoding a
            // list of known prefixes (Bearer, Token, Basic, SSWS...)
            // would be brittle as new vendors invent custom schemes.
            // The cleaner long-term fix is to track the
            // ${token:foo} -> (request, header-name) mapping at
            // substitution time so we can invalidate by auth name on
            // 401 without searching by token value, but that is a
            // larger change in the metadata DSL pipeline.
            //
            // Why the minimum length guard: substring match exposes a
            // false-positive risk. A 6-char token could appear inside
            // an unrelated header value (User-Agent, Cookie, etc.) by
            // coincidence; the caller would then rewrite that header
            // in place, corrupting it on the retry. Real-world auth
            // tokens are essentially always opaque blobs / JWTs well
            // above this floor, so the guard costs nothing in
            // practice while removing the corruption path.
            if (token != null && token.length() >= MIN_TOKEN_MATCH_LEN && headerValue.contains(token)) {
                cache.remove(entry.getKey(), entry.getValue());
                return Optional.of(new TokenProvider.InvalidationResult(entry.getKey().authName, token));
            }
        }
        return Optional.empty();
    }

    private static final int MIN_TOKEN_MATCH_LEN = 16;

    /** Drops every cached token. Useful on configuration reload. */
    public void invalidateAll() {
        cache.clear();
    }

    /** Returns true if a cached, non-expired token exists for {@code authName}. */
    public boolean isCached(final String authName) {
        if (authName == null) {
            return false;
        }
        final Instant now = clock.instant();
        return cache.entrySet().stream()
                .anyMatch(e -> authName.equals(e.getKey().authName) && !e.getValue().isExpired(now));
    }

    /** Composite cache key. Public-package so tests can construct one. */
    static final class CacheKey {
        final String authName;
        final String fingerprint;

        CacheKey(final String authName, final String fingerprint) {
            this.authName = Objects.requireNonNull(authName, "authName");
            this.fingerprint = fingerprint == null ? "" : fingerprint;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey)) return false;
            final CacheKey that = (CacheKey) o;
            return authName.equals(that.authName) && fingerprint.equals(that.fingerprint);
        }

        @Override
        public int hashCode() {
            return 31 * authName.hashCode() + fingerprint.hashCode();
        }

        @Override
        public String toString() {
            return authName + "#" + fingerprint;
        }
    }
}
