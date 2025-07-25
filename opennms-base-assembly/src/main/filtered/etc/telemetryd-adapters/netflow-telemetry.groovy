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

import org.opennms.netmgt.collection.support.builder.NodeLevelResource

NodeLevelResource nodeLevelResource = new NodeLevelResource(agent.getNodeId())

builder.withSequenceNumber((Long)msg.get("@sequenceNumber"))

// just an example of getting data and persisting it, it does not make any sense at all
builder.withGauge(nodeLevelResource, "num_bytes", "num_bytes", (Long) msg.get("octetDeltaCount"))
builder.withGauge(nodeLevelResource, "num_packets", "num_packets", (Long) msg.get("packetDeltaCount"))
builder.withGauge(nodeLevelResource, "engine-id", "engine-id", (Long) msg.get("@observationDomainId"))
