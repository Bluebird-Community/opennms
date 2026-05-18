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
package org.opennms.netmgt.collection.client.rpc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.opennms.core.mate.api.FallbackScope;
import org.opennms.core.mate.api.Interpolator;
import org.opennms.core.mate.api.MetadataConstants;
import org.opennms.core.mate.api.Scope;
import org.opennms.core.rpc.api.RpcRequest;
import org.opennms.core.rpc.api.RpcTarget;
import org.opennms.core.utils.InetAddressUtils;
import org.opennms.core.utils.ParameterMap;
import org.opennms.netmgt.collection.api.CollectionAgent;
import org.opennms.netmgt.collection.api.CollectionSet;
import org.opennms.netmgt.collection.api.CollectorAdaptor;
import org.opennms.netmgt.collection.api.CollectorRequestBuilder;
import org.opennms.netmgt.collection.api.ServiceCollector;
import org.opennms.netmgt.collection.dto.CollectionAgentDTO;
import org.opennms.netmgt.dao.api.MonitoringLocationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CollectorRequestBuilderImpl implements CollectorRequestBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(CollectorRequestBuilderImpl.class);

    private final LocationAwareCollectorClientImpl client;

    private final Map<String, Object> attributes = new HashMap<>();

    private CollectionAgent agent;

    private String systemId;

    private ServiceCollector serviceCollector;

    private Long ttlInMs;

    private String className;

    private final List<CollectorAdaptor> adaptors = new ArrayList<>();

    public CollectorRequestBuilderImpl(LocationAwareCollectorClientImpl client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public CollectorRequestBuilder withAgent(CollectionAgent agent) {
        this.agent = agent;
        return this;
    }

    @Override
    public CollectorRequestBuilder withSystemId(String systemId) {
        this.systemId = systemId;
        return this;
    }

    @Override
    public CollectorRequestBuilder withCollector(ServiceCollector collector) {
        this.serviceCollector = collector;
        return this;
    }

    @Override
    public CollectorRequestBuilder withCollectorClassName(String className) {
        this.className = className;
        this.serviceCollector = client.getRegistry().getCollectorFutureByClassName(className).getNow(null);
        return this;
    }

    @Override
    public CollectorRequestBuilder withTimeToLive(Long ttlInMs) {
        this.ttlInMs = ttlInMs;
        return this;
    }

    @Override
    public CollectorRequestBuilder withAttribute(String key, Object value) {
        this.attributes.put(key, value);
        return this;
    }

    @Override
    public CollectorRequestBuilder withAttributes(Map<String, Object> attributes) {
        this.attributes.putAll(attributes);
        return this;
    }

    @Override
    public CollectorRequestBuilder withAdaptor(CollectorAdaptor adaptor) {
        if (adaptor != null) {
            this.adaptors.add(adaptor);
        }
        return this;
    }

    @Override
    public CompletableFuture<CollectionSet> execute() {
        if (serviceCollector == null) {
            throw new IllegalArgumentException("Collector or collector class name is required.");
        } else if (agent == null) {
            throw new IllegalArgumentException("Agent is required.");
        }

        final Scope scope = new FallbackScope(
                this.client.getEntityScopeProvider().getScopeForNode(agent.getNodeId()),
                this.client.getEntityScopeProvider().getScopeForInterface(agent.getNodeId(), InetAddressUtils.toIpAddrString(agent.getAddress()))
        );

        // Give each adaptor a chance to transform the raw attribute map
        // before Mate's Interpolator runs. Adaptors that resolve custom
        // ${prefix:...} placeholders (e.g. token-auth) need to see them
        // intact -- Interpolator strips unrecognized prefixes to empty,
        // so adaptors must run first. Adaptors are controller-only;
        // their output is what Mate then interpolates and what gets
        // marshaled across to the Minion.
        Map<String, Object> preInterpolated = attributes;
        for (final CollectorAdaptor adaptor : adaptors) {
            final Map<String, Object> next = adaptor.beforeCollect(agent, preInterpolated);
            preInterpolated = next != null ? next : preInterpolated;
        }

        final Map<String, Object> interpolatedAttributes = Interpolator.interpolateObjects(preInterpolated, scope);

        final RpcTarget target = client.getRpcTargetHelper().target()
                .withNodeId(agent.getNodeId())
                .withLocation(agent.getLocationName())
                .withSystemId(systemId)
                .withServiceAttributes(interpolatedAttributes)
                .withLocationOverride((s) -> serviceCollector.getEffectiveLocation(s))
                .build();

        CollectorRequestDTO request = new CollectorRequestDTO();
        request.setLocation(target.getLocation());
        request.setSystemId(target.getSystemId());
        // For Service collectors that implement integration api will have proxy collectors.
        // fetching class name from proxy won't match with class name in collector registry so prefer clasName if it present.
        final String collectorClassName = className != null ? className : serviceCollector.getClass().getCanonicalName();
        request.setClassName(collectorClassName);
        // Overwrite if ttl exists in metadata.
        ttlInMs = ParameterMap.getLongValue(MetadataConstants.TTL, interpolatedAttributes.get(MetadataConstants.TTL), ttlInMs);
        request.setTimeToLiveMs(ttlInMs);
        request.addTracingInfo(RpcRequest.TAG_NODE_ID, String.valueOf(agent.getNodeId()));
        request.addTracingInfo(RpcRequest.TAG_NODE_LABEL, agent.getNodeLabel());
        request.addTracingInfo(RpcRequest.TAG_CLASS_NAME, collectorClassName);
        request.addTracingInfo(RpcRequest.TAG_IP_ADDRESS, InetAddressUtils.toIpAddrString(agent.getAddress()));

        // Retrieve the runtime attributes, which may include attributes
        // such as the agent details and other state related attributes
        // which should be included in the request.
        Map<String, Object> rawRuntimeAttributes = serviceCollector.getRuntimeAttributes(agent, interpolatedAttributes);
        // Let each adaptor walk the runtime-attribute tree first.
        // Adaptors that own a custom ${prefix:...} placeholder (e.g.
        // token-auth) need to substitute before Mate's Interpolator
        // sees the tree -- the standard pass strips unknown prefixes to
        // empty. Default impl is a no-op.
        for (final CollectorAdaptor adaptor : adaptors) {
            final Map<String, Object> next = adaptor.beforeRuntimeInterpolation(agent, rawRuntimeAttributes);
            rawRuntimeAttributes = next != null ? next : rawRuntimeAttributes;
        }
        final Map<String, Object> runtimeAttributes = Interpolator.interpolateAttributes(rawRuntimeAttributes, scope);
        final Map<String, Object> dispatchedAttributes = new HashMap<>();
        dispatchedAttributes.putAll(interpolatedAttributes);
        dispatchedAttributes.putAll(runtimeAttributes);

        // The runtime attributes may include objects which need to be marshaled.
        // Only marshal these if the request is being executed at another location.
        if (MonitoringLocationUtils.isDefaultLocationName(request.getLocation())) {
            // As-is
            request.setAgent(agent);
            request.addAttributes(dispatchedAttributes);
        } else {
            // Marshal
            request.setAgent(new CollectionAgentDTO(agent));
            final Map<String, String> marshaledParms = serviceCollector.marshalParameters(dispatchedAttributes);
            marshaledParms.forEach(request::addAttribute);
            request.setAttributesNeedUnmarshaling(true);
        }

        // Execute the request, then let each adaptor post-process the
        // result. Adaptors run in registration order; each may return a
        // possibly-modified CollectionSet.
        return client.getDelegate().execute(request).thenApply(response -> {
            CollectionSet result = response.getCollectionSet();
            for (final CollectorAdaptor adaptor : adaptors) {
                result = adaptor.handleCollectionResult(agent, dispatchedAttributes, result);
            }
            return result;
        });
    }

}
