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
import java.util.concurrent.CompletableFuture;

public interface CollectorRequestBuilder {

    CollectorRequestBuilder withAgent(CollectionAgent agent);

    CollectorRequestBuilder withSystemId(String systemId);

    CollectorRequestBuilder withCollector(ServiceCollector collector);

    CollectorRequestBuilder withCollectorClassName(String className);

    CollectorRequestBuilder withTimeToLive(Long ttlInMs);

    CollectorRequestBuilder withAttribute(String key, Object value);

    CollectorRequestBuilder withAttributes(Map<String, Object> attributes);

    /**
     * Registers a {@link CollectorAdaptor} to be invoked on the
     * controller before this request is dispatched (and after the
     * response returns). Default is a no-op so downstream
     * implementations don't have to opt in -- there is no point
     * forcing a binary-compatibility break on every existing
     * {@code CollectorRequestBuilder} for a feature most don't use.
     */
    default CollectorRequestBuilder withAdaptor(CollectorAdaptor adaptor) {
        return this;
    }

    CompletableFuture<CollectionSet> execute();

}
