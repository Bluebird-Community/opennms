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
package org.opennms.netmgt.bsm.persistence.api;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.MapKeyColumn;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.Transient;

import org.opennms.netmgt.bsm.persistence.api.functions.map.AbstractMapFunctionEntity;
import org.opennms.netmgt.bsm.persistence.api.functions.reduce.AbstractReductionFunctionEntity;
import org.opennms.netmgt.model.OnmsApplication;
import org.opennms.netmgt.model.OnmsMonitoredService;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

@Entity
@Table(name = "bsm_service")
public class BusinessServiceEntity {

    private Long m_id;

    private String m_name;

    private Map<String, String> m_attributes = Maps.newLinkedHashMap();

    private Set<BusinessServiceEdgeEntity> m_edges = Sets.newLinkedHashSet();

    private AbstractReductionFunctionEntity m_reductionFunction;

    /** The level in the hierarchy.
     * If 0 the business service should not have any parents.
     * If -1 the business service level has not been initialized*/
    private int level = -1;

    public void setLevel(int level) {
        this.level = level;
    }

    @Transient
    public int getLevel() {
        return level;
    }

    @Id
    @SequenceGenerator(name = "opennmsSequence", sequenceName = "opennmsNxtId")
    @GeneratedValue(generator = "opennmsSequence")
    @Column(name = "id", nullable = false)
    public Long getId() {
        return m_id;
    }

    public void setId(Long id) {
        m_id = id;
    }

    @Column(name = "name", nullable = false, unique = true)
    public String getName() {
        return m_name;
    }

    public void setName(String name) {
        m_name = name;
    }

    @ElementCollection(fetch = FetchType.EAGER)
    @JoinTable(name = "bsm_service_attributes", joinColumns = @JoinColumn(name = "bsm_service_id", referencedColumnName = "id"))
    @MapKeyColumn(name = "key")
    @Column(name = "value", nullable = false)
    public Map<String, String> getAttributes() {
        return m_attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        m_attributes = attributes;
    }

    public void setAttribute(String key, String value) {
        m_attributes.put(key, value);
    }

    @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL, mappedBy="businessService", orphanRemoval = true)
    public Set<BusinessServiceEdgeEntity> getEdges() {
        return m_edges;
    }

    public void setEdges(Set<BusinessServiceEdgeEntity> edges) {
        m_edges = edges;
    }

    public void addEdge(BusinessServiceEdgeEntity edge) {
        m_edges.add(edge);
    }

    public boolean removeEdge(BusinessServiceEdgeEntity edge) {
        return m_edges.remove(edge);
    }

    @Transient
    public Set<IPServiceEdgeEntity> getIpServiceEdges() {
        return getEdges(IPServiceEdgeEntity.class);
    }

    @Transient
    public Set<BusinessServiceChildEdgeEntity> getChildEdges() {
        return getEdges(BusinessServiceChildEdgeEntity.class);
    }

    @Transient
    public Set<SingleReductionKeyEdgeEntity> getReductionKeyEdges() {
        return getEdges(SingleReductionKeyEdgeEntity.class);
    }

    @Transient
    public Set<ApplicationEdgeEntity> getApplicationEdges() {
        return getEdges(ApplicationEdgeEntity.class);
    }

    @Transient
    @SuppressWarnings("unchecked")
    private <T extends BusinessServiceEdgeEntity> Set<T> getEdges(Class<T> type) {
        return getEdges().stream()
                .filter(type::isInstance)
                .map(e -> (T)e)
                .collect(Collectors.toSet());
    }

    @OneToOne(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "bsm_reduce_id")
    public AbstractReductionFunctionEntity getReductionFunction() {
        return m_reductionFunction;
    }

    public void setReductionFunction(AbstractReductionFunctionEntity reductionFunction) {
        m_reductionFunction = Objects.requireNonNull(reductionFunction);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (obj == this) return true;
        if (!(obj instanceof BusinessServiceEntity)) return false;
        final BusinessServiceEntity other = (BusinessServiceEntity) obj;
        if (getId() != null) {
            return getId().equals(other.getId());
        }
        return super.equals(obj);
    }

    @Override
    public int hashCode() {
        return 0; // HACK: always return 0, as otherwise Sets etc do not work.
    }

    @Override
    public String toString() {
        // we do not include ip services here, otherwise we cannot use this object properly
        return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("id", m_id)
                .add("name", m_name)
                .add("attributes", m_attributes)
                .add("edges", m_edges)
                .toString();
    }

    // Convenient method to add a child edge
    public BusinessServiceEntity addChildServiceEdge(BusinessServiceEntity child, AbstractMapFunctionEntity mapFunction) {
        return addChildServiceEdge(child, mapFunction, 1);
    }

    // Convenient method to add a child edge
    public BusinessServiceEntity addChildServiceEdge(BusinessServiceEntity child, AbstractMapFunctionEntity mapFunction, int weight) {
        BusinessServiceChildEdgeEntity edge = new BusinessServiceChildEdgeEntity();
        edge.setBusinessService(this);
        edge.setChild(Objects.requireNonNull(child));
        edge.setWeight(weight);
        edge.setMapFunction(Objects.requireNonNull(mapFunction));
        addEdge(edge);
        return this;
    }

    public BusinessServiceEntity addApplicationEdge(OnmsApplication onmsApplication, AbstractMapFunctionEntity mapFunction) {
        return addApplicationEdge(onmsApplication, mapFunction, 1);
    }

    public BusinessServiceEntity addApplicationEdge(OnmsApplication onmsApplication, AbstractMapFunctionEntity mapFunction, int weight) {
        ApplicationEdgeEntity edge = new ApplicationEdgeEntity();
        edge.setBusinessService(this);
        edge.setApplication(Objects.requireNonNull(onmsApplication));
        edge.setWeight(weight);
        edge.setWeight(weight);
        edge.setMapFunction(Objects.requireNonNull(mapFunction));
        addEdge(edge);
        return this;
    }

    // Convenient method to add an ipservice edge
    public BusinessServiceEntity addIpServiceEdge(OnmsMonitoredService ipService, AbstractMapFunctionEntity mapFunction) {
        return addIpServiceEdge(ipService, mapFunction, 1, null);
    }

    // Convenient method to add an ipservice edge
    public BusinessServiceEntity addIpServiceEdge(OnmsMonitoredService ipService, AbstractMapFunctionEntity mapFunction, int weight, String friendlyName) {
        IPServiceEdgeEntity edge = new IPServiceEdgeEntity();
        edge.setBusinessService(this);
        edge.setIpService(Objects.requireNonNull(ipService));
        edge.setWeight(weight);
        edge.setMapFunction(Objects.requireNonNull(mapFunction));
        edge.setFriendlyName(friendlyName);
        addEdge(edge);
        return this;
    }

    // Convenient method to add a reduction key edge
    public BusinessServiceEntity addReductionKeyEdge(String reductionKey, AbstractMapFunctionEntity mapFunction) {
        return addReductionKeyEdge(reductionKey, mapFunction, 1, null);
    }

    // Convenient method to add a reduction key edge
    public BusinessServiceEntity addReductionKeyEdge(String reductionKey, AbstractMapFunctionEntity mapFunction, int weight, String friendlyName) {
        SingleReductionKeyEdgeEntity edge = new SingleReductionKeyEdgeEntity();
        edge.setBusinessService(this);
        edge.setReductionKey(Objects.requireNonNull(reductionKey));
        edge.setWeight(weight);
        edge.setMapFunction(Objects.requireNonNull(mapFunction));
        edge.setFriendlyName(friendlyName);
        addEdge(edge);
        return this;
    }
}
