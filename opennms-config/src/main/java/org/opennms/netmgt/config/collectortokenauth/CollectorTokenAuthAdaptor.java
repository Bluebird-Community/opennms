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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.opennms.core.mate.api.EntityScopeProvider;
import org.opennms.core.mate.api.FallbackScope;
import org.opennms.core.mate.api.Scope;
import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.collection.api.CollectionAgent;
import org.opennms.netmgt.collection.api.CollectionSet;
import org.opennms.netmgt.collection.api.CollectionStatus;
import org.opennms.netmgt.collection.api.CollectorAdaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link CollectorAdaptor} for token-based authentication.
 *
 * <p>{@link #beforeCollect} resolves {@code ${token:<name>}} placeholders
 * in the collection parameters using {@link TokenProvider}, on the
 * OpenNMS controller, before the request is marshaled across the RPC
 * boundary. Substituted values cross the wire; the Minion sees a
 * normal-looking parameter map with no token-aware code involved.</p>
 *
 * <p>{@link #handleCollectionResult} invalidates the token cache for any
 * auth name that participated in this collection when the result comes
 * back failed. The next collection cycle re-acquires automatically.
 * Passive invalidation rather than retry orchestration -- the adaptor
 * does not re-issue the RPC.</p>
 *
 * <p>Placeholder grammar: {@code ${token:<name>}} or
 * {@code ${token:<name>|<fallback>}}. Names matching no definition
 * substitute the fallback (or empty string when absent), matching the
 * legacy DSL-scope semantics.</p>
 */
public class CollectorTokenAuthAdaptor implements CollectorAdaptor {

    private static final Logger LOG = LoggerFactory.getLogger(CollectorTokenAuthAdaptor.class);

    private static final Pattern TOKEN_PLACEHOLDER =
            Pattern.compile("\\$\\{token:([^|}]+)(?:\\|([^}]*))?\\}");

    /**
     * Key used to stash the set of auth names that participated in a
     * given request, so {@link #handleCollectionResult} can invalidate
     * the right entries on failure. Internal; not part of any public
     * parameter contract.
     */
    static final String AUTH_NAMES_USED_PARAM = "__opennms.collectortokenauth.names";

    private final TokenProvider tokenProvider;
    private final EntityScopeProvider entityScopeProvider;

    public CollectorTokenAuthAdaptor(final TokenProvider tokenProvider,
                                     final EntityScopeProvider entityScopeProvider) {
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
        this.entityScopeProvider = Objects.requireNonNull(entityScopeProvider, "entityScopeProvider");
    }

    @Override
    public Map<String, Object> beforeCollect(final CollectionAgent agent,
                                             final Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return parameters;
        }

        // Quick scan: avoid allocating a new map when no value contains a
        // ${token:...} placeholder. Common case for collectors that don't
        // use this feature.
        boolean anyMatch = false;
        for (final Object v : parameters.values()) {
            if (v instanceof String && ((String) v).contains("${token:")) {
                anyMatch = true;
                break;
            }
        }
        if (!anyMatch) {
            return parameters;
        }

        final Scope scope = new FallbackScope(
                entityScopeProvider.getScopeForNode(agent.getNodeId()),
                entityScopeProvider.getScopeForInterface(
                        agent.getNodeId(),
                        InetAddressUtils.toIpAddrString(agent.getAddress())));

        final Set<String> namesUsed = new HashSet<>();
        final Map<String, Object> resolved = new HashMap<>(parameters);
        for (final Map.Entry<String, Object> entry : parameters.entrySet()) {
            final Object value = entry.getValue();
            if (!(value instanceof String)) {
                continue;
            }
            final String stringValue = (String) value;
            if (!stringValue.contains("${token:")) {
                continue;
            }
            resolved.put(entry.getKey(), substitute(stringValue, scope, namesUsed));
        }

        if (!namesUsed.isEmpty()) {
            resolved.put(AUTH_NAMES_USED_PARAM, namesUsed);
        }
        return resolved;
    }

    @Override
    public CollectionSet handleCollectionResult(final CollectionAgent agent,
                                                final Map<String, Object> parameters,
                                                final CollectionSet result) {
        if (result == null
                || result.getStatus() == null
                || result.getStatus() != CollectionStatus.FAILED) {
            return result;
        }
        @SuppressWarnings("unchecked")
        final Set<String> authNames = (Set<String>) parameters.get(AUTH_NAMES_USED_PARAM);
        if (authNames == null || authNames.isEmpty()) {
            return result;
        }
        for (final String name : authNames) {
            tokenProvider.invalidate(name);
            LOG.debug("Invalidated token cache for auth '{}' after failed collection (node {})",
                    name, agent.getNodeId());
        }
        return result;
    }

    private String substitute(final String value, final Scope scope, final Set<String> namesUsed) {
        final Matcher m = TOKEN_PLACEHOLDER.matcher(value);
        final StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            final String authName = m.group(1).trim();
            final String fallback = m.group(2);
            out.append(value, last, m.start());
            final String resolved = tokenProvider.getToken(authName, scope)
                    .orElse(fallback != null ? fallback : "");
            out.append(resolved);
            namesUsed.add(authName);
            last = m.end();
        }
        out.append(value, last, value.length());
        return out.toString();
    }
}
