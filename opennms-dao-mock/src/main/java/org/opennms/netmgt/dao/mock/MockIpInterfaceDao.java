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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.opennms.netmgt.dao.api.IpInterfaceDao;
import org.opennms.netmgt.model.OnmsIpInterface;
import org.opennms.netmgt.model.OnmsMonitoredService;
import org.opennms.netmgt.model.OnmsNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockIpInterfaceDao extends AbstractMockDao<OnmsIpInterface, Integer> implements IpInterfaceDao {
    private static final Logger LOG = LoggerFactory.getLogger(MockIpInterfaceDao.class);
    private AtomicInteger m_id = new AtomicInteger(0);

    @Override
    public Integer save(final OnmsIpInterface iface) {
        updateParent(iface);
        Integer retval = super.save(iface);
        updateSubObjects(iface);
        return retval;
    }

    @Override
    public void update(final OnmsIpInterface iface) {
        updateParent(iface);
        super.update(iface);
        updateSubObjects(iface);
    }

    @Override
    public void flush() {
        super.flush();
        for (final OnmsIpInterface iface : findAll()) {
            updateSubObjects(iface);
        }
    }

    private void updateParent(final OnmsIpInterface iface) {
        OnmsNode node = null;
        if (iface.getNodeId() != null) {
            node = getNodeDao().get(iface.getNodeId());
        } else if (iface.getNode() != null) {
            node = getNodeDao().findByForeignId(iface.getNode().getForeignSource(), iface.getNode().getForeignId());
            if (node == null) {
                node = getNodeDao().findByForeignId(iface.getNode().getForeignSource(), iface.getNode().getForeignId());
            }
        }
        if (node != null && node != iface.getNode()) {
            LOG.debug("merging node {} into node {}", iface.getNode(), node);
            node.mergeNode(iface.getNode(), new NullEventForwarder(), false);
            iface.setNode(node);
        }
        if (!iface.getNode().getIpInterfaces().contains(iface)) {
            LOG.debug("adding IP interface to node {}: {}", iface.getNode().getId(), iface);
            iface.getNode().addIpInterface(iface);
        }
    }

    private void updateSubObjects(final OnmsIpInterface iface) {
        for (final OnmsMonitoredService svc : iface.getMonitoredServices()) {
            getMonitoredServiceDao().saveOrUpdate(svc);
        }
    }

    @Override
    public void delete(final OnmsIpInterface iface) {
        super.delete(iface);
        for (final OnmsMonitoredService svc : iface.getMonitoredServices()) {
            getMonitoredServiceDao().delete(svc);
        }
    }

    @Override
    protected void generateId(final OnmsIpInterface iface) {
        iface.setId(m_id.incrementAndGet());
    }

    @Override
    protected Integer getId(final OnmsIpInterface iface) {
        return iface.getId();
    }

    @SuppressWarnings("deprecation")
    @Override
    public OnmsIpInterface get(final OnmsNode node, final String ipAddress) {
        for (final OnmsIpInterface iface : findAll()) {
            if (node.equals(iface.getNode()) && ipAddress.equals(iface.getIpAddressAsString())) {
                return iface;
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    @Override
    public OnmsIpInterface findByNodeIdAndIpAddress(final Integer nodeId, final String ipAddress) {
        for (final OnmsIpInterface iface : findAll()) {
            if (iface.getNode().getId().equals(nodeId) && ipAddress.equals(iface.getIpAddressAsString())) {
                return iface;
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    @Override
    public OnmsIpInterface findByForeignKeyAndIpAddress(final String foreignSource, final String foreignId, final String ipAddress) {
        if (foreignId == null || ipAddress == null) {
            return null;
        }
        for (final OnmsIpInterface iface : findAll()) {
            if (!Objects.equals(ipAddress, iface.getIpAddressAsString())) {
                continue;
            }
            if (Objects.equals(foreignSource, iface.getForeignSource()) && Objects.equals(foreignId, iface.getForeignId())) {
                return iface;
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    @Override
    public List<OnmsIpInterface> findByIpAddress(final String ipAddress) {
        final List<OnmsIpInterface> ifaces = new ArrayList<>();
        if (ipAddress == null) return ifaces;
        for (final OnmsIpInterface iface : findAll()) {
            if (ipAddress.equals(iface.getIpAddressAsString())) {
                ifaces.add(iface);
            }
        }
        return ifaces;
    }

    @Override
    public List<OnmsIpInterface> findByNodeId(final Integer nodeId) {
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public List<OnmsIpInterface> findByServiceType(final String svcName) {
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public List<OnmsIpInterface> findHierarchyByServiceType(final String svcName) {
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public Map<InetAddress, Integer> getInterfacesForNodes() {
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public OnmsIpInterface findPrimaryInterfaceByNodeId(final Integer nodeId) {
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public List<OnmsIpInterface> findInterfacesWithMetadata(String context, String key, String value, final boolean matchEnumeration) {
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public List<OnmsIpInterface> findByMacLinksOfNode(Integer nodeId) {
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public List<OnmsIpInterface> findByIpAddressAndLocation(String location, String address) {
        throw new UnsupportedOperationException("Not yet implemented!");
    }
}
