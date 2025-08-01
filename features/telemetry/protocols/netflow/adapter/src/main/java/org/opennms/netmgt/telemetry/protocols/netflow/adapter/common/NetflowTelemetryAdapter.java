/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2024 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2024 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/
package org.opennms.netmgt.telemetry.protocols.netflow.adapter.common;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.script.ScriptException;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import org.opennms.core.mate.api.ContextKey;
import org.opennms.netmgt.collection.api.CollectionAgent;
import org.opennms.netmgt.collection.api.CollectionAgentFactory;
import org.opennms.netmgt.collection.api.CollectionSet;
import org.opennms.netmgt.collection.dto.CollectionSetDTO;
import org.opennms.netmgt.dao.api.InterfaceToNodeCache;
import org.opennms.netmgt.telemetry.protocols.cache.NodeInfo;
import org.opennms.netmgt.telemetry.api.adapter.TelemetryMessageLog;
import org.opennms.netmgt.telemetry.api.adapter.TelemetryMessageLogEntry;
import org.opennms.netmgt.telemetry.config.api.AdapterDefinition;
import org.opennms.netmgt.telemetry.protocols.collection.AbstractScriptedCollectionAdapter;
import org.opennms.netmgt.telemetry.protocols.collection.CollectionSetWithAgent;
import org.opennms.netmgt.telemetry.protocols.collection.ScriptedCollectionSetBuilder;
import org.opennms.netmgt.telemetry.protocols.cache.NodeInfoCache;
import org.opennms.netmgt.telemetry.protocols.netflow.transport.FlowMessage;
import org.opennms.netmgt.telemetry.protocols.netflow.transport.Value;

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Strings;
import com.google.protobuf.InvalidProtocolBufferException;

import static com.codahale.metrics.MetricRegistry.name;

public class NetflowTelemetryAdapter extends AbstractScriptedCollectionAdapter {
    private final Timer scriptEvaluationTimer;
    private final Meter metricsCollected;
    private final Meter rawValuesProcessed;
    private InterfaceToNodeCache interfaceToNodeCache;
    private CollectionAgentFactory collectionAgentFactory;

    private ContextKey contextKey;
    private String metaDataNodeLookup;
    private final NodeInfoCache nodeInfoCache;

    protected NetflowTelemetryAdapter(final AdapterDefinition adapterConfig, final MetricRegistry metricRegistry, final NodeInfoCache nodeInfoCache) {
        super(adapterConfig, metricRegistry);
        this.nodeInfoCache = nodeInfoCache;

        this.scriptEvaluationTimer = metricRegistry.timer(name("adapters", adapterConfig.getFullName(), "scriptEvaluation"));
        this.rawValuesProcessed = metricRegistry.meter(name("adapters", adapterConfig.getFullName(), "rawValuesProcessed"));
        this.metricsCollected = metricRegistry.meter(name("adapters", adapterConfig.getFullName(), "metricsCollected"));
    }

    @Override
    public Stream<CollectionSetWithAgent> handleCollectionMessage(final TelemetryMessageLogEntry message, final TelemetryMessageLog messageLog) {
        LOG.debug("Received {} telemetry messages", messageLog.getMessageList().size());

        LOG.trace("Parsing packet: {}", message);
        FlowMessage flowMessage;
        try {
            flowMessage = FlowMessage.parseFrom(message.getByteArray());
        } catch (InvalidProtocolBufferException e) {
            LOG.error("Unable to parse message from proto", e);
            return Stream.empty();
        }

        final String address = messageLog.getSourceAddress();

        final InetAddress inetAddress;
        try {
            inetAddress = InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            LOG.warn("Failed to resolve agent address: {}", address);
            return Stream.empty();
        }

        final Optional<NodeInfo> nodeInfo = nodeInfoCache.getNodeInfoFromCache(messageLog.getLocation(), messageLog.getSourceAddress(), contextKey, flowMessage.getNodeIdentifier());

        final CollectionAgent agent;
        if (nodeInfo.isPresent()) {
            agent = collectionAgentFactory.createCollectionAgent(Integer.toString(nodeInfo.get().getNodeId()), inetAddress);

        } else {
            LOG.warn("Unable to find node and interface for agent address: {}", address);
            return Stream.empty();
        }

        final ScriptedCollectionSetBuilder builder = getCollectionBuilder();
        if (builder == null) {
            LOG.error("Error compiling script '{}'. See logs for details.", this.getScript());
            return Stream.empty();
        }

        final Map<String, Object> data = flowMessage.getRawMessageList().stream().collect(HashMap::new, (m, v)->m.put(v.getName(), mapToJavaTypes(v)), HashMap::putAll);

        rawValuesProcessed.mark(flowMessage.getRawMessageList().size());
        try {
            final CollectionSet builderResults;

            try (final Timer.Context ctx = scriptEvaluationTimer.time()) {
                builderResults = builder.build(agent, data, message.getTimestamp());
            }

            metricsCollected.mark(((CollectionSetDTO) builderResults).countMetrics());
            return Stream.of(new CollectionSetWithAgent(agent, builderResults));
        } catch (final ScriptException e) {
            LOG.error("Error while running script: {}", e.getMessage());
            return Stream.empty();
        }
    }

    static Object mapToJavaTypes(Value value) {
        switch (value.getOneofValueCase()) {
            case BOOLEAN:
                return value.getBoolean().getBool().getValue();
            case FLOAT:
                return value.getFloat().getDouble().getValue();
            case DATETIME:
                return value.getDatetime().getUint64().getValue();
            case IPV4ADDRESS:
                return value.getIpv4Address().getString().getValue();
            case IPV6ADDRESS:
                return value.getIpv6Address().getString().getValue();
            case MACADDRESS:
                return value.getMacaddress().getString().getValue();
            case LIST:
                return value.getList().getListList().stream()
                        .map(org.opennms.netmgt.telemetry.protocols.netflow.transport.List::getValueList)
                        .map(list -> list.stream()
                                .map(NetflowTelemetryAdapter::mapToJavaTypes)
                                .collect(Collectors.toList()));
            case SIGNED:
                return value.getSigned().getInt64().getValue();
            case UNSIGNED:
                return value.getUnsigned().getUint64().getValue();
            case STRING:
                return value.getString().getString().getValue();
            case OCTETARRAY:
                return value.getOctetarray().getBytes().getValue().toByteArray();
            case UNDECLARED:
                return value.getOctetarray().getBytes().getValue().toByteArray();
            case NULL:
            case ONEOFVALUE_NOT_SET:
            default:
                return null;
        }

    }

    public void setCollectionAgentFactory(final CollectionAgentFactory collectionAgentFactory) {
        this.collectionAgentFactory = collectionAgentFactory;
    }

    public void setInterfaceToNodeCache(final InterfaceToNodeCache interfaceToNodeCache) {
        this.interfaceToNodeCache = interfaceToNodeCache;
    }

    public ContextKey getContextKey() {
        return contextKey;
    }

    public String getMetaDataNodeLookup() {
        return metaDataNodeLookup;
    }

    public void setMetaDataNodeLookup(String metaDataNodeLookup) {
        this.metaDataNodeLookup = metaDataNodeLookup;

        if (!Strings.isNullOrEmpty(this.metaDataNodeLookup)) {
            this.contextKey = new ContextKey(metaDataNodeLookup);
        } else {
            this.contextKey = null;
        }
    }
}
