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
package org.opennms.netmgt.collectd;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.opennms.core.rpc.mock.MockRpcClientFactory;
import org.opennms.core.test.MockPlatformTransactionManager;
import org.opennms.netmgt.collection.api.AttributeGroupType;
import org.opennms.netmgt.collection.api.ServiceParameters;
import org.opennms.netmgt.config.collectd.Filter;
import org.opennms.netmgt.config.collectd.Package;
import org.opennms.netmgt.config.collectd.Service;
import org.opennms.netmgt.config.datacollection.MibObject;
import org.opennms.netmgt.config.datacollection.Parameter;
import org.opennms.netmgt.config.datacollection.PersistenceSelectorStrategy;
import org.opennms.netmgt.config.datacollection.StorageStrategy;
import org.opennms.netmgt.dao.api.IpInterfaceDao;
import org.opennms.netmgt.dao.mock.MockDataCollectionConfigDao;
import org.opennms.netmgt.model.NetworkBuilder;
import org.opennms.netmgt.model.OnmsIpInterface;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.snmp.SnmpInstId;
import org.opennms.netmgt.snmp.SnmpValue;
import org.opennms.netmgt.snmp.proxy.LocationAwareSnmpClient;
import org.opennms.netmgt.snmp.proxy.common.LocationAwareSnmpClientRpcImpl;
import org.opennms.netmgt.snmp.snmp4j.Snmp4JValueFactory;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Test class for PersistRegexSelectorStrategy
 * 
 * @author <a href="mail:agalue@opennms.org">Alejandro Galue</a>
 */
public class PersistRegexSelectorStrategyTest {

    private IpInterfaceDao ipInterfaceDao;
    private GenericIndexResource resourceA;
    private GenericIndexResource resourceB;
    private GenericIndexResource resourceC;
    private GenericIndexResource resourceD;
    private ServiceParameters serviceParams;

    @Before
    public void setUp() throws Exception {
        ipInterfaceDao = mock(IpInterfaceDao.class);
        String localhost = InetAddress.getLocalHost().getHostAddress();

        NetworkBuilder builder = new NetworkBuilder();
        builder.addNode("myNode");
        builder.addInterface(localhost).setIsManaged("M").setIsSnmpPrimary("P");
        OnmsNode node = builder.getCurrentNode();
        node.setId(1);
        OnmsIpInterface ipInterface = node.getIpInterfaces().iterator().next();
        when(ipInterfaceDao.load(1)).thenReturn(ipInterface);

        Package pkg = new Package();
        pkg.setName("junitTestPackage");
        Filter filter = new Filter();
        filter.setContent("IPADDR != '0.0.0.0'");
        pkg.setFilter(filter);
        Service service = new Service();
        service.setName("SNMP");
        pkg.addService(service);
        Map<String, Object> map = new TreeMap<String, Object>();
        List<org.opennms.netmgt.config.collectd.Parameter> params = pkg.getService("SNMP").getParameters();
        for (org.opennms.netmgt.config.collectd.Parameter p : params) {
            map.put(p.getKey(), p.getValue());
        }
        map.put("collection", "default");
        serviceParams = new ServiceParameters(map);

        LocationAwareSnmpClient locationAwareSnmpClient = new LocationAwareSnmpClientRpcImpl(new MockRpcClientFactory());
        PlatformTransactionManager ptm = new MockPlatformTransactionManager();
        SnmpCollectionAgent agent = DefaultSnmpCollectionAgent.create(1, ipInterfaceDao, ptm);
        OnmsSnmpCollection snmpCollection = new OnmsSnmpCollection(agent, serviceParams, new MockDataCollectionConfigDao(), locationAwareSnmpClient);

        org.opennms.netmgt.config.datacollection.ResourceType rt = new org.opennms.netmgt.config.datacollection.ResourceType();
        rt.setName("myResourceType");
        StorageStrategy storageStrategy = new StorageStrategy();
        storageStrategy.setClazz("org.opennms.netmgt.collection.support.IndexStorageStrategy");
        rt.setStorageStrategy(storageStrategy);
        PersistenceSelectorStrategy persistenceSelectorStrategy = new PersistenceSelectorStrategy();
        persistenceSelectorStrategy.setClazz("org.opennms.netmgt.collectd.PersistRegexSelectorStrategy");
        Parameter param = new Parameter();
        param.setKey(PersistRegexSelectorStrategy.MATCH_EXPRESSION);
        param.setValue("#name matches '^agalue.*$'");
        persistenceSelectorStrategy.addParameter(param);
        rt.setPersistenceSelectorStrategy(persistenceSelectorStrategy);
        GenericIndexResourceType resourceType = new GenericIndexResourceType(agent, snmpCollection, rt);

        resourceA = new GenericIndexResource(resourceType, rt.getName(), new SnmpInstId("1.2.3.4.5.6.7.8.9.1.1"));
        
        AttributeGroupType groupType = new AttributeGroupType("mib2-interfaces", AttributeGroupType.IF_TYPE_ALL);
        MibObject mibObject = new MibObject();
        mibObject.setOid(".1.2.3.4.5.6.7.8.9.2.1");
        mibObject.setInstance("1");
        mibObject.setAlias("name");
        mibObject.setType("string");
        StringAttributeType attributeType = new StringAttributeType(resourceType, snmpCollection.getName(), mibObject, groupType);
        SnmpValue snmpValue = new Snmp4JValueFactory().getOctetString("agalue rules!".getBytes());
        resourceA.setAttributeValue(attributeType, snmpValue);
        
        resourceB = new GenericIndexResource(resourceType, rt.getName(), new SnmpInstId("1.2.3.4.5.6.7.8.9.1.2"));

        // selector sensitive to instance IDs
        org.opennms.netmgt.config.datacollection.ResourceType rtInst = new org.opennms.netmgt.config.datacollection.ResourceType();
        rtInst.setName("myResourceTypeTwo");
        rtInst.setStorageStrategy(storageStrategy);
        PersistenceSelectorStrategy persistenceSelectorStrategyInst = new PersistenceSelectorStrategy();
        persistenceSelectorStrategyInst.setClazz("org.opennms.netmgt.collectd.PersistRegexSelectorStrategy");
        Parameter paramInst = new Parameter();
        paramInst.setKey(PersistRegexSelectorStrategy.MATCH_EXPRESSION);
        paramInst.setValue("#instance matches '.*\\.3$'");
        persistenceSelectorStrategyInst.addParameter(paramInst);
        rtInst.setPersistenceSelectorStrategy(persistenceSelectorStrategyInst);
        GenericIndexResourceType resourceTypeInst = new GenericIndexResourceType(agent, snmpCollection, rtInst);

        resourceC = new GenericIndexResource(resourceTypeInst, rtInst.getName(), new SnmpInstId("1.2.3.4.5.6.7.8.9.1.3"));
        resourceD = new GenericIndexResource(resourceTypeInst, rtInst.getName(), new SnmpInstId("1.2.3.4.5.6.7.8.9.1.4"));
    }

    @After
    public void tearDown() throws Exception {
        verifyNoMoreInteractions(ipInterfaceDao);
    }

    @Test
    public void testPersistSelector() throws Exception {
        Assert.assertTrue("resourceA matches parameter expression", resourceA.shouldPersist(serviceParams));
        Assert.assertFalse("resourceB doesn't matche parameter expression", resourceB.shouldPersist(serviceParams));
        Assert.assertTrue("resourceC matches instance expression", resourceC.shouldPersist(serviceParams));
        Assert.assertFalse("resourceD doesn't match instance expression", resourceD.shouldPersist(serviceParams));

        verify(ipInterfaceDao, atLeastOnce()).load(anyInt());
    }

    @Test
    public void testSpringEl() throws Exception {
        ExpressionParser parser = new SpelExpressionParser();
        Expression exp = parser.parseExpression("#name matches '^Alejandro.*'");
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariable("name", "Alejandro Galue");
        boolean result = (Boolean)exp.getValue(context, Boolean.class);
        Assert.assertTrue(result);

        verify(ipInterfaceDao, atLeastOnce()).load(anyInt());
    }
}
