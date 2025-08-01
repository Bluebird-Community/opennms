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
package org.opennms.netmgt.dao.mock;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.opennms.core.criteria.CriteriaBuilder;
import org.opennms.netmgt.dao.api.NodeDao;
import org.opennms.netmgt.model.OnmsCategory;
import org.opennms.netmgt.model.OnmsIpInterface;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.model.OnmsSnmpInterface;
import org.opennms.netmgt.model.SurveillanceStatus;
import org.opennms.netmgt.model.monitoringLocations.OnmsMonitoringLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockNodeDao extends AbstractMockDao<OnmsNode, Integer> implements NodeDao {
    private static final Logger LOG = LoggerFactory.getLogger(MockNodeDao.class);
    private AtomicInteger m_id = new AtomicInteger(0);

    @Override
    protected void generateId(final OnmsNode node) {
        node.setId(m_id.incrementAndGet());
    }

    @Override
    protected Integer getId(final OnmsNode node) {
        return node.getId();
    }

    @Override
    public void delete(final OnmsNode node) {
        for (final OnmsIpInterface iface : node.getIpInterfaces()) {
            getIpInterfaceDao().delete(iface);
        }
        for (final OnmsSnmpInterface iface : node.getSnmpInterfaces()) {
            getSnmpInterfaceDao().delete(iface);
        }
        super.delete(node);
    }

    @Override
    public void update(final OnmsNode node) {
        if (node == null) return;
        super.update(node);
        updateSubObjects(node);
    }

    @Override
    public Integer save(final OnmsNode node) {
        if (node == null) return null;
        Integer retval = super.save(node);
        updateSubObjects(node);
        return retval;
    }

    @Override
    public void flush() {
        super.flush();
        for (final OnmsNode node : findAll()) {
            updateSubObjects(node);
        }
    }

    private void updateSubObjects(final OnmsNode node) {
        node.getAssetRecord().setNode(node);
        getAssetRecordDao().saveOrUpdate(node.getAssetRecord());

        for (final OnmsCategory cat : node.getCategories()) {
            getCategoryDao().saveOrUpdate(cat);
        }

        /* not sure if this is necessary */
        /*
        getMonitoringLocationDao().saveOrUpdate(node.getLocation());
        */

        /** delete any interfaces that were removed compared to the database **/
        final OnmsNode dbNode = node.getId() == null ? null : get(node.getId());
        if (dbNode != null) {
            for (final OnmsSnmpInterface iface : dbNode.getSnmpInterfaces()) {
                if (!node.getSnmpInterfaces().contains(iface)) {
                    getSnmpInterfaceDao().delete(iface);
                }
            }
            for (final OnmsIpInterface iface : dbNode.getIpInterfaces()) {
                if (!node.getIpInterfaces().contains(iface)) {
                    getIpInterfaceDao().delete(iface);
                }
            }
        }
        /* not sure if this is necessary */
        /*
        for (final OnmsIpInterface iface : getIpInterfaceDao().findAll()) {
            final OnmsSnmpInterface snmpInterface = iface.getSnmpInterface();
            if (snmpInterface != null && snmpInterface.getId() != null) {
                if (snmpInterfaceDao.get(snmpInterface.getId()) == null) {
                    getIpInterfaceDao().delete(iface);
                }
            }
        }
         */

        for (final OnmsSnmpInterface iface : node.getSnmpInterfaces()) {
            iface.setNode(node);
            getSnmpInterfaceDao().saveOrUpdate(iface);
        }

        for (final OnmsIpInterface iface : node.getIpInterfaces()) {
            iface.setNode(node);
            getIpInterfaceDao().saveOrUpdate(iface);
        }
    }

	@Override
	public OnmsNode get(final String lookupCriteria) {
		if (lookupCriteria.contains(":")) {
			throw new UnsupportedOperationException("Not yet implemented!");
		} else {
			Integer nodeId = Integer.parseInt(lookupCriteria);
			return get(nodeId);
		}
	}

    @Override
    public String getLabelForId(final Integer id) {
        final OnmsNode node = get(id);
        return node == null ? null : node.getLabel();
    }

    @Override
    public String getLocationForId(final Integer id) {
        final OnmsNode node = get(id);
        if (node == null) {
            return null;
        } else {
            OnmsMonitoringLocation onmsMonitoringLocation = node.getLocation();
            return onmsMonitoringLocation == null ? null : onmsMonitoringLocation.getLocationName();
        }
    }

    @Override
    public List<OnmsNode> findByLabel(final String label) {
        final List<OnmsNode> nodes = new ArrayList<OnmsNode>();
        for (final OnmsNode node : findAll()) {
            if (label.equals(node.getLabel())) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    @Override
    public List<OnmsNode> findBySysNameOfLldpLinksOfNode(int nodeId) {
        return Collections.emptyList();
    }

    @Override
    public OnmsNode getHierarchy(final Integer id) {
        return get(id);
    }

    @Override
    public Map<String, Integer> getForeignIdToNodeIdMap(final String foreignSource) {
        final Map<String, Integer> nodes = new HashMap<String, Integer>();
        for (final OnmsNode node : findAll()) {
            if (foreignSource.equals(node.getForeignSource())) {
                nodes.put(node.getForeignId(), node.getId());
            }
        }
        return nodes;
    }

    @Override
    public List<OnmsNode> findAllByVarCharAssetColumn(final String columnName, final String columnValue) {
        final CriteriaBuilder builder = new CriteriaBuilder(OnmsNode.class);
        builder.alias("assetRecord", "assets");
        builder.eq("assets." + columnName, columnValue);
        return findMatching(builder.toCriteria());
    }

    @Override
    public List<OnmsNode> findAllByVarCharAssetColumnCategoryList(final String columnName, final String columnValue, final Collection<OnmsCategory> categories) {
        final List<OnmsNode> nodes = new ArrayList<OnmsNode>();
        for (final OnmsNode node : findAllByVarCharAssetColumn(columnName, columnValue)) {
            for (final OnmsCategory cat : categories) {
                if (node.hasCategory(cat.getName())) {
                    nodes.add(node);
                    break;
                }
            }
        }
        return nodes;
    }

    @Override
    public List<OnmsNode> findByCategory(final OnmsCategory category) {
        final List<OnmsNode> nodes = new ArrayList<OnmsNode>();
        for (final OnmsNode node : findAll()) {
            if (node.getCategories().contains(category)) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    @Override
    public List<OnmsNode> findAllByCategoryList(final Collection<OnmsCategory> categories) {
        final List<OnmsNode> nodes = new ArrayList<OnmsNode>();
        for (final OnmsNode node : findAll()) {
            for (final OnmsCategory category : categories) {
                if (node.getCategories().contains(category)) {
                    nodes.add(node);
                }
            }
        }
        return nodes;
    }

    @Override
    public List<OnmsNode> findAllByCategoryLists(final Collection<OnmsCategory> rowCatNames, final Collection<OnmsCategory> colCatNames) {
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public List<OnmsNode> findByForeignSource(final String foreignSource) {
        final List<OnmsNode> nodes = new ArrayList<OnmsNode>();
        for (final OnmsNode node : findAll()) {
            if (Objects.equals(foreignSource, node.getForeignSource())) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    @Override
    public List<OnmsNode> findByIpAddressAndService(InetAddress ipAddress, String serviceName) {
        return null;
    }

    @Override
    public OnmsNode findByForeignId(final String foreignSource, final String foreignId) {
        for (final OnmsNode node : findByForeignSource(foreignSource)) {
            if (foreignId.equals(node.getForeignId())) {
                return node;
            }
        }
        return null;
    }

    @Override
    public int getNodeCountForForeignSource(final String foreignSource) {
        return findByForeignSource(foreignSource).size();
    }

    @Override
    public Map<String, Set<String>> getForeignIdsPerForeignSourceMap() {
        Map<String, Set<String>> map = new TreeMap<String,Set<String>>();
        for (final OnmsNode node : findAll()) {
            if (node.getForeignSource() != null) {
                final String foreignSource = node.getForeignSource();
                final String foreignId = node.getForeignId();
                if (!map.containsKey(foreignSource)) {
                    map.put(foreignSource, new TreeSet<String>());
                }
                map.get(foreignSource).add(foreignId);
            }
        }
        return map;
    }
    @Override
    public Set<String> getForeignIdsPerForeignSource(String foreignSource) {
        Set<String> set = new TreeSet<String>();
        for (final OnmsNode node : findAll()) {
            if (node.getForeignId() != null) {
                set.add(node.getForeignId());
            }
        }
        return set;
    }

    @Override
    public List<OnmsNode> findAllProvisionedNodes() {
        final List<OnmsNode> nodes = new ArrayList<OnmsNode>();
        for (final OnmsNode node : findAll()) {
            if (node.getForeignSource() != null) nodes.add(node);
        }
        return nodes;
    }

    @Override
    public List<OnmsIpInterface> findObsoleteIpInterfaces(final Integer nodeId, final Date scanStamp) {
        final List<OnmsIpInterface> ifaces = new ArrayList<>();
        final OnmsNode node = get(nodeId);
        if (node == null) return ifaces;

        for (final OnmsIpInterface iface : node.getIpInterfaces()) {
            if (iface.isPrimary()) continue;
            if (truncateMillis(iface.getIpLastCapsdPoll()) < truncateMillis(scanStamp)) {
                LOG.debug("findObsoleteIpInterfaces: {} < {}", truncateMillis(iface.getIpLastCapsdPoll()), truncateMillis(scanStamp));
                ifaces.add(iface);
            }
        }

        return ifaces;
    }

    public List<OnmsSnmpInterface> findObsoleteSnmpInterfaces(final Integer nodeId, final Date scanStamp) {
        final List<OnmsSnmpInterface> ifaces = new ArrayList<>();
        final OnmsNode node = get(nodeId);
        if (node == null) return ifaces;

        for (final OnmsSnmpInterface iface : node.getSnmpInterfaces()) {
            if (truncateMillis(iface.getLastCapsdPoll()) < truncateMillis(scanStamp)) {
                LOG.debug("findObsoleteSnmpInterfaces: {} < {}", truncateMillis(iface.getLastCapsdPoll()), truncateMillis(scanStamp));
                ifaces.add(iface);
            }
        }

        return ifaces;
    }

    private static long truncateMillis(final Date date) {
        return date == null ? 0 : (1000 * (date.getTime() / 1000));
    }

    @Override
    public void deleteObsoleteInterfaces(final Integer nodeId, final Date scanStamp) {
        final OnmsNode node = get(nodeId);
        if (node == null) return;

        for (final OnmsIpInterface iface : findObsoleteIpInterfaces(nodeId, scanStamp)) {
            LOG.debug("Deleting obsolete IP interface: {}", iface);
            node.getIpInterfaces().remove(iface);
            getIpInterfaceDao().delete(iface.getId());
        }

        for (final OnmsSnmpInterface iface : findObsoleteSnmpInterfaces(nodeId, scanStamp)) {
            LOG.debug("Deleting obsolete SNMP interface: {}", iface);
            node.getSnmpInterfaces().remove(iface);
            getSnmpInterfaceDao().delete(iface.getId());
        }
    }

    @Override
    public void updateNodeScanStamp(final Integer nodeId, final Date scanStamp) {
        get(nodeId).setLastCapsdPoll(scanStamp);
    }

    @Override
    public Collection<Integer> getNodeIds() {
        final List<Integer> ids = new ArrayList<Integer>();
        for (final OnmsNode node : findAll()) {
            ids.add(node.getId());
        }
        return ids;
    }

    @Override
    public List<OnmsNode> findByForeignSourceAndIpAddress(final String foreignSource, final String ipAddress) {
        final List<OnmsNode> nodes = new ArrayList<OnmsNode>();
        for (final OnmsNode node : findAll()) {
            if (Objects.equals(foreignSource, node.getForeignSource())) {
                final OnmsIpInterface iface = node.getIpInterfaceByIpAddress(ipAddress);
                if (iface != null) nodes.add(node);
                continue;
            }
        }
        return nodes;
    }

    @Override
    public SurveillanceStatus findSurveillanceStatusByCategoryLists(final Collection<OnmsCategory> rowCategories, final Collection<OnmsCategory> columnCategories) {
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public Integer getNextNodeId(final Integer nodeId) {
        Integer next = null;
        for (final OnmsNode node : findAll()) {
            if (node.getId() > nodeId) {
                if (next == null || (node.getId() < next)) {
                    next = node.getId();
                }
            }
        }
        return next;
    }

    @Override
    public Integer getPreviousNodeId(final Integer nodeId) {
        Integer previous = null;
        for (final OnmsNode node : findAll()) {
            if (node.getId() < nodeId) {
                if (previous == null || (previous < node.getId())) {
                    previous = node.getId();
                }
            }
        }
        return previous;
    }

    @Override
    public Map<Integer, String> getAllLabelsById() {
        Map<Integer, String> allLabelsById = new HashMap<Integer, String>();
        for (final OnmsNode node : findAll()) {
            allLabelsById.put(node.getId(), node.getLabel());
        }
        return allLabelsById;
    }

    @Override
    public Map<String, Long> getNumberOfNodesBySysOid() {
        return new HashMap<>();
    }

    public int getNextNodeId() {
        return m_id.get() + 1;
    }

    @Override
    public List<OnmsNode> findByLabelForLocation(String label, String location) {
        List<OnmsNode> nodes = new ArrayList<OnmsNode>();
        for (final OnmsNode node : findAll()) {
            if (node.getLabel().equals(label) && (node.getLocation().getLocationName().equals(location))) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    @Override
    public List<OnmsNode> findByForeignId(String foreignId) {
        List<OnmsNode> nodes = new ArrayList<OnmsNode>();
        for (final OnmsNode node : findAll()) {
            if (node.getLabel().equals(foreignId)) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    @Override
    public List<OnmsNode> findByForeignIdForLocation(String foreignId, String location) {
        List<OnmsNode> nodes = new ArrayList<OnmsNode>();
        for (final OnmsNode node : findAll()) {
            if (node.getLabel().equals(foreignId) && (node.getLocation().getLocationName().equals(location))) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    @Override
    public void markHavingFlows(Collection<Integer> ingressIds, Collection<Integer> egressIds) {
    }

    @Override
    public List<OnmsNode> findAllHavingFlows() {
        return Collections.emptyList();
    }

    @Override
    public List<OnmsNode> findAllHavingIngressFlows() {
        return Collections.emptyList();
    }

    @Override
    public List<OnmsNode> findAllHavingEgressFlows() {
        return Collections.emptyList();
    }

    @Override
    public OnmsNode getDefaultFocusPoint() {
        return null;
    }

    @Override
    public List<OnmsNode> findNodeWithMetaData(String context, String key, String value, boolean matchEnumeration) {
        return Collections.emptyList();
    }
}
