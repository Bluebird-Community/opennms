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

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link CollectorAdaptor} that handles {@code ${token:<name>}}
 * placeholders in collection parameters.
 *
 * <p>{@link #beforeCollect} runs before Mate's {@code Interpolator}
 * (CollectorRequestBuilderImpl invokes adaptors first); it scans the
 * raw parameter map for {@code ${token:<name>}} occurrences,
 * resolves each through {@link TokenProvider}, and substitutes the
 * token value inline. Resolution uses a per-call {@link FallbackScope}
 * built from the agent's node and interface so the auth definition
 * itself can reference {@code ${node:...}}, {@code ${interface:...}},
 * {@code ${scv:...}}, and {@code ${env:...}}.</p>
 *
 * <p>{@link #handleCollectionResult} invalidates the cache entry for
 * any auth name that participated in the request when the result
 * status is FAILED. Invalidation is passive: the next cycle
 * re-acquires naturally.</p>
 */
public class TokenAuthCollectorAdaptor implements CollectorAdaptor {

    private static final Logger LOG = LoggerFactory.getLogger(TokenAuthCollectorAdaptor.class);

    /**
     * {@code ${token:<name>}} or {@code ${token:<name>|<fallback>}}.
     */
    private static final Pattern TOKEN_PLACEHOLDER =
            Pattern.compile("\\$\\{token:([^|}]+)(?:\\|([^}]*))?\\}");

    /**
     * Key under which {@link #beforeCollect} stashes the comma-separated
     * list of auth names resolved during substitution, so
     * {@link #handleCollectionResult} can invalidate the right entries on
     * failure. Stored as a String for marshal safety across the RPC
     * boundary; non-String values would break
     * {@code AbstractRemoteServiceCollector.marshalParameters()}.
     */
    static final String AUTH_NAMES_USED_PARAM = "__opennms.tokenauth.names";

    private final TokenProvider tokenProvider;
    private volatile EntityScopeProvider entityScopeProvider;

    public TokenAuthCollectorAdaptor(final TokenProvider tokenProvider) {
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
    }

    /**
     * Optional {@link EntityScopeProvider} for resolving per-node /
     * per-interface placeholders inside the auth definition. Marked
     * {@code required = false} so test contexts that don't wire one
     * still construct the bean cleanly; in that case substitution
     * runs against an empty scope chain.
     */
    @Autowired(required = false)
    public void setEntityScopeProvider(final EntityScopeProvider entityScopeProvider) {
        this.entityScopeProvider = entityScopeProvider;
    }

    @Override
    public Map<String, Object> beforeCollect(final CollectionAgent agent,
                                             final Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return parameters;
        }

        // Quick scan: only allocate if a string value contains the
        // token placeholder. Cheaper than building scope/regex state
        // for collections that don't use this feature.
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

        final Scope scope = buildScope(agent);
        final Set<String> namesUsed = new LinkedHashSet<>();
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
            resolved.put(AUTH_NAMES_USED_PARAM, String.join(",", namesUsed));
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
        if (parameters == null) {
            return result;
        }
        final Object raw = parameters.get(AUTH_NAMES_USED_PARAM);
        if (!(raw instanceof String) || ((String) raw).isEmpty()) {
            return result;
        }
        for (final String name : ((String) raw).split(",")) {
            final String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            tokenProvider.invalidate(trimmed);
            LOG.debug("Invalidated token cache for auth '{}' after failed collection (node {})",
                    trimmed, agent != null ? agent.getNodeId() : null);
        }
        return result;
    }

    private Scope buildScope(final CollectionAgent agent) {
        if (entityScopeProvider == null || agent == null) {
            return org.opennms.core.mate.api.EmptyScope.EMPTY;
        }
        final Integer nodeId = agent.getNodeId();
        final String address = agent.getAddress() != null
                ? InetAddressUtils.toIpAddrString(agent.getAddress())
                : null;
        if (nodeId == null) {
            return org.opennms.core.mate.api.EmptyScope.EMPTY;
        }
        if (address == null) {
            return entityScopeProvider.getScopeForNode(nodeId);
        }
        return new FallbackScope(
                entityScopeProvider.getScopeForNode(nodeId),
                entityScopeProvider.getScopeForInterface(nodeId, address));
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
