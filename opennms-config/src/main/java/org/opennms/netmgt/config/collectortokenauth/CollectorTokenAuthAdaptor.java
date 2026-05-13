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
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.opennms.netmgt.collection.api.CollectionAgent;
import org.opennms.netmgt.collection.api.CollectionSet;
import org.opennms.netmgt.collection.api.CollectionStatus;
import org.opennms.netmgt.collection.api.CollectorAdaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link CollectorAdaptor} for token-based authentication.
 *
 * <p>Token placeholders ({@code ${token:<name>}}) are resolved by
 * {@link TokenAuthScope} during Mate parameter interpolation, which
 * runs before the adaptor is invoked. The adaptor's role here is
 * narrower: drain the per-thread record of which auth names
 * participated in this collection (left behind by
 * {@link TokenAuthScope}), carry that record across the dispatch
 * boundary in a parameter, then -- on the way back -- invalidate the
 * matching cache entries when the collection fails.</p>
 *
 * <p>Invalidation is passive: the adaptor does not re-issue the
 * request. The next collection cycle picks a fresh token naturally.</p>
 */
public class CollectorTokenAuthAdaptor implements CollectorAdaptor {

    private static final Logger LOG = LoggerFactory.getLogger(CollectorTokenAuthAdaptor.class);

    /**
     * Key used to stash the set of auth names that participated in a
     * given request, so {@link #handleCollectionResult} can invalidate
     * the right entries on failure. Internal; not part of any public
     * parameter contract.
     */
    static final String AUTH_NAMES_USED_PARAM = "__opennms.collectortokenauth.names";

    private final TokenProvider tokenProvider;

    public CollectorTokenAuthAdaptor(final TokenProvider tokenProvider) {
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
    }

    @Override
    public Map<String, Object> beforeCollect(final CollectionAgent agent,
                                             final Map<String, Object> parameters) {
        if (parameters == null) {
            return null;
        }
        // CollectionSpecification#getServiceParameters wraps the result of
        // Interpolator.interpolateObjects in a Guava transformed view, so
        // values are only interpolated when read. Materialize the map here
        // so TokenAuthScope (chained into the interpolator's scope) gets a
        // chance to record any ${token:<name>} placeholders that were
        // substituted -- regardless of whether the downstream collector
        // ends up reading those particular keys.
        final Map<String, Object> materialized = new HashMap<>(parameters);
        final Set<String> namesUsed = TokenAuthScope.takeNamesUsed();
        if (namesUsed.isEmpty()) {
            return materialized;
        }
        materialized.put(AUTH_NAMES_USED_PARAM, namesUsed);
        return materialized;
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
}
