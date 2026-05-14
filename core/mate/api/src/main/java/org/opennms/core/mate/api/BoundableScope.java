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
package org.opennms.core.mate.api;

/**
 * Extension hook for {@link Scope} implementations that need access to
 * the surrounding scope chain. Scope assembly points (for example,
 * an {@code EntityScopeProvider} composing per-node and per-interface
 * scopes) may call {@link #bind(Scope)} after building the base chain
 * to give a scope its per-call ambient context.
 *
 * <p>Typical use case: a scope that resolves placeholders by calling
 * out to an external system whose configuration may itself contain
 * placeholders. The bound ambient lets the called-out configuration
 * be interpolated against the same node/interface/asset/SCV/ENV
 * context as the rest of the collection cycle.</p>
 *
 * <p>Implementations should treat {@code bind} as a factory: return
 * a new {@code Scope} instance with the supplied ambient, leaving the
 * receiver unchanged. Returning {@code this} when the implementation
 * doesn't need the ambient is also fine.</p>
 */
public interface BoundableScope {

    /**
     * Returns a {@link Scope} identical to this one except that its
     * ambient lookup chain has been replaced with {@code ambient}.
     *
     * @param ambient the per-call scope chain to bind, typically built
     *        from node, interface, asset, SCV, and ENV scopes
     * @return a Scope that uses {@code ambient} for inner lookups
     */
    Scope bind(Scope ambient);
}
