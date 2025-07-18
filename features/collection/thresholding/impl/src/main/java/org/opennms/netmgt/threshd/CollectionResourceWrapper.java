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
package org.opennms.netmgt.threshd;

import static org.opennms.core.utils.InetAddressUtils.addr;

import java.io.File;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.opennms.netmgt.collectd.AliasedResource;
import org.opennms.netmgt.collectd.IfInfo;
import org.opennms.netmgt.collection.api.AttributeType;
import org.opennms.netmgt.collection.api.CollectionAttribute;
import org.opennms.netmgt.collection.api.CollectionResource;
import org.opennms.netmgt.collection.api.LatencyCollectionResource;
import org.opennms.netmgt.dao.api.IfLabel;
import org.opennms.netmgt.model.ResourceId;
import org.opennms.netmgt.model.ResourceTypeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>CollectionResourceWrapper class.</p>
 * 
 * Wraps a CollectionResource with some methods and caching for the efficient application of thresholds (without
 * pulling thresholding code into CollectionResource itself)
 * 
 * A fresh instance should be created for each collection cycle (assumptions are made based on that premise)
 *
 * @author ranger
 * @version $Id: $
 */
public class CollectionResourceWrapper {
    
    private static final Logger LOG = LoggerFactory.getLogger(CollectionResourceWrapper.class);
    
    private final int m_nodeId;
    private final String m_hostAddress;
    private final String m_serviceName;
    private String m_dsLabel;
    private String m_exprLabel;
    private final String m_iflabel;
    private final String m_ifindex;
    private final CollectionResource m_resource;
    private final Map<String, CollectionAttribute> m_attributes;
    private final Long m_sequenceNumber;

    /**
     * Keeps track of both the Double value, and when it was collected, for the static cache of attributes
     * 
     * This is necessary for the *correct* calculation of Counter rates, across variable collection times and possible
     * collection failures (see NMS-4244)
     */
    public static class CacheEntry {
        private final Date m_timestamp;
        private final Double m_value;

        public CacheEntry(final Date timestamp, final Double value) {
            if (timestamp == null) {
                throw new IllegalArgumentException("Illegal null timestamp in cache value");
            } else if (value == null) {
                throw new IllegalArgumentException("Illegal null value in cache value");
            }
            this.m_timestamp = timestamp;
            this.m_value = value;
        }

        public Date getTimestamp() {
            return m_timestamp;
        }

        public Double getValue() {
            return m_value;
        }
    }

    /*
     * Holds last values for counter attributes (in order to calculate delta)
     */
    static final ConcurrentHashMap<String, CacheEntry> s_cache = new ConcurrentHashMap<String,CacheEntry>();
    
    /*
     * To avoid update static cache on every call of getAttributeValue.
     * In some cases, the same DS could be needed in many thresholds definitions for same resource.
     * See Bug 3193
     */
    private final Map<String, Double> m_localCache = new HashMap<String,Double>();
    
    /*
     * Holds interface ifInfo data for interface resource only. This avoid multiple calls to database for same resource.
     */
    private final Map<String, String> m_ifInfo = new HashMap<String,String>();
    
    /*
	 * Holds the timestamp of the collection being thresholded, for the calculation of counter rates
     */
    private final Date m_collectionTimestamp;

    /*
     * true, if the sysUpTime wrap or abrupt reset has been detected.
     */
    private boolean m_counterReset = false;

    public CollectionResourceWrapper(Date collectionTimestamp, int nodeId, String hostAddress, String serviceName,
            CollectionResource resource, Map<String, CollectionAttribute> attributes,
            IfLabel ifLabelDao, Long sequenceNumber) {

        if (collectionTimestamp == null) {
            throw new IllegalArgumentException(String.format("%s: Null collection timestamp when thresholding service %s on node %d (%s)", this.getClass().getSimpleName(), serviceName, nodeId, hostAddress));
        }

        m_collectionTimestamp = collectionTimestamp;
        m_nodeId = nodeId;
        m_hostAddress = hostAddress;
        m_serviceName = serviceName;
        m_resource = resource;
        m_attributes = attributes;
        m_sequenceNumber = sequenceNumber;

        if (isAnInterfaceResource()) {
            if (resource instanceof AliasedResource) { // TODO What about AliasedResource's custom attributes?
                m_iflabel = ((AliasedResource) resource).getInterfaceLabel();
                m_ifInfo.putAll(((AliasedResource) resource).getIfInfo().getAttributesMap());
                m_ifInfo.put("domain", ((AliasedResource) resource).getDomain());
            } else if (resource instanceof IfInfo) {
                m_iflabel = ((IfInfo) resource).getInterfaceLabel();
                m_ifInfo.putAll(((IfInfo) resource).getAttributesMap());
            } else {
                LOG.info("Can't find ifInfo for {}", resource);
                m_iflabel = null;
            }
            m_ifindex = m_ifInfo.get("snmpifindex");
        } else if (isLatencyResource() && ifLabelDao != null) {
            String ipAddress = ((LatencyCollectionResource) resource).getIpAddress();
            m_iflabel = ifLabelDao.getIfLabel(getNodeId(), addr(ipAddress));
            if (m_iflabel != null) { // See Bug 3488
                m_ifInfo.putAll(ifLabelDao.getInterfaceInfoFromIfLabel(getNodeId(), m_iflabel));
            } else {
                LOG.info("Can't find ifLabel for latency resource {} on node {}", resource.getInstance(), getNodeId());
            }
            m_ifindex = null;
        } else {
            m_ifindex = null;
            m_iflabel = null;
        }
    }

    public void setCounterReset(boolean counterReset) {
        this.m_counterReset = counterReset;
    }

    /**
     * <p>getNodeId</p>
     *
     * @return a int.
     */
    public int getNodeId() {
        return m_nodeId;
    }

    /**
     * <p>getHostAddress</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getHostAddress() {
        return m_hostAddress;
    }

    /**
     * <p>getServiceName</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getServiceName() {
        return m_serviceName;
    }

    /**
     * <p>getDsLabel</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getDsLabel() {
        return m_dsLabel;
    }

    /**
     * <p>setDsLabel</p>
     *
     * @param dsLabel a {@link java.lang.String} object.
     */
    public void setDsLabel(String dsLabel) {
        m_dsLabel = dsLabel;
    }

     /**
     * <p>getExprLabel</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getExprLabel() {
        return m_exprLabel;
    }

    /**
     * <p>setExprLabel</p>
     *
     * @param exprLabel a {@link java.lang.String} object.
     */
    public void setExprLabel(String exprLabel) {
        m_exprLabel = exprLabel;
    }

    /**
     * <p>getInstance</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getInstance() {
        return m_resource != null ? m_resource.getInstance() : null;
    }

    /**
     * <p>getInstanceLabel</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getInstanceLabel() {
        return m_resource != null ? m_resource.getInterfaceLabel() : null;
    }

    /**
     * <p>getResourceTypeName</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getResourceTypeName() {
        return m_resource != null ? m_resource.getResourceTypeName() : null;
    }

    /**
     * <p>getResourceId</p>
     * <p>Inspired by DefaultKscReportService</p>
     * 
     * @return a {@link java.lang.String} object.
     */
    public ResourceId getResourceId() {
        if (m_resource == null) {
            return null;
        }

        String resourceType  = getResourceTypeName();
        String resourceLabel = getInstanceLabel();
        if (CollectionResource.RESOURCE_TYPE_NODE.equals(resourceType)) {
            resourceType  = "nodeSnmp";
            resourceLabel = "";
        }
        if (CollectionResource.RESOURCE_TYPE_IF.equals(resourceType)) {
            resourceType = "interfaceSnmp";
        }
        String parentResourceTypeName = CollectionResource.RESOURCE_TYPE_NODE;
        String parentResourceName = Integer.toString(getNodeId());
        // I can't find a better way to deal with this when storeByForeignSource is enabled        
        if (m_resource.getParent() != null && m_resource.getParent().toString().startsWith(ResourceTypeUtils.FOREIGN_SOURCE_DIRECTORY)) {
            // If separatorChar is backslash (like on Windows) use a double-escaped backslash in the regex
            String[] parts = m_resource.getParent().toString().split(File.separatorChar == '\\' ? "\\\\" : File.separator);
            if (parts.length == 3) {
                parentResourceTypeName = "nodeSource";
                parentResourceName = parts[1] + ":" + parts[2];
            }
        }
        return ResourceId.get(parentResourceTypeName, parentResourceName).resolve(resourceType, resourceLabel);
    }

    /**
     * <p>getIfLabel</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getIfLabel() {
        return m_iflabel;
    }
    
    /**
     * <p>getIfIndex</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getIfIndex() {
        return m_ifindex;
    }
    
    /**
     * <p>getIfInfoValue</p>
     *
     * @param attribute a {@link java.lang.String} object.
     * @return a {@link java.lang.String} object.
     */
    protected String getIfInfoValue(String attribute) {
        if (m_ifInfo != null) {
            return m_ifInfo.get(attribute);
        } else {
            return null;
        }
    }

    public boolean isLatencyResource() {
        return getResourceTypeName() != null && CollectionResource.RESOURCE_TYPE_LATENCY.equals(getResourceTypeName());
    }

    /**
     * <p>isNodeResource</p>
     *
     * @return a boolean.
     */
    public boolean isNodeResource() {
        return getResourceTypeName() != null && CollectionResource.RESOURCE_TYPE_NODE.equals(getResourceTypeName());
    }

    /**
     * <p>isAnInterfaceResource</p>
     *
     * @return a boolean.
     */
    public boolean isAnInterfaceResource() {
        return getResourceTypeName() != null && CollectionResource.RESOURCE_TYPE_IF.equals(getResourceTypeName());
    }

    /**
     * <p>isValidInterfaceResource</p>
     *
     * @return a boolean.
     */
    public boolean isValidInterfaceResource() {
        try {
            if(m_ifindex == null) {
                return true; // allowing null is required for Telemetry Resources
            } else if(Integer.parseInt(m_ifindex) < 0) {
                return false;
            }
        } catch(Throwable e) {
            return false;
        }
        return true;
    }

    /**
     * <p>getAttributeValue</p>
     *
     * @param ds a {@link java.lang.String} object.
     * @return a {@link java.lang.Double} object.
     */
    public Double getAttributeValue(String ds) {
        if (isAnInterfaceResource() && ("snmpifspeed".equalsIgnoreCase(ds) || "snmpiftype".equalsIgnoreCase(ds))) { // Get Value from ifInfo only for Interface Resource
            String value = getIfInfoValue(ds);
            if (value != null) {
                try {
                    return Double.parseDouble(value);
                } catch (Exception e) {
                    return Double.NaN;
                }
            }
        }
        if (m_attributes == null || m_attributes.get(ds) == null) {
            LOG.info("getAttributeValue: can't find attribute called {} on {}", ds, m_resource);
            return null;
        }
        Number numValue = m_attributes.get(ds).getNumericValue();
        if (numValue == null) {
            LOG.info("getAttributeValue: can't find numeric value for {} on {}", ds, m_resource);
            return null;
        }
        // Generating a unique ID for the node/resourceType/resource/metric combination.
        String id =  "node[" + m_nodeId + "].resourceType[" + m_resource.getResourceTypeName() + "].instance[" + m_resource.getInterfaceLabel() + "].metric[" + ds + "]";
        Double current = numValue.doubleValue();
        if (!AttributeType.COUNTER.equals(m_attributes.get(ds).getType())) {
            LOG.debug("getAttributeValue: id={}, value= {}", id, current);
            return current;
        } else {
            return getCounterValue(id, current);
        }
    }

    /*
     * This will return the rate based on configured collection step
     */
    private Double getCounterValue(String id, Double current) {
        synchronized (m_localCache) {

        if (m_localCache.containsKey(id) == false) {
            // Atomically replace the CacheEntry with the new value
            // If the sysUpTime was changed, the "last" value must be null (to force update the cache).
            CacheEntry last = m_counterReset ? null : s_cache.put(id, new CacheEntry(m_collectionTimestamp, current));
            LOG.debug("getCounterValue: id={}, last={}, current={}", id, (last==null ? last : last.m_value +"@"+ last.m_timestamp), current);
            if (last == null) {
                m_localCache.put(id, Double.NaN);
                if (m_counterReset) {
                    s_cache.put(id, new CacheEntry(m_collectionTimestamp, current));
                }
                LOG.info("getCounterValue: unknown last value for {}, ignoring current", id);
            } else {                
                Double delta = current.doubleValue() - last.m_value.doubleValue();
                // wrapped counter handling(negative delta), rrd style
                if (delta < 0) {
                    double newDelta = delta.doubleValue();
                    // 2-phase adjustment method
                    // try 32-bit adjustment
                    newDelta += Math.pow(2, 32);
                    if (newDelta < 0) {
                        // try 64-bit adjustment
                        newDelta += Math.pow(2, 64) - Math.pow(2, 32);
                    }
                    LOG.info("getCounterValue: {}(counter) wrapped counter adjusted last={}@{}, current={}, olddelta={}, newdelta={}", id, last.m_value, last.m_timestamp, current, delta, newDelta);
                    delta = newDelta;
                }
                // Get the interval between when this current collection was taken, and the last time this
                // value was collected (and had a counter rate calculated for it).
                // If the interval is zero, than the current rate must returned as 0.0 since there can be 
                // no delta across a time interval of zero.
                long interval = ( m_collectionTimestamp.getTime() - last.m_timestamp.getTime() ) / 1000;
                if (interval > 0) {
                    final Double value = (delta/interval);
                    LOG.debug("getCounterValue: id={}, value={}, delta={}, interval={}", id, value, delta, interval);
                    m_localCache.put(id, value);
                } else {
                    LOG.info("getCounterValue: invalid zero-length rate interval for {}, returning rate of zero", id);
                    m_localCache.put(id, 0.0);
                    // Restore the original value inside the static cache
                    s_cache.put(id, last);
                }
            }
        }
        Double value = m_localCache.get(id);
        // This is just a sanity check, we should never have a value of null for the value at this point
        if (value == null) {
            LOG.error("getCounterValue: value was not calculated correctly for {}, using NaN", id);
            m_localCache.put(id, Double.NaN);
            return Double.NaN;
        } else {
            return value;
        }

        }
    }

    /**
     * <p>getFieldValue</p>
     *
     * @param ds a {@link java.lang.String} object.
     * @return a {@link java.lang.String} object.
     */
    public String getFieldValue(String ds) {
        if (ds == null || "".equals(ds)) {
            return null;
        }
        LOG.debug("getLabelValue: Getting Value for {}::{}", m_resource.getResourceTypeName(), ds);
        if ("nodeid".equalsIgnoreCase(ds)) {
            return Integer.toString(m_nodeId);
        } else if ("ipaddress".equalsIgnoreCase(ds)) {
            return m_hostAddress;
        } else if ("iflabel".equalsIgnoreCase(ds)) {
            return getIfLabel();
        } else if ("id".equalsIgnoreCase(ds)) {
            return m_resource.getPath().getName().toString();
        }

        try {
            String retval = null;

            // Get Value from ifInfo only for Interface Resource
            if (isAnInterfaceResource()) {
                retval = getIfInfoValue(ds);
                if (retval != null) {
                    return retval;
                }
            }

            // Find values saved in string attributes
            final var attr = m_attributes.get(ds);
            if (attr != null && attr.getType() == AttributeType.STRING) {
                return attr.getStringValue();
            }
        } catch (Throwable e) {
            LOG.info("getFieldValue: Can't get value for attribute {} for resource {}.", ds, m_resource, e);
        }

        LOG.debug("getFieldValue: The field {} is not a string property. Trying to parse it as numeric metric.", ds);
        Double d = getAttributeValue(ds);
        if (d != null) {
            return d.toString();
        }

        return null;
    }

    public Long getSequenceNumber() {
        return m_sequenceNumber;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return m_resource.toString();
    }

}
