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
package org.opennms.netmgt.collection.api;

import java.util.Map;

/**
 * Cross-cutting hook for a {@link ServiceCollector} dispatch. Adaptors
 * run on the OpenNMS controller and wrap a collection with pre- and
 * post-RPC behavior without modifying the collector itself.
 *
 * <p>Modeled on {@code ServiceMonitorAdaptor}. Register adaptors on a
 * request via {@code CollectorRequestBuilder.withAdaptor(...)}; they
 * are invoked in registration order.</p>
 *
 * <p>Adaptors never run on the Minion. Any state they need (token
 * providers, DAO lookups, etc.) is resolved on the controller before
 * the request is marshaled across the RPC boundary.</p>
 */
public interface CollectorAdaptor {

    /**
     * Invoked on the controller after the collector's runtime
     * attributes are resolved and merged, but before the request is
     * dispatched (locally or via RPC to a Minion). Implementations may
     * return a possibly-modified parameter map; the returned map is
     * what the collector ultimately sees.
     */
    default Map<String, Object> beforeCollect(final CollectionAgent agent,
                                              final Map<String, Object> parameters) {
        return parameters;
    }

    /**
     * Invoked on the controller after the collector's runtime
     * attributes are fetched but BEFORE Mate's {@code Interpolator}
     * walks them. Mate's standard pass resolves only the prefixes it
     * knows ({@code node}, {@code interface}, {@code asset}, etc.) and
     * strips any unknown prefix to an empty string -- so adaptors that
     * own a custom prefix (e.g. {@code token:}) must do their own
     * substitution here, before Mate sees the tree.
     *
     * <p>The values in {@code runtimeAttributes} may include JAXB
     * objects (annotated {@code @XmlRootElement}) representing
     * collection-config snippets such as the {@code <xml-source>} block
     * of an {@code xml-datacollection-config.xml}. Implementations that
     * resolve placeholders inside those trees can marshal the object to
     * XML, substitute, and unmarshal -- the same shape Mate's
     * {@code Interpolator.interpolate(Object, Scope)} uses internally.</p>
     */
    default Map<String, Object> beforeRuntimeInterpolation(final CollectionAgent agent,
                                                           final Map<String, Object> runtimeAttributes) {
        return runtimeAttributes;
    }

    /**
     * Invoked on the controller after the collection future resolves,
     * before the result reaches the original caller. Implementations
     * may inspect or replace the {@link CollectionSet} -- e.g. to
     * invalidate a controller-side cache on a failed result.
     */
    default CollectionSet handleCollectionResult(final CollectionAgent agent,
                                                 final Map<String, Object> parameters,
                                                 final CollectionSet result) {
        return result;
    }
}
