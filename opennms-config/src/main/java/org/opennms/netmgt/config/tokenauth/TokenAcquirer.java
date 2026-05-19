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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.opennms.core.mate.api.EntityScopeProvider;
import org.opennms.core.mate.api.EmptyScope;
import org.opennms.core.mate.api.FallbackScope;
import org.opennms.core.mate.api.Interpolator;
import org.opennms.core.mate.api.Scope;
import org.opennms.core.web.EmptyKeyRelaxedTrustProvider;
import org.opennms.core.web.HttpClientWrapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Performs the HTTP call described by an {@link TokenAuth} definition and
 * returns the resulting {@link CachedToken}. Used by {@link TokenCache} on
 * cache misses and on token invalidation.
 *
 * <p>This class is intentionally stateless. A single instance can be shared
 * across many auth definitions and many concurrent callers. Each
 * {@link #acquire(TokenAuth)} call constructs a fresh
 * {@link HttpClientWrapper} configured for the specific auth definition's
 * SSL/proxy/timeout settings, executes the request, and closes the
 * wrapper.</p>
 */
public class TokenAcquirer {

    static {
        // Make the EmptyKeyRelaxedTrustSSLContext algorithm available
        // to JSSE so configureClient() can request relaxed-trust
        // SSLContext when an auth definition opts in via
        // disable-ssl-verification="true". HttpClientWrapper.useRelaxedSSL
        // looks up the algorithm by name and fails with
        // NoSuchAlgorithmException if the provider was never added.
        // Other components (HttpCollector, PageSequenceMonitor, ...)
        // also register this provider; java.security.Security tolerates
        // duplicate adds and returns -1 for the second.
        java.security.Security.addProvider(new EmptyKeyRelaxedTrustProvider());
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_SOCKET_TIMEOUT_MS = 30_000;

    private final int connectTimeoutMs;
    private final int socketTimeoutMs;
    private final java.time.Clock clock;
    private volatile EntityScopeProvider entityScopeProvider;

    public TokenAcquirer() {
        this(DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_SOCKET_TIMEOUT_MS, null, java.time.Clock.systemUTC());
    }

    public TokenAcquirer(final int connectTimeoutMs, final int socketTimeoutMs) {
        this(connectTimeoutMs, socketTimeoutMs, null, java.time.Clock.systemUTC());
    }

    public TokenAcquirer(final int connectTimeoutMs, final int socketTimeoutMs,
                         final EntityScopeProvider entityScopeProvider) {
        this(connectTimeoutMs, socketTimeoutMs, entityScopeProvider, java.time.Clock.systemUTC());
    }

    /**
     * Test-friendly constructor that accepts a {@link java.time.Clock} for
     * computing the cached-token expiry timestamp. Production wiring uses
     * {@link java.time.Clock#systemUTC()}; tests that share a fake clock
     * with {@link TokenCache} should pass that same clock here so token
     * expiries computed at acquire time are comparable to the cache's
     * notion of "now".
     */
    public TokenAcquirer(final int connectTimeoutMs, final int socketTimeoutMs,
                         final EntityScopeProvider entityScopeProvider,
                         final java.time.Clock clock) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.socketTimeoutMs = socketTimeoutMs;
        this.entityScopeProvider = entityScopeProvider;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    /**
     * Autowire setter for {@link EntityScopeProvider}. {@code required=false}
     * lets test contexts that import component-dao.xml without defining an
     * {@code entityScopeProvider} bean still construct this bean cleanly.
     * When the provider is absent, {@link #buildScope} falls back to
     * {@link EmptyScope} for SCV/ENV lookups -- enough for the bean wiring
     * to succeed; full SCV/ENV interpolation requires the real provider.
     */
    @Autowired(required = false)
    public void setEntityScopeProvider(final EntityScopeProvider entityScopeProvider) {
        this.entityScopeProvider = entityScopeProvider;
    }

    /**
     * Resolves any {@code ${scv:...}}, {@code ${env:...}} placeholders in
     * the given string. If a {@code callerScope} is supplied (typically
     * the per-node scope of the in-flight collection), node-scoped
     * placeholders like {@code ${node:label}} or {@code ${requisition:...}}
     * are also resolved. {@code ${token:...}} self-references inside an
     * auth definition are guarded against by {@link TokenAuthCollectorAdaptor}'s
     * re-entrancy check, not by excluding it from the chain.
     *
     * <p>Always runs interpolation, even if no provider and no caller
     * scope are available -- the resulting chain is just empty scopes
     * in that case, which causes any placeholder to resolve as empty
     * per the metadata DSL's empty-on-miss semantics. This is the safe
     * choice over returning the raw text: if the auth runtime is in a
     * partial-init state, an unresolved {@code ${scv:foo}} should
     * surface visibly (an empty credential the auth server rejects)
     * rather than be silently transmitted on the wire as literal text.</p>
     */
    String interpolate(final String text, final Scope callerScope) {
        if (text == null) {
            return text;
        }
        final Scope chain = buildScope(callerScope);
        return Interpolator.interpolate(text, chain).output;
    }

    /** Backwards-compat single-argument variant -- no caller scope. */
    String interpolate(final String text) {
        return interpolate(text, null);
    }

    private Scope buildScope(final Scope callerScope) {
        final Scope scv = entityScopeProvider != null
                ? firstNonNull(entityScopeProvider.getScopeForScv(), EmptyScope.EMPTY)
                : EmptyScope.EMPTY;
        final Scope env = entityScopeProvider != null
                ? firstNonNull(entityScopeProvider.getScopeForEnv(), EmptyScope.EMPTY)
                : EmptyScope.EMPTY;
        if (callerScope == null) {
            return new FallbackScope(scv, env);
        }
        return new FallbackScope(scv, env, callerScope);
    }

    private static Scope firstNonNull(final Scope a, final Scope b) {
        return a != null ? a : b;
    }

    /**
     * Computes a cache fingerprint from the resolved auth definition.
     * Two auth definitions that resolve to the same URL, credentials,
     * and content body produce the same fingerprint -- which is what
     * lets {@code N} nodes pointing at the same regional endpoint share
     * one cached token.
     *
     * <p>Uses SHA-256 rather than {@code String.hashCode}: a 32-bit
     * hash collision would cause one tenant's request to receive
     * another tenant's cached token, so the hash needs to be
     * collision-resistant in the cryptographic sense, not just
     * statistically uniform.</p>
     *
     * <p>Headers are included by name+value with insertion order
     * preserved. {@link TokenAuth} fields that don't change the resulting
     * token (timeouts, SSL flags, token-from config) are excluded.</p>
     */
    public String fingerprint(final TokenAuth auth, final Scope callerScope) {
        final StringBuilder sb = new StringBuilder();
        sb.append(interpolate(auth.getUrl(), callerScope)).append('\0');
        // TokenAuth.getMethod() returns the configured method or its
        // DEFAULT_METHOD ("POST") when <method> is omitted, so we always
        // fingerprint the *effective* method. Implicit and explicit POST
        // configs therefore share a cache key, as they should.
        sb.append(auth.getMethod()).append('\0');
        if (auth.getBasicAuth() != null) {
            sb.append(interpolate(auth.getBasicAuth().getUsername(), callerScope)).append('\0');
            sb.append(interpolate(auth.getBasicAuth().getPassword(), callerScope)).append('\0');
        } else {
            sb.append('\0').append('\0');
        }
        if (auth.getHeaders() != null) {
            for (final org.opennms.netmgt.config.tokenauth.Header h : auth.getHeaders()) {
                sb.append(h.getName() == null ? "" : h.getName()).append('=');
                sb.append(interpolate(h.getValue(), callerScope)).append('\0');
            }
        }
        if (auth.getContent() != null) {
            sb.append(auth.getContent().getType() == null ? "" : auth.getContent().getType()).append('\0');
            sb.append(interpolate(auth.getContent().getData(), callerScope));
        }
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(digest.length * 2);
            for (final byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every Java SE implementation per
            // the platform spec, so this branch is unreachable. Fall
            // back to the unhashed text rather than killing acquisition,
            // and let any collision misbehavior surface visibly.
            return sb.toString();
        }
    }

    /**
     * Executes the auth call described by {@code auth} and returns the
     * resulting cached token. Per-node placeholders inside {@code auth}
     * are not resolved on this path.
     *
     * <p><b>Subclassing note for test fakes:</b> if you override only
     * this single-arg variant, your override is invoked when the cache
     * calls with {@code callerScope == null}, but it is <b>bypassed</b>
     * when production code passes a scope (per-node case). Production
     * collects always pass a scope, so a fake that overrides only
     * this method will work for unit tests but not for any test that
     * exercises a scope-aware caller through a real
     * {@link TokenCache#getToken(TokenAuth, Scope)}. Override
     * {@link #acquire(TokenAuth, Scope)} as well -- or instead -- when
     * writing scope-aware fakes.</p>
     */
    public CachedToken acquire(final TokenAuth auth) throws IOException {
        return acquireInternal(auth, null);
    }

    /**
     * Executes the auth call described by {@code auth}, resolving
     * placeholders against {@code callerScope} (in addition to SCV and
     * env), and returns the resulting cached token.
     *
     * <p>When {@code callerScope} is {@code null} this delegates to
     * {@link #acquire(TokenAuth)} so subclasses that override only the
     * legacy single-argument variant continue to work for the
     * non-scope path. When a scope is present, the full implementation
     * (with per-node placeholder resolution) is used; subclass
     * overrides of {@link #acquire(TokenAuth)} are <b>bypassed</b> on this
     * path. Test fakes that need to short-circuit acquisition for
     * scope-aware callers must override this two-arg method, not the
     * single-arg one.</p>
     *
     * @throws IOException on network errors, non-2xx status, or token
     *                     extraction failure
     */
    public CachedToken acquire(final TokenAuth auth, final Scope callerScope) throws IOException {
        if (callerScope == null) {
            return acquire(auth);
        }
        return acquireInternal(auth, callerScope);
    }

    private CachedToken acquireInternal(final TokenAuth auth, final Scope callerScope) throws IOException {
        if (auth.getUrl() == null || auth.getUrl().isEmpty()) {
            throw new IOException("auth definition '" + auth.getName() + "' has no <url>");
        }
        if (auth.getTokenFrom() == null) {
            throw new IOException("auth definition '" + auth.getName() + "' has no <token-from>");
        }

        final HttpRequestBase request = buildRequest(auth, callerScope);
        try (HttpClientWrapper client = configureClient(auth);
             CloseableHttpResponse response = client.execute(request)) {
            final int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("auth call to " + auth.getUrl()
                        + " returned status " + status);
            }
            final String token = extractToken(auth.getTokenFrom(), response);
            return new CachedToken(token, computeExpiresAt(auth));
        }
    }

    /**
     * Builds an {@link HttpClientWrapper} configured per the auth
     * definition's SSL/proxy/timeout settings.
     */
    private HttpClientWrapper configureClient(final TokenAuth auth) throws IOException {
        final HttpClientWrapper wrapper = HttpClientWrapper.create()
                .setConnectionTimeout(connectTimeoutMs)
                .setSocketTimeout(socketTimeoutMs);
        if (auth.isUseSystemProxy()) {
            wrapper.useSystemProxySettings();
        }
        if (auth.isDisableSslVerification()) {
            try {
                wrapper.useRelaxedSSL("https");
            } catch (final GeneralSecurityException e) {
                throw new IOException(
                        "failed to configure relaxed SSL for auth '" + auth.getName() + "'", e);
            }
        }
        return wrapper;
    }

    private HttpRequestBase buildRequest(final TokenAuth auth, final Scope callerScope) throws IOException {
        final String method = auth.getMethod();
        final String url = interpolate(auth.getUrl(), callerScope);
        final HttpRequestBase request;
        if ("GET".equalsIgnoreCase(method)) {
            request = new HttpGet(url);
        } else if ("POST".equalsIgnoreCase(method)) {
            request = new HttpPost(url);
        } else if ("PUT".equalsIgnoreCase(method)) {
            request = new HttpPut(url);
        } else {
            throw new IOException("unsupported HTTP method '" + method
                    + "' in auth definition '" + auth.getName() + "'");
        }

        if (auth.getBasicAuth() != null) {
            final String userPass = interpolate(auth.getBasicAuth().getUsername(), callerScope) + ":"
                    + interpolate(auth.getBasicAuth().getPassword(), callerScope);
            final String encoded = Base64.getEncoder()
                    .encodeToString(userPass.getBytes(StandardCharsets.UTF_8));
            request.setHeader("Authorization", "Basic " + encoded);
        }

        for (final org.opennms.netmgt.config.tokenauth.Header h : auth.getHeaders()) {
            request.setHeader(h.getName(), interpolate(h.getValue(), callerScope));
        }

        if (auth.getContent() != null && request instanceof HttpEntityEnclosingRequestBase) {
            final org.opennms.netmgt.config.tokenauth.Content c = auth.getContent();
            final StringEntity entity = new StringEntity(
                    c.getData() == null ? "" : interpolate(c.getData(), callerScope),
                    StandardCharsets.UTF_8);
            if (c.getType() != null && !c.getType().isEmpty()) {
                entity.setContentType(c.getType());
            }
            ((HttpEntityEnclosingRequestBase) request).setEntity(entity);
        }

        return request;
    }

    private String extractToken(final TokenFrom tf,
                                final HttpResponse response) throws IOException {
        // Header extraction does not consume the body; check it first.
        if (tf.getHeader() != null && !tf.getHeader().isEmpty()) {
            final Header h = response.getFirstHeader(tf.getHeader());
            if (h == null) {
                throw new IOException("auth response missing header: " + tf.getHeader());
            }
            return h.getValue();
        }

        final HttpEntity entity = response.getEntity();
        final String body = entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);

        if (tf.isBodyAsToken()) {
            String trimmed = body.trim();
            // Some APIs (notably vSphere v8) return a JSON-encoded bare string
            // as the body. Strip surrounding double quotes when present.
            if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
            if (trimmed.isEmpty()) {
                throw new IOException("auth response body was empty");
            }
            return trimmed;
        }

        if (tf.getJsonpath() != null && !tf.getJsonpath().isEmpty()) {
            return extractJsonPath(body, tf.getJsonpath());
        }

        throw new IOException("token-from must specify jsonpath, header, or body-as-token=\"true\"");
    }

    /**
     * Walks a slash-separated path through a JSON document. For example,
     * {@code token/token} returns the {@code token} field of the {@code token}
     * field of the root object.
     */
    private String extractJsonPath(final String body, final String path) throws IOException {
        JsonNode node = JSON.readTree(body);
        for (final String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            node = node.path(segment);
        }
        if (node.isMissingNode() || node.isNull()) {
            throw new IOException("jsonpath '" + path + "' did not resolve in auth response");
        }
        return node.asText();
    }

    /**
     * Refresh-buffer floor: tokens get cached for {@code ttl-seconds}
     * minus this many seconds so we never hand back a token that's
     * within 10 minutes of expiring upstream.
     */
    private static final long REFRESH_BUFFER_FLOOR_SECONDS = 600L;

    /**
     * Refresh-buffer fraction: alternatively, we shorten by 5% of the
     * configured TTL when that's larger than the 10-minute floor. So
     * e.g. ttl=1h refreshes 10 min early, ttl=24h refreshes 72 min
     * early. {@code max(floor, fraction)} -- whichever is larger.
     */
    private static final double REFRESH_BUFFER_FRACTION = 0.05;

    private Instant computeExpiresAt(final TokenAuth auth) {
        final Long ttl = auth.getTtlSeconds();
        if (ttl == null || ttl <= 0) {
            return null;
        }
        // Refresh shortly before the upstream-issued expiry so a
        // collection never picks up a token that's about to be rejected.
        // Whichever buffer is larger wins: 10 minutes or 5% of TTL.
        final long fractional = (long) Math.ceil(ttl * REFRESH_BUFFER_FRACTION);
        final long buffer = Math.max(REFRESH_BUFFER_FLOOR_SECONDS, fractional);
        // Guard against tiny TTLs where the buffer would put us in the
        // past: clamp the effective remaining time to 1 second so we
        // still cache the token for at least one request.
        final long effective = Math.max(1L, ttl - buffer);
        return clock.instant().plusSeconds(effective);
    }
}
