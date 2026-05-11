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
package org.opennms.netmgt.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.opennms.core.utils.ConfigFileConstants;
import org.opennms.core.xml.JaxbUtils;
import org.opennms.netmgt.config.collectortokenauth.TokenAuth;
import org.opennms.netmgt.config.collectortokenauth.CollectorTokenAuthConfiguration;
import org.opennms.netmgt.config.collectortokenauth.TokenFrom;

/**
 * Singleton holding the parsed {@code auth-configuration.xml}.
 * Modeled on {@link VacuumdConfigFactory}.
 *
 * <p>Performs strict load-time validation: each {@code <auth>} block must
 * have a name, a URL, a {@code <token-from>}, and that {@code <token-from>}
 * must specify exactly one of {@code jsonpath}, {@code header}, or
 * {@code body-as-token=true}. Misconfigurations fail fast at startup
 * rather than producing confusing 401s on the first cache miss.</p>
 */
public final class CollectorTokenAuthConfigFactory {

    private static CollectorTokenAuthConfigFactory m_singleton;

    private final CollectorTokenAuthConfiguration m_config;

    public CollectorTokenAuthConfigFactory(final InputStream stream) {
        this(JaxbUtils.unmarshal(CollectorTokenAuthConfiguration.class, new InputStreamReader(stream)));
    }

    /**
     * Test-friendly constructor that takes an already-unmarshalled config.
     */
    public CollectorTokenAuthConfigFactory(final CollectorTokenAuthConfiguration config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        validate(config);
        m_config = config;
    }

    /**
     * Loads {@code etc/auth-configuration.xml} and installs the singleton.
     * Calling this method again is a no-op; use {@link #reload()} to pick
     * up changes on disk.
     */
    public static synchronized void init() throws IOException {
        if (m_singleton != null) {
            return;
        }
        try (InputStream is = new FileInputStream(
                ConfigFileConstants.getFile(ConfigFileConstants.COLLECTOR_TOKEN_AUTH_CONFIG_FILE_NAME))) {
            setInstance(new CollectorTokenAuthConfigFactory(is));
        }
    }

    /**
     * Reloads the config from disk. Parses and validates into a new
     * factory instance first, and only swaps {@code m_singleton} on
     * success -- a parse or validation failure leaves the previously
     * loaded configuration in effect, matching the documented behavior
     * (see auth-configuration.adoc, "Reload without restart"). The
     * caller is responsible for invalidating any token cache that
     * depends on the previous configuration.
     */
    public static synchronized void reload() throws IOException {
        try (InputStream is = new FileInputStream(
                ConfigFileConstants.getFile(ConfigFileConstants.COLLECTOR_TOKEN_AUTH_CONFIG_FILE_NAME))) {
            final CollectorTokenAuthConfigFactory next = new CollectorTokenAuthConfigFactory(is);
            setInstance(next);
        }
    }

    public static synchronized CollectorTokenAuthConfigFactory getInstance() {
        if (m_singleton == null) {
            throw new IllegalStateException("CollectorTokenAuthConfigFactory.init() has not been called");
        }
        return m_singleton;
    }

    /**
     * Test seam. Replaces the active singleton without going through
     * {@link #init()}. Package-private rather than public because a
     * caller outside this package could otherwise install a factory
     * whose {@link CollectorTokenAuthConfiguration} was mutated post-construction
     * (e.g. setting both {@code header} and {@code body-as-token});
     * validation only runs at construction. The reload path always
     * re-parses XML and re-validates, so production never reaches a
     * mutated state via legitimate means.
     */
    static synchronized void setInstance(final CollectorTokenAuthConfigFactory factory) {
        m_singleton = factory;
    }

    /**
     * Test-only helper to drop the active singleton so the next call
     * to {@link #init()} or {@link #reload()} reads from disk fresh.
     * Public visibility (in contrast to {@link #setInstance}) because
     * resetting to null does not bypass validation -- the next load
     * still goes through {@link #init()} and the constructor's
     * checks. Useful from test classes outside this package.
     */
    public static synchronized void resetForTesting() {
        m_singleton = null;
    }

    /**
     * Returns the {@link TokenAuth} definition with the given name, if any.
     */
    public synchronized Optional<TokenAuth> getAuth(final String name) {
        return m_config.getAuth(name);
    }

    public synchronized List<TokenAuth> getAuths() {
        return m_config.getAuths();
    }

    public synchronized CollectorTokenAuthConfiguration getConfig() {
        return m_config;
    }

    /**
     * Validates the entire configuration. Throws
     * {@link IllegalArgumentException} on the first problem found.
     */
    static void validate(final CollectorTokenAuthConfiguration config) {
        final Set<String> seen = new HashSet<>();
        for (final TokenAuth auth : config.getAuths()) {
            validate(auth);
            if (!seen.add(auth.getName())) {
                throw new IllegalArgumentException(
                        "duplicate auth definition name: '" + auth.getName() + "'");
            }
        }
    }

    static void validate(final TokenAuth auth) {
        if (auth.getName() == null || auth.getName().isEmpty()) {
            throw new IllegalArgumentException("auth definition is missing a name attribute");
        }
        if (auth.getUrl() == null || auth.getUrl().isEmpty()) {
            throw new IllegalArgumentException(
                    "auth definition '" + auth.getName() + "' has no <url>");
        }
        validateUrlScheme(auth.getName(), auth.getUrl());
        if (auth.getTokenFrom() == null) {
            throw new IllegalArgumentException(
                    "auth definition '" + auth.getName() + "' has no <token-from>");
        }
        validateTokenFrom(auth);
        if (auth.getBasicAuth() != null) {
            final org.opennms.netmgt.config.collectortokenauth.BasicAuth ba = auth.getBasicAuth();
            if (ba.getUsername() == null || ba.getPassword() == null) {
                throw new IllegalArgumentException(
                        "auth definition '" + auth.getName()
                                + "' has <basic-auth> without both username and password");
            }
        }
        if (auth.getTtlSeconds() != null && auth.getTtlSeconds() < 0) {
            throw new IllegalArgumentException(
                    "auth definition '" + auth.getName()
                            + "' has a negative <ttl-seconds>; use a positive value or omit the element");
        }
    }

    /**
     * Lightweight scheme check on the configured URL. Skipped when
     * the URL begins with a {@code ${...}} metadata placeholder --
     * those resolve at acquire time and cannot be inspected here.
     * Any literal scheme prefix that is not {@code http://} or
     * {@code https://} is rejected so a typo like {@code htttp://}
     * fails fast rather than producing a confusing acquire-time
     * exception later.
     */
    private static void validateUrlScheme(final String name, final String url) {
        if (url.startsWith("${")) {
            return;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return;
        }
        throw new IllegalArgumentException(
                "auth definition '" + name
                        + "' has a <url> that does not start with http:// or https://: "
                        + url);
    }

    private static void validateTokenFrom(final TokenAuth auth) {
        final TokenFrom tf = auth.getTokenFrom();
        int specified = 0;
        if (tf.getJsonpath() != null && !tf.getJsonpath().isEmpty()) {
            specified++;
        }
        if (tf.getHeader() != null && !tf.getHeader().isEmpty()) {
            specified++;
        }
        if (tf.isBodyAsToken()) {
            specified++;
        }
        if (specified != 1) {
            throw new IllegalArgumentException(
                    "auth definition '" + auth.getName()
                            + "' must specify exactly one of jsonpath, header, or body-as-token=\"true\""
                            + " on its <token-from>; got " + specified);
        }
    }

    /** Drops the singleton, primarily for tests. */
    static synchronized void clearForTest() {
        m_singleton = null;
    }
}
