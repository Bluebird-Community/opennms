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
package org.opennms.netmgt.collection.dto;


import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import org.opennms.netmgt.collection.api.AttributeGroupType;
import org.opennms.netmgt.collection.api.AttributeType;
import org.opennms.netmgt.collection.api.CollectionAgent;
import org.opennms.netmgt.collection.api.CollectionAttribute;
import org.opennms.netmgt.collection.api.CollectionResource;
import org.opennms.netmgt.collection.api.CollectionSet;
import org.opennms.netmgt.collection.api.CollectionSetVisitor;
import org.opennms.netmgt.collection.api.CollectionStatus;
import org.opennms.netmgt.collection.api.Persister;
import org.opennms.netmgt.collection.api.ServiceParameters;
import org.opennms.netmgt.collection.support.AbstractCollectionAttribute;
import org.opennms.netmgt.collection.support.AbstractCollectionAttributeType;
import org.opennms.netmgt.collection.support.AbstractCollectionResource;
import org.opennms.netmgt.collection.support.builder.Attribute;
import org.opennms.netmgt.collection.support.builder.CollectionSetBuilder;
import org.opennms.netmgt.collection.support.builder.Resource;

@XmlRootElement(name = "collection-set")
@XmlAccessorType(XmlAccessType.NONE)
public class CollectionSetDTO implements CollectionSet {

    @XmlElement(name="agent")
    private CollectionAgentDTO agent;

    @XmlAttribute(name="status")
    private CollectionStatus status = CollectionStatus.SUCCEEDED;

    @XmlAttribute(name="timestamp")
    private Date timestamp;

    @XmlElement(name="collection-resource")
    private List<CollectionResourceDTO> collectionResources = new ArrayList<>(0);

    @XmlAttribute(name="disable-counter-persistence")
    private Boolean disableCounterPersistence;

    @XmlAttribute(name = "sequence-number")
    private Long sequenceNumber;

    public CollectionSetDTO() { }

    public CollectionSetDTO(CollectionAgent agent, CollectionStatus status,
            Date timestamp, Map<Resource, List<Attribute<?>>> attributesByResource,
            boolean disableCounterPersistence, Long sequenceNumber) {
        this.agent = new CollectionAgentDTO(agent);
        this.status = status;
        this.timestamp = timestamp;
        this.sequenceNumber = sequenceNumber;
        collectionResources = new ArrayList<>();
        for (Entry<Resource, List<Attribute<?>>> entry : attributesByResource.entrySet()) {
            collectionResources.add(new CollectionResourceDTO(entry.getKey(), entry.getValue()));
        }
        if (disableCounterPersistence) {
            this.disableCounterPersistence = disableCounterPersistence;
        }
    }

    @Override
    public String toString() {
        return String.format("CollectionSetDTO[agent=%s, collectionResources=%s, status=%s, timestamp=%s, disableCounterPersistence=%s]",
                agent, collectionResources, status, timestamp, disableCounterPersistence);
    }

    @Override
    public int hashCode() {
        return Objects.hash(agent, collectionResources, status, timestamp, disableCounterPersistence, sequenceNumber);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        } else if (obj == null) {
            return false;
        } else if (!(obj instanceof CollectionSetDTO)) {
            return false;
        }
        CollectionSetDTO other = (CollectionSetDTO) obj;
        return Objects.equals(this.agent, other.agent)
               && Objects.equals(this.collectionResources, other.collectionResources)
               && Objects.equals(this.status, other.status)
               && Objects.equals(this.timestamp, other.timestamp)
               && Objects.equals(this.disableCounterPersistence, other.disableCounterPersistence)
               && Objects.equals(this.sequenceNumber, other.sequenceNumber);
    }

    @Override
    public CollectionStatus getStatus() {
        return status;
    }

    @Override
    public boolean ignorePersist() {
        return false;
    }

    @Override
    public Date getCollectionTimestamp() {
        return timestamp;
    }

    public void setCollectionAgent(CollectionAgentDTO agent) {
        this.agent = agent;
    }

    public long countMetrics() {
        long count = 0;
        for (CollectionResourceDTO entry : this.collectionResources) {
            count += entry.getAttributes().size();
        }
        return count;
    }

    private Set<CollectionResource> buildCollectionResources() {
        final Set<CollectionResource> collectionResources = new LinkedHashSet<>();
        for (CollectionResourceDTO entry : this.collectionResources) {
            final Resource resource = entry.getResource();
            final AbstractCollectionResource collectionResource = CollectionSetBuilder.toCollectionResource(resource, agent);
            if (resource != null) {
                resource.getTags().forEach(collectionResource::addTag);
                resource.getServiceParams().forEach(collectionResource::addServiceParam);
            }
            for (Attribute<?> attribute : entry.getAttributes()) {
                final AttributeGroupType groupType = new AttributeGroupType(attribute.getGroup(), AttributeGroupType.IF_TYPE_ALL);
                final AbstractCollectionAttributeType attributeType = new AbstractCollectionAttributeType(groupType) {
                    @Override
                    public AttributeType getType() {
                        return attribute.getType();
                    }

                    @Override
                    public String getName() {
                        return attribute.getName();
                    }

                    @Override
                    public void storeAttribute(CollectionAttribute collectionAttribute, Persister persister) {
                        if (AttributeType.STRING.equals(attribute.getType())) {
                            persister.persistStringAttribute(collectionAttribute);
                        } else {
                            persister.persistNumericAttribute(collectionAttribute);
                        }
                    }

                    @Override
                    public String toString() {
                        return attribute.toString();
                    }
                };

                collectionResource.addAttribute(new AbstractCollectionAttribute(attributeType, collectionResource) {
                    @Override
                    public String getMetricIdentifier() {
                        return attribute.getIdentifier() != null ? attribute.getIdentifier() : attribute.getName();
                    }

                    @Override
                    public Number getNumericValue() {
                        return attribute.getNumericValue();
                    }

                    @Override
                    public String getStringValue() {
                        return attribute.getStringValue();
                    }

                    @Override
                    public boolean shouldPersist(ServiceParameters params) {
                        return !(Boolean.FALSE.equals(disableCounterPersistence) && AttributeType.COUNTER.equals(attribute.getType()));
                    }

                    @Override
                    public String toString() {
                        return String.format("Attribute[%s:%s]", getMetricIdentifier(), attribute.getValue());
                    }
                });
            }
            collectionResources.add(collectionResource);
        }
        return collectionResources;
    }

    @Override
    public void visit(CollectionSetVisitor visitor) {
        visitor.visitCollectionSet(this);

        for(CollectionResource resource : buildCollectionResources()) {
            resource.visit(visitor);
        }

        visitor.completeCollectionSet(this);
    }

    @Override
    public OptionalLong getSequenceNumber() {
        return sequenceNumber == null ? OptionalLong.empty() : OptionalLong.of(sequenceNumber);
    }
}
