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
package org.opennms.netmgt.dao.hibernate;

import java.net.InetAddress;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.hibernate.HibernateException;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.transform.ResultTransformer;
import org.opennms.core.criteria.Alias;
import org.opennms.core.criteria.Alias.JoinType;
import org.opennms.core.criteria.Order;
import org.opennms.core.criteria.restrictions.EqRestriction;
import org.opennms.core.criteria.restrictions.NeRestriction;
import org.opennms.netmgt.dao.api.NodeDao;
import org.opennms.netmgt.model.OnmsCategory;
import org.opennms.netmgt.model.OnmsIpInterface;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.model.OnmsNode.NodeType;
import org.opennms.netmgt.model.OnmsSnmpInterface;
import org.opennms.netmgt.model.SurveillanceStatus;
import org.opennms.netmgt.model.monitoringLocations.OnmsMonitoringLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.hibernate3.HibernateCallback;
import org.springframework.util.StringUtils;

/**
 * <p>NodeDaoHibernate class.</p>
 *
 * @author Ted Kazmark
 * @author David Hustace
 */
public class NodeDaoHibernate extends AbstractDaoHibernate<OnmsNode, Integer> implements NodeDao {
    private static final Logger LOG = LoggerFactory.getLogger(NodeDaoHibernate.class);

    /**
     * <p>Constructor for NodeDaoHibernate.</p>
     */
    public NodeDaoHibernate() {
        super(OnmsNode.class);
    }

    /** {@inheritDoc} */
    @Override
    public OnmsNode get(String lookupCriteria) {
        if (lookupCriteria.contains(":")) {
            String[] criteria = lookupCriteria.split(":");
            return findByForeignId(criteria[0], criteria[1]);
        }
        return get(Integer.parseInt(lookupCriteria));
    }

    /**
     * Test the ability to simply retrieve a String object (node label) without
     * having to return a bulky Node object.
     */
    @Override
    public String getLabelForId(Integer id) {
        List<String> list = findObjects(String.class, "select n.label from OnmsNode as n where n.id = ?", id);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    @Override
    public String getLocationForId(Integer id) {
        List<OnmsMonitoringLocation> list = findObjects(OnmsMonitoringLocation.class, "select n.location from OnmsNode as n where n.id = ?", id);
        return list == null || list.isEmpty() ? null : list.get(0).getLocationName();
    }

    /** {@inheritDoc} */
    @Override
    public Map<Integer, String> getAllLabelsById() {
        Map<Integer, String> labelsByNodeId = new HashMap<Integer, String>();
        List<? extends Object[]> rows = findObjects(new Object[0].getClass(), "select n.id, n.label from OnmsNode as n");
        for (Object row[] : rows) {
            labelsByNodeId.put((Integer)row[0], (String)row[1]);
        }
        return labelsByNodeId;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Set<String>> getForeignIdsPerForeignSourceMap() {
        Map<String, Set<String>> map = new TreeMap<String,Set<String>>();
        List<? extends Object[]> rows = findObjects(new Object[0].getClass(), "select n.foreignSource, n.foreignId from OnmsNode as n");
        for (Object row[] : rows) {
            final String foreignSource = (String) row[0];
            final String foreignId = (String) row[1];
            if (foreignSource != null && foreignId != null) {
                if (!map.containsKey(foreignSource)) {
                    map.put(foreignSource, new TreeSet<String>());
                }
                map.get(foreignSource).add(foreignId);
            }
        }
        return map;
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getForeignIdsPerForeignSource(String foreignSource) {
        Set<String> set = new TreeSet<String>();
        List<String> rows = findObjects(String.class, "select n.foreignId from OnmsNode as n where n.foreignSource = ?", foreignSource);
        for (String foreignId : rows) {
            if (foreignId != null) {
                set.add(foreignId);
            }
        }
        return set;
    }

    /** {@inheritDoc} */
    @Override
    public List<OnmsNode> findByForeignId(String foreignId) {
        return find("from OnmsNode as n where n.foreignId = ?", foreignId);
    }
    
    /** {@inheritDoc} */
    @Override
    public List<OnmsNode> findByForeignIdForLocation(String foreignId, String location) {
        return find("from OnmsNode as n where n.foreignId = ? and n.location.locationName = ?", foreignId, location);
    }
    
    /** {@inheritDoc} */
    @Override
    public OnmsNode getHierarchy(Integer id) {
        OnmsNode node = findUnique(
                                   "select distinct n from OnmsNode as n "
                                           + "left join fetch n.assetRecord "
                                           + "where n.id = ?", id);

        initialize(node.getIpInterfaces());
        for (OnmsIpInterface i : node.getIpInterfaces()) {
            initialize(i.getMonitoredServices());
        }

        initialize(node.getSnmpInterfaces());
        for (OnmsSnmpInterface i : node.getSnmpInterfaces()) {
            initialize(i.getIpInterfaces());
        }

        return node;

    }

    /** {@inheritDoc} */
    @Override
    public List<OnmsNode> findByLabel(String label) {
        return find("from OnmsNode as n where n.label = ?", label);
    }

    /** {@inheritDoc} */
    @Override
    public List<OnmsNode> findByLabelForLocation(String label, String location) {
        return find("from OnmsNode as n where n.label = ? and n.location.locationName = ?", label, location);
    }
    
    /** {@inheritDoc} */
    @Override
    public List<OnmsNode> findAllByVarCharAssetColumn(
                                                      String columnName, String columnValue) {
        return find("from OnmsNode as n where n.assetRecord." + columnName
                    + " = ?", columnValue);
    }

    /** {@inheritDoc} */
    @Override
    public List<OnmsNode> findAllByVarCharAssetColumnCategoryList(
                                                                  String columnName, String columnValue,
                                                                  Collection<OnmsCategory> categories) {

        return find("select distinct n from OnmsNode as n "
                + "join n.categories as c "
                + "left join fetch n.assetRecord "
                + "left join fetch n.ipInterfaces as ipInterface "
                + "left join fetch ipInterface.monitoredServices as monSvc "
                + "left join fetch monSvc.serviceType "
                + "left join fetch monSvc.currentOutages "
                + "where n.assetRecord." + columnName + " = ? "
                + "and c.name in ("+categoryListToNameList(categories)+")", columnValue);
    }

    /** {@inheritDoc} */
    @Override
    public List<OnmsNode> findByCategory(OnmsCategory category) {
        return find("select distinct n from OnmsNode as n "
                + "join n.categories c "
                + "left join fetch n.assetRecord "
                + "left join fetch n.ipInterfaces as ipInterface "
                + "left join fetch ipInterface.monitoredServices as monSvc "
                + "left join fetch monSvc.serviceType "
                + "left join fetch monSvc.currentOutages "
                + "where c.name = ?",
                category.getName());
    }

    private String categoryListToNameList(Collection<OnmsCategory> categories) {
        List<String> categoryNames = new ArrayList<String>();
        for (OnmsCategory category : categories) {
            categoryNames.add(category.getName());
        }
        return StringUtils.collectionToDelimitedString(categoryNames, ", ", "'", "'");
    }



    /** {@inheritDoc} */
    @Override
    public List<OnmsNode> findAllByCategoryList(
                                                Collection<OnmsCategory> categories) {
        return find("select distinct n from OnmsNode as n "
                + "join n.categories c " 
                + "left join fetch n.assetRecord "
                + "left join fetch n.ipInterfaces as ipInterface "
                + "left join fetch n.snmpInterfaces as snmpIface"
                + "left join fetch ipInterface.monitoredServices as monSvc "
                + "left join fetch monSvc.serviceType "
                + "left join fetch monSvc.currentOutages "
                + "where c.name in ("+categoryListToNameList(categories)+")"
                + "and n.type != '" + NodeType.DELETED.value()+ "'");
    }

    /** {@inheritDoc} */
    @Override
    public List<OnmsNode> findAllByCategoryLists( final Collection<OnmsCategory> rowCategories, final Collection<OnmsCategory> columnCategories) {

        return getHibernateTemplate().execute(new HibernateCallback<List<OnmsNode>>() {

            @SuppressWarnings("unchecked")
            @Override
            public List<OnmsNode> doInHibernate(Session session) throws HibernateException, SQLException {

                return (List<OnmsNode>)session.createQuery("select distinct n from OnmsNode as n "
                        + "join n.categories c1 "
                        + "join n.categories c2 "
                        + "left join fetch n.assetRecord "
                        + "left join fetch n.ipInterfaces as iface "
                        + "left join fetch n.snmpInterfaces as snmpIface"
                        + "left join fetch iface.monitoredServices as monSvc "
                        + "left join fetch monSvc.serviceType "
                        + "left join fetch monSvc.currentOutages "
                        + "where c1 in (:rowCategories) "
                        + "and c2 in (:colCategories) "
                        + "and n.type != '" + NodeType.DELETED.value()+ "'")
                        .setParameterList("rowCategories", rowCategories)
                        .setParameterList("colCategories", columnCategories)
                        .list();


            }

        });

    }

    public static class SimpleSurveillanceStatus implements SurveillanceStatus {
        private static final Logger LOG = LoggerFactory.getLogger(SimpleSurveillanceStatus.class);

        private int m_serviceOutages;
        private int m_upNodeCount;
        private int m_nodeCount;

        public SimpleSurveillanceStatus(Number serviceOutages, Number upNodeCount, Number nodeCount) {
            LOG.debug("Args: {} ({}), {} ({}), {} ({})",
                serviceOutages, serviceOutages == null ? null : serviceOutages.getClass(),
                upNodeCount, upNodeCount == null ? null : upNodeCount.getClass(),
                nodeCount, nodeCount == null ? null : nodeCount.getClass());

            m_serviceOutages = serviceOutages == null ? 0 : serviceOutages.intValue();
            m_upNodeCount = upNodeCount == null ? 0 : upNodeCount.intValue();
            m_nodeCount = nodeCount == null ? 0 : nodeCount.intValue();
        }

        @Override
        public Integer getDownEntityCount() {
            return m_nodeCount - m_upNodeCount;
        }

        @Override
        public Integer getTotalEntityCount() {
            return m_nodeCount;
        }

        @Override
        public String getStatus() {
            switch (m_serviceOutages) {
            case 0:  return "Normal";
            case 1:  return "Warning";
            default: return "Critical";
            }
        }

    }
    @Override
    public SurveillanceStatus findSurveillanceStatusByCategoryLists(final Collection<OnmsCategory> rowCategories, final Collection<OnmsCategory> columnCategories) {
        return getHibernateTemplate().execute(new HibernateCallback<SurveillanceStatus>() {

            @Override
            public SurveillanceStatus doInHibernate(Session session) throws HibernateException, SQLException {
                return (SimpleSurveillanceStatus)session.createSQLQuery("select" +
                        " count(distinct case when outages.outageid is not null and monSvc.status = 'A' then monSvc.id else null end) as svcCount," +
                        " count(distinct case when outages.outageid is null and monSvc.status = 'A' then node.nodeid else null end) as upNodeCount," +
                        " count(distinct node.nodeid) as nodeCount" +
                        " from node" +
                        " join category_node cn1 using (nodeid)" +
                        " join category_node cn2 using (nodeid)" +
                        " left outer join ipinterface ip using (nodeid)" +
                        " left outer join ifservices monsvc on (monsvc.ipinterfaceid = ip.id)" +
                        " left outer join outages on (outages.ifserviceid = monsvc.id and outages.ifregainedservice is null)" +
                        " where nodeType <> '" + NodeType.DELETED.value()+ "'" +
                        " and cn1.categoryid in (:rowCategories)" +
                        " and cn2.categoryid in (:columnCategories)"
                        )
                        .setParameterList("rowCategories", rowCategories)
                        .setParameterList("columnCategories", columnCategories)
                        .setResultTransformer(new ResultTransformer() {
                            private static final long serialVersionUID = 5152094813503430377L;

                            @Override
                            public Object transformTuple(Object[] tuple, String[] aliases) {
                                LOG.debug("tuple length = {}", tuple.length);
                                for (int i = 0; i < tuple.length; i++) {
                                    LOG.debug("{}: {} ({})", i, tuple[i], tuple[i].getClass());
                                }
                                return new SimpleSurveillanceStatus((Number)tuple[0], (Number)tuple[1], (Number)tuple[2]);
                            }

                            // Implements Hibernate API
                            @SuppressWarnings("rawtypes")
                            @Override
                            public List transformList(List collection) {
                                return collection;
                            }

                        })
                        .uniqueResult();
            }

        });

    }


    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Integer> getForeignIdToNodeIdMap(String foreignSource) {
        List<Object[]> pairs = (List<Object[]>)getHibernateTemplate().find("select n.id, n.foreignId from OnmsNode n where n.foreignSource = ?", foreignSource);
        Map<String, Integer> foreignIdMap = new HashMap<String, Integer>();
        for (Object[] pair : pairs) {
            foreignIdMap.put((String)pair[1], (Integer)pair[0]);
        }
        return Collections.unmodifiableMap(foreignIdMap);
    }

    /** {@inheritDoc} */
    @Override
    public List<OnmsNode> findByForeignSource(String foreignSource) {
        return find("from OnmsNode n where n.foreignSource = ?", foreignSource);
    }

    /** {@inheritDoc} */
    @Override
    public OnmsNode findByForeignId(String foreignSource, String foreignId) {
        return findUnique("from OnmsNode n where n.foreignSource = ? and n.foreignId = ?", foreignSource, foreignId);
    }

    /** {@inheritDoc} */
    @Override
    public List<OnmsNode> findByForeignSourceAndIpAddress(String foreignSource, String ipAddress) {
        if (foreignSource == null) {
            return find("select distinct n from OnmsNode n join n.ipInterfaces as ipInterface where n.foreignSource is NULL and ipInterface.ipAddress = ?", ipAddress);
        } else {
            return find("select distinct n from OnmsNode n join n.ipInterfaces as ipInterface where n.foreignSource = ? and ipInterface.ipAddress = ?", foreignSource, ipAddress);
        }
    }

    /** {@inheritDoc} */
    @Override
    public int getNodeCountForForeignSource(String foreignSource) {
        return queryInt("select count(*) from OnmsNode as n where n.foreignSource = ?", foreignSource);
    }

    /**
     * <p>findAll</p>
     *
     * @return a {@link java.util.List} object.
     */
    @Override
    public List<OnmsNode> findAll() {
        return find("from OnmsNode order by label");
    }

    /**
     * <p>findAllProvisionedNodes</p>
     *
     * @return a {@link java.util.List} object.
     */
    @Override
    public List<OnmsNode> findAllProvisionedNodes() {
        return find("from OnmsNode n where n.foreignSource is not null");
    }

    @Override
    public List<OnmsNode> findByIpAddressAndService(InetAddress ipAddress, String serviceName) {
        final org.opennms.core.criteria.Criteria criteria = new org.opennms.core.criteria.Criteria(OnmsNode.class)
        .setAliases(Arrays.asList(new Alias[] {
                new Alias("ipInterfaces","ipInterfaces", JoinType.LEFT_JOIN),
                new Alias("ipInterfaces.monitoredServices","monitoredServices", JoinType.LEFT_JOIN),
                new Alias("monitoredServices.serviceType","serviceType", JoinType.LEFT_JOIN)
        }))
        .addRestriction(new EqRestriction("ipInterfaces.ipAddress", ipAddress))
        //TODO: Replace D with a constant
        .addRestriction(new NeRestriction("ipInterfaces.isManaged", "D"))
        .addRestriction(new EqRestriction("serviceType.name", serviceName))
        .setOrders(Arrays.asList(new Order[] {
                Order.desc("id")
        }));

        return findMatching(criteria);
    }

    /** {@inheritDoc} */
    @Override
    public List<OnmsIpInterface> findObsoleteIpInterfaces(Integer nodeId, Date scanStamp) {
        // we exclude the primary interface from the obsolete list since the only way for them to be obsolete is when we have snmp
        return findObjects(OnmsIpInterface.class, "from OnmsIpInterface ipInterface where ipInterface.node.id = ? and ipInterface.snmpPrimary != 'P' and (ipInterface.ipLastCapsdPoll is null or ipInterface.ipLastCapsdPoll < ?)", nodeId, scanStamp);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteObsoleteInterfaces(Integer nodeId, Date scanStamp) {
        getHibernateTemplate().bulkUpdate("delete from OnmsIpInterface ipInterface where ipInterface.node.id = ? and ipInterface.snmpPrimary != 'P' and (ipInterface.ipLastCapsdPoll is null or ipInterface.ipLastCapsdPoll < ?)", new Object[] { nodeId, scanStamp });
        getHibernateTemplate().bulkUpdate("delete from OnmsSnmpInterface snmpInterface where snmpInterface.node.id = ? and (snmpInterface.lastCapsdPoll is null or snmpInterface.lastCapsdPoll < ?)", new Object[] { nodeId, scanStamp });
    }

    /** {@inheritDoc} */
    @Override
    public void updateNodeScanStamp(Integer nodeId, Date scanStamp) {
        OnmsNode n = get(nodeId);
        n.setLastCapsdPoll(scanStamp);
        update(n);
    }

    /**
     * <p>getNodeIds</p>
     *
     * @return a {@link java.util.Collection} object.
     */
    @Override
    public Collection<Integer> getNodeIds() {
        return findObjects(Integer.class, "select distinct n.id from OnmsNode as n where n.type != '" + NodeType.DELETED.value()+ "'");
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Long> getNumberOfNodesBySysOid() {
        List<Object[]> pairs = (List<Object[]>)getHibernateTemplate().find("select n.sysObjectId, count(*) from OnmsNode as n where n.sysObjectId != null group by sysObjectId");
        Map<String, Long> numberOfNodesBySysOid = new HashMap<String, Long>();
        for (Object[] pair : pairs) {
            numberOfNodesBySysOid.put((String)pair[0], (Long)pair[1]);
        }
        return Collections.unmodifiableMap(numberOfNodesBySysOid);
    }

    @Override
    public Integer getNextNodeId (Integer nodeId) {
        Integer nextNodeId = null;
        nextNodeId = findObjects(Integer.class, "select n.id from OnmsNode as n where n.id > ? and n.type != ? order by n.id asc limit 1", nodeId, String.valueOf(NodeType.DELETED.value())).get(0);
        return nextNodeId;
    }

    @Override
    public Integer getPreviousNodeId (Integer nodeId) {
        Integer nextNodeId = null;
        nextNodeId = findObjects(Integer.class, "select n.id from OnmsNode as n where n.id < ? and n.type != ? order by n.id desc limit 1", nodeId, String.valueOf(NodeType.DELETED.value())).get(0);
        return nextNodeId;
    }

    @Override
    public void markHavingFlows(final Collection<Integer> ingressIds, final Collection<Integer> egressIds) {
        getHibernateTemplate().executeWithNativeSession(session -> {
            int results = 0;

            if (!ingressIds.isEmpty()) {
                results += session.createSQLQuery("update node set last_ingress_flow = NOW() where nodeid in (:ids)")
                        .setParameterList("ids", ingressIds)
                        .executeUpdate();
            }
            if (!egressIds.isEmpty()) {
                results += session.createSQLQuery("update node set last_egress_flow = NOW() where nodeid in (:ids)")
                        .setParameterList("ids", egressIds)
                        .executeUpdate();
            }

            return results;
        });
    }

    @Override
    public List<OnmsNode> findAllHavingFlows() {
        if (OnmsSnmpInterface.INGRESS_AND_EGRESS_REQUIRED) {
            return find("from OnmsNode as n where (EXTRACT(EPOCH FROM (NOW() - lastIngressFlow)) <= " + OnmsSnmpInterface.MAX_FLOW_AGE +" AND EXTRACT(EPOCH FROM (NOW() - lastEgressFlow)) <= " + OnmsSnmpInterface.MAX_FLOW_AGE + ")");
        } else {
            return find("from OnmsNode as n where (EXTRACT(EPOCH FROM (NOW() - lastIngressFlow)) <= " + OnmsSnmpInterface.MAX_FLOW_AGE +" OR EXTRACT(EPOCH FROM (NOW() - lastEgressFlow)) <= " + OnmsSnmpInterface.MAX_FLOW_AGE + ")");
        }
    }

    @Override
    public List<OnmsNode> findAllHavingIngressFlows() {
        return find("from OnmsNode as n where EXTRACT(EPOCH FROM (NOW() - lastIngressFlow)) <= " + OnmsSnmpInterface.MAX_FLOW_AGE);
    }

    @Override
    public List<OnmsNode> findAllHavingEgressFlows() {
        return find("from OnmsNode as n where EXTRACT(EPOCH FROM (NOW() - lastEgressFlow)) <= " + OnmsSnmpInterface.MAX_FLOW_AGE);
    }

    @Override
    public List<OnmsNode> findBySysNameOfLldpLinksOfNode(int nodeId) {
        return find("from OnmsNode as n where n.sysName in (select l.lldpRemSysname from LldpLink l where l.node.id = ?)", nodeId);
    }

    @Override
    public OnmsNode getDefaultFocusPoint() {
        // getting the node which has the most ifspeed
        final String query2 = "select node.id from OnmsSnmpInterface as snmp join snmp.node as node group by node order by sum(snmp.ifSpeed) desc";

        // is there already a node?
        OnmsNode focusNode = getHibernateTemplate().execute(new HibernateCallback<OnmsNode>() {
            public OnmsNode doInHibernate(Session session) throws HibernateException, SQLException {
                Integer nodeId = (Integer)session.createQuery(query2).setMaxResults(1).uniqueResult();
                return getNode(nodeId, session);
            }
        });

        return focusNode;
    }

    private OnmsNode getNode(Integer nodeId, Session session) {
        if (nodeId != null) {
            Query q = session.createQuery("from OnmsNode as n where n.id = :nodeId");
            q.setInteger("nodeId",  nodeId);
            return (OnmsNode)q.uniqueResult();
        }
        return null;
    }

    public List<OnmsNode> findNodeWithMetaData(final String context, final String key, final String value, final boolean matchEnumeration) {
        // what is happening here?
        // 1. in the case matchEnumeration is set to true, we try to find the given value by using the following regular expression: (?:^[ ,]*|,[ ]*)stringToSearchFor(?=[ ]*,|,?[ ]*$)
        // 2. of course the value to search for needs to be escaped, so we use REGEXP_REPLACE(:value, '([\.\+\*\?\^\$\(\)\[\]\{\}\|\\])', '\\\1', 'g') here
        return getHibernateTemplate().execute(session -> (List<OnmsNode>) session.createSQLQuery("SELECT n.nodeid FROM node n, node_metadata m WHERE m.id = n.nodeid AND context = :context AND key = :key AND value " + (matchEnumeration ? "~ CONCAT('(?:^[ ,]*|,[ ]*)', REGEXP_REPLACE(:value, '([\\.\\+\\*\\?\\^\\$\\(\\)\\[\\]\\{\\}\\|\\\\])', '\\\\\\1', 'g'), '(?=[ ]*,|,?[ ]*$)')" : "= :value" ) + " ORDER BY n.nodeid")
                .setString("context", context)
                .setString("key", key)
                .setString("value", value)
                .setResultTransformer(new ResultTransformer() {
                    @Override
                    public Object transformTuple(Object[] tuple, String[] aliases) {
                        return get((Integer) tuple[0]);
                    }

                    @SuppressWarnings("rawtypes")
                    @Override
                    public List transformList(List collection) {
                        return collection;
                    }
                }).list());
    }
}
