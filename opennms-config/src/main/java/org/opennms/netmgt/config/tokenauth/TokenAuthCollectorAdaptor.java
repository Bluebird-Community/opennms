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

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.bind.annotation.XmlRootElement;

import org.opennms.core.mate.api.EntityScopeProvider;
import org.opennms.core.mate.api.FallbackScope;
import org.opennms.core.mate.api.Interpolator;
import org.opennms.core.mate.api.Scope;
import org.opennms.core.utils.InetAddressUtils;
import org.opennms.core.xml.JaxbUtils;
import org.opennms.netmgt.collection.api.CollectionAgent;
import org.opennms.netmgt.collection.api.CollectionSet;
import org.opennms.netmgt.collection.api.CollectionStatus;
import org.opennms.netmgt.collection.api.CollectorAdaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link CollectorAdaptor} that handles {@code ${token:<name>}}
 * placeholders in collection parameters and runtime attributes.
 *
 * <p>Two substitution paths run on the controller before Mate's
 * {@code Interpolator}:</p>
 * <ul>
 *   <li>{@link #beforeCollect} walks the flat collection-parameter
 *       map (values from {@code collectd-configuration.xml}
 *       {@code <parameter value="...">}).</li>
 *   <li>{@link #beforeRuntimeInterpolation} walks the runtime-attribute
 *       map, including JAXB-deserialized DTOs (e.g. the
 *       {@code <xml-source>} block of {@code xml-datacollection-config.xml}
 *       where auth headers live). JAXB values are marshaled to XML,
 *       substituted, and unmarshaled back -- mirroring how Mate's
 *       {@code Interpolator.interpolate(Object,Scope)} already handles
 *       {@code @XmlRootElement} values.</li>
 * </ul>
 *
 * <p>Both paths resolve through {@link TokenProvider} against a
 * per-call {@link FallbackScope} built from the agent's node and
 * interface, so the auth definition itself can reference
 * {@code ${node:...}}, {@code ${interface:...}}, {@code ${scv:...}},
 * and {@code ${env:...}}.</p>
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

    /**
     * Walks the runtime-attribute tree and substitutes
     * {@code ${token:<name>}} placeholders BEFORE Mate's
     * {@code Interpolator} runs. This is the path for placeholders
     * inside JAXB-deserialized DTOs such as the {@code <xml-source>}
     * blocks in {@code xml-datacollection-config.xml}.
     *
     * <p>For each value in the runtime-attributes map:
     * <ul>
     *   <li>{@code null} or non-String non-JAXB values pass through unchanged.</li>
     *   <li>{@code String} values are regex-substituted directly.</li>
     *   <li>JAXB objects (carrying {@code @XmlRootElement}) are marshaled
     *       to XML, substituted in the string form, and unmarshaled back.
     *       This mirrors how {@code Interpolator.interpolate(Object,Scope)}
     *       walks JAXB trees -- reusing the same shape avoids a hand-rolled
     *       reflection walker and the marshal/unmarshal would happen anyway
     *       in Mate's subsequent pass.</li>
     * </ul>
     * </p>
     *
     * <p>Any auth name resolved during this walk is also appended to
     * {@link #AUTH_NAMES_USED_PARAM} on the returned map so
     * {@link #handleCollectionResult} can invalidate the right cache
     * entries on FAILED collections.</p>
     */
    @Override
    public Map<String, Object> beforeRuntimeInterpolation(final CollectionAgent agent,
                                                          final Map<String, Object> runtimeAttributes) {
        if (runtimeAttributes == null || runtimeAttributes.isEmpty()) {
            return runtimeAttributes;
        }
        if (!containsTokenPlaceholder(runtimeAttributes.values())) {
            return runtimeAttributes;
        }

        final Scope scope = buildScope(agent);
        final Set<String> namesUsed = new LinkedHashSet<>();
        final Map<String, Object> resolved = new HashMap<>(runtimeAttributes);
        for (final Map.Entry<String, Object> entry : runtimeAttributes.entrySet()) {
            final Object value = entry.getValue();
            final Object substituted = substituteInValue(value, scope, namesUsed);
            if (substituted != value) {
                resolved.put(entry.getKey(), substituted);
            }
        }

        if (!namesUsed.isEmpty()) {
            // Merge with any names already stashed by beforeCollect.
            final Object existing = resolved.get(AUTH_NAMES_USED_PARAM);
            final LinkedHashSet<String> combined = new LinkedHashSet<>();
            if (existing instanceof String && !((String) existing).isEmpty()) {
                for (final String name : ((String) existing).split(",")) {
                    final String trimmed = name.trim();
                    if (!trimmed.isEmpty()) {
                        combined.add(trimmed);
                    }
                }
            }
            combined.addAll(namesUsed);
            resolved.put(AUTH_NAMES_USED_PARAM, String.join(",", combined));
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

    /**
     * Class name of Mate's package-private
     * {@code Interpolator.ToBeInterpolated} wrapper. Collectors (e.g.
     * {@code XmlCollector}) wrap their runtime-attribute values with
     * {@link Interpolator#pleaseInterpolate(Object)} so the standard
     * pass knows to recurse into them. The token-auth adaptor needs to
     * see the wrapped value too, but the class itself isn't exported.
     * Resolved reflectively at static-init time -- a single one-shot
     * lookup, no per-call cost.
     */
    private static final Class<?> TO_BE_INTERPOLATED_CLASS;
    private static final java.lang.reflect.Field TO_BE_INTERPOLATED_VALUE_FIELD;

    static {
        Class<?> klass = null;
        java.lang.reflect.Field field = null;
        try {
            klass = Class.forName("org.opennms.core.mate.api.Interpolator$ToBeInterpolated");
            field = klass.getDeclaredField("value");
            field.setAccessible(true);
        } catch (final ClassNotFoundException | NoSuchFieldException e) {
            LoggerFactory.getLogger(TokenAuthCollectorAdaptor.class)
                    .warn("Mate Interpolator.ToBeInterpolated not reachable; "
                            + "token-auth substitution will only see top-level Map values "
                            + "(not their JAXB-wrapped innards).", e);
        }
        TO_BE_INTERPOLATED_CLASS = klass;
        TO_BE_INTERPOLATED_VALUE_FIELD = field;
    }

    /**
     * Returns {@code true} if any value in the iterable contains the
     * token placeholder prefix. Strings are checked directly; JAXB
     * objects are marshaled to XML for the check. The marshal cost
     * is the same one Mate's {@code Interpolator} pays on the value
     * anyway, so this isn't an extra round trip for JAXB types -- we
     * just gate further work on it.
     */
    private static boolean containsTokenPlaceholder(final Collection<?> values) {
        for (final Object v : values) {
            if (mayContainTokenPlaceholder(v)) {
                return true;
            }
        }
        return false;
    }

    private static boolean mayContainTokenPlaceholder(final Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof String) {
            return ((String) v).contains("${token:");
        }
        if (TO_BE_INTERPOLATED_CLASS != null && TO_BE_INTERPOLATED_CLASS.isInstance(v)) {
            return mayContainTokenPlaceholder(unwrap(v));
        }
        if (v.getClass().isAnnotationPresent(XmlRootElement.class)) {
            final String marshaled = JaxbUtils.marshal(v);
            return marshaled != null && marshaled.contains("${token:");
        }
        return false;
    }

    private static Object unwrap(final Object toBeInterpolated) {
        try {
            return TO_BE_INTERPOLATED_VALUE_FIELD.get(toBeInterpolated);
        } catch (final IllegalAccessException e) {
            return null;
        }
    }

    /**
     * Returns a copy of {@code value} with all {@code ${token:<name>}}
     * occurrences substituted. Strings are substituted directly; JAXB
     * objects round-trip via {@code JaxbUtils.marshal/unmarshal}; Mate
     * {@code ToBeInterpolated} wrappers are unwrapped, recursed into,
     * and re-wrapped with {@link Interpolator#pleaseInterpolate(Object)}
     * so the standard pass continues to process the value normally.
     * For values that contain no placeholder, the original reference
     * is returned (referential equality lets the caller skip the
     * map put).
     */
    private Object substituteInValue(final Object value, final Scope scope, final Set<String> namesUsed) {
        if (value instanceof String) {
            final String stringValue = (String) value;
            if (!stringValue.contains("${token:")) {
                return value;
            }
            return substitute(stringValue, scope, namesUsed);
        }
        if (value != null && TO_BE_INTERPOLATED_CLASS != null && TO_BE_INTERPOLATED_CLASS.isInstance(value)) {
            final Object inner = unwrap(value);
            if (inner == null) {
                return value;
            }
            final Object substituted = substituteInValue(inner, scope, namesUsed);
            if (substituted == inner) {
                return value;
            }
            return Interpolator.pleaseInterpolate(substituted);
        }
        if (value != null && value.getClass().isAnnotationPresent(XmlRootElement.class)) {
            final String marshaled = JaxbUtils.marshal(value);
            if (marshaled == null || !marshaled.contains("${token:")) {
                return value;
            }
            final String substituted = substitute(marshaled, scope, namesUsed);
            return JaxbUtils.unmarshal(value.getClass(), substituted, false);
        }
        return value;
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
