/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2025 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2025 The OpenNMS Group, Inc.
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


import groovy.util.logging.Slf4j
import org.opennms.features.openconfig.proto.gnmi.Gnmi
import org.opennms.netmgt.collection.api.AttributeType
import org.opennms.netmgt.collection.api.CollectionResource
import org.opennms.netmgt.collection.support.builder.InterfaceLevelResource
import org.opennms.netmgt.collection.support.builder.NodeLevelResource

@Slf4j
class CollectionSetGenerator {
    static generate(agent, builder, response) {
        log.debug("Generating collection set for message: {}", response)
        NodeLevelResource nodeLevelResource = new NodeLevelResource(agent.getNodeId())
        // Sample code for parsing and building resources, real data may vary
        Gnmi.Notification notification = response.getUpdate()
        List<Gnmi.Update> updateList = notification.getUpdateList()

        // Check if this is a component (CPU) or interface metric
        String resourceType = null;
        String resourceName = null;

        for (Gnmi.PathElem pathElem : notification.getPrefix().getElemList()) {
            if (pathElem.getName().equals("interface")) {
                resourceType = CollectionResource.RESOURCE_TYPE_IF;
                resourceName = pathElem.getKeyMap().get("name");
                break;
            } else if (pathElem.getName().equals("component")) {
                resourceType = CollectionResource.RESOURCE_TYPE_NODE;
                resourceName = pathElem.getKeyMap().get("name");
                break;
            }
        }

        if (resourceName == null) {
            resourceName = agent.getHostAddress();
        }
        for (Gnmi.Update update : updateList) {

            StringBuilder pathName = new StringBuilder();
            for (Gnmi.PathElem pathElem : update.getPath().getElemList()) {
                if (pathName.length() > 0) {
                    pathName.append("-");
                }
                pathName.append(pathElem.getName());
            }

            // Handle different value types
            if (update.getVal().getValueCase().equals(Gnmi.TypedValue.ValueCase.UINT_VAL)) {
                long longValue = update.getVal().getUintVal();
                if (resourceType == CollectionResource.RESOURCE_TYPE_IF) {
                    InterfaceLevelResource interfaceResource = new InterfaceLevelResource(nodeLevelResource, resourceName);
                    builder.withNumericAttribute(interfaceResource, "gnmi-interfaces", pathName.toString(),
                            longValue, AttributeType.COUNTER);
                } else if (resourceType == CollectionResource.RESOURCE_TYPE_NODE) {
                    builder.withNumericAttribute(nodeLevelResource, "component-stats", pathName.toString(),
                            longValue, AttributeType.GAUGE);
                } else {
                    // Default to interface-level resource
                    InterfaceLevelResource interfaceResource = new InterfaceLevelResource(nodeLevelResource, resourceName);
                    builder.withNumericAttribute(interfaceResource, "gnmi-interfaces", pathName.toString(),
                            longValue, AttributeType.COUNTER);
                }
            } else if (update.getVal().getValueCase().equals(Gnmi.TypedValue.ValueCase.JSON_VAL)) {
                String jsonValue = new String(update.getVal().getJsonVal().toByteArray());
                jsonValue = jsonValue.replaceAll("\"", "");
                try {
                    double doubleValue = Double.parseDouble(jsonValue);
                    if (resourceType == CollectionResource.RESOURCE_TYPE_IF) {
                        InterfaceLevelResource interfaceResource = new InterfaceLevelResource(nodeLevelResource, resourceName);
                        builder.withNumericAttribute(interfaceResource, "gnmi-interfaces", pathName.toString(),
                                doubleValue, AttributeType.COUNTER);
                    } else if (resourceType == CollectionResource.RESOURCE_TYPE_NODE) {
                        builder.withNumericAttribute(nodeLevelResource, "component-stats", pathName.toString(),
                                doubleValue, AttributeType.GAUGE);
                    } else {
                        // Default to interface-level resource
                        InterfaceLevelResource interfaceResource = new InterfaceLevelResource(nodeLevelResource, resourceName);
                        builder.withNumericAttribute(interfaceResource, "gnmi-interfaces", pathName.toString(),
                                doubleValue, AttributeType.COUNTER);
                    }
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse JSON value: {}", jsonValue, e);
                }
            }
        }
    }
}

// The following variables are passed in as globals from the adapter:
// agent: the agent (or node) against which the metrics will be associated
// builder: a reference to a CollectionSetBuilder to which the resources/metrics should be added
// msg: the message from which to extract the metrics

Gnmi.SubscribeResponse response = msg

// Generate the CollectionSet
CollectionSetGenerator.generate(agent, builder, response)
