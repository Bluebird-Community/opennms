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
package org.opennms.smoketest.utils;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import joptsimple.internal.Strings;
import org.glassfish.jersey.client.ClientProperties;
import org.opennms.core.xml.JaxbUtils;
import org.opennms.netmgt.measurements.model.QueryRequest;
import org.opennms.netmgt.measurements.model.QueryResponse;
import org.opennms.netmgt.model.OnmsAlarmCollection;
import org.opennms.netmgt.model.OnmsApplication;
import org.opennms.netmgt.model.OnmsCategory;
import org.opennms.netmgt.model.OnmsEvent;
import org.opennms.netmgt.model.OnmsEventCollection;
import org.opennms.netmgt.model.OnmsIpInterface;
import org.opennms.netmgt.model.OnmsMonitoredService;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.model.minion.OnmsMinion;
import org.opennms.netmgt.model.resource.ResourceDTO;
import org.opennms.netmgt.provision.persist.requisition.Requisition;
import org.opennms.netmgt.xml.event.Event;
import org.opennms.smoketest.containers.OpenNMSContainer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class RestClient {
    private static final Logger LOG = LoggerFactory.getLogger(RestClient.class);

    private static final String DEFAULT_USERNAME = OpenNMSContainer.ADMIN_USER;

    private static final String DEFAULT_PASSWORD = OpenNMSContainer.ADMIN_PASSWORD;

    private final String authorizationHeader;

    private final URL url;

    public RestClient(InetSocketAddress addr) {
        this(addr, DEFAULT_USERNAME, DEFAULT_PASSWORD);
    }

    public RestClient(URL url) {
        this(url, DEFAULT_USERNAME, DEFAULT_PASSWORD);
    }

    public RestClient(InetSocketAddress addr, String username, String password) {
        this(toUrl(addr), username, password);
    }

    public RestClient(URL url, String username, String password) {
        this.url = url;
        authorizationHeader = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
    }

    /**
     * Create a REST client that connects to http://localhost:8980/ with the default credentials.
     * Used for testing.
     *
     * @return a new REST client for OpenNMS on the localhost
     */
    public static RestClient forLocalhost() {
        return new RestClient(InetSocketAddress.createUnresolved("127.0.0.1", 8980));
    }

    private static URL toUrl(InetSocketAddress addr) {
        try {
            return new URL(String.format("http://%s:%d/opennms", addr.getHostString(), addr.getPort()));
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    public String getDisplayVersion() {
        final WebTarget target = getTarget().path("info");
        final String json = getBuilder(target).get(String.class);

        final ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode actualObj = mapper.readTree(json);
            return actualObj.get("displayVersion").asText();
        } catch (IOException e) {
            LOG.debug("Failed to get displayVersion from the info REST service. (OpenNMS is probably not up yet): {}", e.getMessage());
        }
        return null;
    }

    public void addOrReplaceRequisition(Requisition requisition) {
        final WebTarget target = getTarget().path("requisitions");
        getBuilder(target).post(Entity.entity(requisition, MediaType.APPLICATION_XML));
    }

    public void importRequisition(final String foreignSource) {
        final WebTarget target = getTarget().path("requisitions").path(foreignSource).path("import");
        getBuilder(target).put(null);
    }

    public List<OnmsNode> getNodes() {
        GenericType<List<OnmsNode>> nodes = new GenericType<List<OnmsNode>>() {
        };
        final WebTarget target = getTarget().path("nodes");
        return getBuilder(target).get(nodes);
    }

    public List<OnmsMonitoredService> getServicesForANode(String nodeCriteria, String ipAddress) {
        GenericType<List<OnmsMonitoredService>> services = new GenericType<List<OnmsMonitoredService>>() {
        };
        final WebTarget target = getTarget().path("nodes").path(nodeCriteria).path("ipinterfaces").path(ipAddress)
                .path("services");
        return getBuilder(target).get(services);
    }

    public QueryResponse getMeasurements(final QueryRequest request) {
        final WebTarget target = getTarget().path("measurements");
        return getBuilder(target).post(Entity.entity(request, MediaType.APPLICATION_XML), QueryResponse.class);
    }

    public OnmsNode getNode(String nodeCriteria) {
        final WebTarget target = getTarget().path("nodes").path(nodeCriteria);
        return getBuilder(target).get(OnmsNode.class);
    }

    public Map<String, Object> getUsageStatistics() throws Exception {
        final Response response = getBuilder(getTarget().path("datachoices")).get();
        final String jsonContent = response.readEntity(String.class);
        final Map<String, Object> hashMap = new ObjectMapper().readValue(jsonContent, HashMap.class);
        return hashMap.entrySet().stream().collect(Collectors.toMap(e -> e.getKey(), e-> {
                    if (e.getValue() instanceof Integer) {
                        return Long.valueOf( (Integer) e.getValue());
                    } else {
                        return e.getValue();
                    }
                }));
    }

    public Response getResponseForNode(String nodeCriteria) {
        final WebTarget target = getTarget().path("nodes").path(nodeCriteria);
        return getBuilder(target).get();
    }

    public Response addNode(OnmsNode onmsNode) {
        final WebTarget target = getTarget().path("nodes");
        return getBuilder(target).post(Entity.entity(onmsNode, MediaType.APPLICATION_XML));
    }

    public Response addInterface(String nodeCriteria, OnmsIpInterface ipInterface) {
        final WebTarget target = getTarget().path("nodes").path(nodeCriteria).path("ipinterfaces");
        // Serialize interface to XML and remove attributes which can't be set
        final String ipInterfaceXml = XmlUtils.filterAttributesFromXml(JaxbUtils.marshal(ipInterface),
                // We shouldn't be adding any extra attributes here, but rather work to remove these
                "isDown", "hasFlows", "monitoredServiceCount");
        return getBuilder(target).post(Entity.entity(ipInterfaceXml, MediaType.APPLICATION_XML));
    }

    public Response deleteInterface(String nodeCriteria, String ipAddress) {
        final WebTarget target = getTarget().path("nodes").path(nodeCriteria).path("ipinterfaces").path(ipAddress);
        return getBuilder(target).delete();
    }

    public Response addService(String nodeCriteria, String ipAddress, OnmsMonitoredService service) {
        final WebTarget target = getTarget().path("nodes").path(nodeCriteria).path("ipinterfaces").path(ipAddress)
                .path("services");
        // Serialize service to XML and remove attributes which can't be set
        final String serviceXml = XmlUtils.filterAttributesFromXml(JaxbUtils.marshal(service),
                // We shouldn't be adding any extra attributes here, but rather work to remove these
                "down", "statusLong");
        return getBuilder(target).post(Entity.entity(serviceXml, MediaType.APPLICATION_XML));
    }

    public OnmsMonitoredService getService(String nodeCriteria, String ipAddress, String service) {
        final WebTarget target = getTarget().path("nodes").path(nodeCriteria).path("ipinterfaces").path(ipAddress)
                .path("services").path(service);
        return getBuilder(target).get(OnmsMonitoredService.class);
    }

    public Response getResponseForService(String nodeCriteria, String ipAddress, String service) {
        final WebTarget target = getTarget().path("nodes").path(nodeCriteria).path("ipinterfaces").path(ipAddress)
                .path("services").path(service);
        return getBuilder(target).get();
    }

    public Response deleteNode(String nodeCriteria) {
        final WebTarget target = getTarget().path("nodes").path(nodeCriteria);
        return getBuilder(target).delete();
    }

    public Response deleteService(String nodeCriteria, String ipAddress, String service) {
        final WebTarget target = getTarget().path("nodes").path(nodeCriteria).path("ipinterfaces").path(ipAddress)
                .path("services").path(service);
        return getBuilder(target).delete();
    }

    public OnmsIpInterface getInterface(String nodeCriteria, String ipAddress) {
        final WebTarget target = getTarget().path("nodes").path(nodeCriteria).path("ipinterfaces").path(ipAddress);
        return getBuilder(target).get(OnmsIpInterface.class);
    }

    public Response getResponseForInterface(String nodeCriteria, String ipAddress) {
        final WebTarget target = getTarget().path("nodes").path(nodeCriteria).path("ipinterfaces").path(ipAddress);
        return getBuilder(target).get();
    }

    public OnmsEventCollection getEventsForNode(int nodeId) {
        final WebTarget target = getTarget().path("events").queryParam("node.id", nodeId);
        return getBuilder(target).accept(MediaType.APPLICATION_XML).get(OnmsEventCollection.class);
    }

    public OnmsEventCollection getEventsForNodeByEventUei(int nodeId, String eventUei) {
        final WebTarget target = getTarget().path("events").queryParam("node.id", nodeId).queryParam("eventUei", eventUei);
        return getBuilder(target).accept(MediaType.APPLICATION_XML).get(OnmsEventCollection.class);
    }

    public OnmsAlarmCollection getAlarmsByEventUei(String eventUei) {
        final WebTarget target = getTarget().path("alarms").queryParam("uei", eventUei);
        return getBuilder(target).accept(MediaType.APPLICATION_XML).get(OnmsAlarmCollection.class);
    }

    public OnmsAlarmCollection getAlarms() {
        final WebTarget target = getTarget().path("alarms").queryParam("limit", 0);
        return getBuilder(target).accept(MediaType.APPLICATION_XML).get(OnmsAlarmCollection.class);
    }

    public OnmsAlarmCollection getAlarmsForNode(int nodeId) {
        final WebTarget target = getTarget().path("alarms").queryParam("node.id", nodeId);
        return getBuilder(target).accept(MediaType.APPLICATION_XML).get(OnmsAlarmCollection.class);
    }

    public OnmsMinion getMinion(String id) {
        final WebTarget target = getTarget().path("minions").path(id);
        return getBuilder(target).accept(MediaType.APPLICATION_XML).get(OnmsMinion.class);
    }

    public List<OnmsMinion> getAllMinions() {
        GenericType<List<OnmsMinion>> minions = new GenericType<List<OnmsMinion>>() {
        };
        final WebTarget target = getTargetV2().path("minions");
        return getBuilder(target).accept(MediaType.APPLICATION_XML).get(minions);
    }

    public Response addMinion(OnmsMinion minion) {
        final WebTarget target = getTargetV2().path("minions");
        return getBuilder(target).post(Entity.entity(minion, MediaType.APPLICATION_XML));
    }

    public Response deleteMinion(String id) {
        final WebTarget target = getTargetV2().path("minions").path(id);
        return getBuilder(target).delete();
    }

    public void sendEvent(Event event) {
        sendEvent(event, true);
    }

    public void sendEvent(Event event, boolean clearDates) {
        if (clearDates) {
            // Clear dates to avoid problems with date marshaling/unmarshaling
            event.setCreationTime(null);
            event.setTime(null);
        }
        final WebTarget target = getTarget().path("events");
        final Response response = getBuilder(target).post(Entity.entity(event, MediaType.APPLICATION_XML));
        bailOnFailure(response);
    }

    public List<OnmsEvent> getEvents() {
        GenericType<List<OnmsEvent>> events = new GenericType<List<OnmsEvent>>() {
        };
        final WebTarget target = getTarget().path("events");
        return getBuilder(target).get(events);
    }

    public List<OnmsEvent> getAllEvents() {
        GenericType<List<OnmsEvent>> events = new GenericType<List<OnmsEvent>>() {
        };
        final WebTarget target = getTarget().path("events").queryParam("limit", 0);
        return getBuilder(target).get(events);
    }

    public Long getFlowCount(long start, long end) {
        final WebTarget target = getTarget().path("flows").path("count")
                .queryParam("start", start)
                .queryParam("end", end);
        return getBuilder(target).get(Long.class);
    }

    public ResourceDTO getResourcesForNode(String nodeCriteria) {
        final WebTarget target = getTarget().path("resources").path("fornode").path(nodeCriteria);
        return getBuilder(target).get(ResourceDTO.class);
    }

    public void resetGeocoderConfiguration() {
        final WebTarget target = getTargetV2().path("geocoding").path("config");
        final Response response = getBuilder(target).delete();
        if (!Response.Status.Family.SUCCESSFUL.equals(response.getStatusInfo().getFamily())) {
            throw new RuntimeException(String.format("Request failed with: %s:\n%s",
                    response.getStatusInfo().getReasonPhrase(), response.hasEntity() ? response.readEntity(String.class) : ""));
        }
    }

    public void addCategory(String categoryName) {
        final OnmsCategory category = new OnmsCategory(categoryName);
        final WebTarget target = getTarget().path("categories");
        final Response response = getBuilder(target).post(Entity.entity(category, MediaType.APPLICATION_XML));
        if (!Response.Status.Family.SUCCESSFUL.equals(response.getStatusInfo().getFamily())) {
            throw new RuntimeException(String.format("Request failed with: %s:\n%s",
                    response.getStatusInfo().getReasonPhrase(), response.hasEntity() ? response.readEntity(String.class) : ""));
        }
    }

    public void addCategoryToNode(String nodeCriteria, String categoryName) {
        final WebTarget target = getTarget().path("categories").path(categoryName).path("nodes").path(nodeCriteria);
        final Response response = getBuilder(target).put(null);
        if (!Response.Status.Family.SUCCESSFUL.equals(response.getStatusInfo().getFamily())) {
            throw new RuntimeException(String.format("Request failed with: %s:\n%s",
                    response.getStatusInfo().getReasonPhrase(), response.hasEntity() ? response.readEntity(String.class) : ""));
        }
    }

    private WebTarget getTarget() {
        final Client client = ClientBuilder.newClient();
        // Allow PUT with empty body
        client.property(ClientProperties.SUPPRESS_HTTP_COMPLIANCE_VALIDATION, true);
        return client.target(url.toString()).path("rest");
    }

    private WebTarget getTargetV2() {
        final Client client = ClientBuilder.newClient();
        return client.target(url.toString()).path("api").path("v2");
    }

    private Invocation.Builder getBuilder(final WebTarget target) {
        return target.request().header("Authorization", authorizationHeader);
    }

    public void sendGraphML(String graphName, InputStream graphMLStream) {
        Objects.requireNonNull(graphName);
        Objects.requireNonNull(graphMLStream);

        final WebTarget target = getTarget().path("graphml").path(graphName);
        final Response response = getBuilder(target).accept(MediaType.APPLICATION_XML).post(Entity.entity(graphMLStream, MediaType.APPLICATION_XML));
        bailOnFailure(response);
    }

    public Response getGraphML(String graphName) {
        Objects.requireNonNull(graphName);

        final WebTarget target = getTarget().path("graphml").path(graphName);
        final Response response = getBuilder(target).accept(MediaType.APPLICATION_XML).get();
        return response;
    }

    public void deleteGraphML(String graphName) {
        Objects.requireNonNull(graphName);
        final WebTarget target = getTarget().path("graphml").path(graphName);
        final Response response = getBuilder(target).delete();
        bailOnFailure(response);
    }

    private void bailOnFailure(Response response) {
        if (!Response.Status.Family.SUCCESSFUL.equals(response.getStatusInfo().getFamily())) {
            throw new RuntimeException(String.format("Request failed with: %s:\n%s",
                    response.getStatusInfo().getReasonPhrase(), response.hasEntity() ? response.readEntity(String.class) : ""));
        }
    }

    public List<OnmsApplication> getApplications() {
        final GenericType<List<OnmsApplication>> applications = new GenericType<List<OnmsApplication>>() {
        };
        final WebTarget target = getTargetV2().path("applications");
        return getBuilder(target).accept(MediaType.APPLICATION_XML).get(applications);
    }

    public void triggerBackup(final String requestDTO) {
        final WebTarget target = getTarget().path("device-config").path("backup");
        final var response = getBuilder(target)
                .post(Entity.entity(requestDTO, MediaType.APPLICATION_JSON));
        System.err.println(response);
        this.bailOnFailure(response);
    }

    public JsonNode getBackups() throws IOException {
        final var result = getBuilder(getTarget().path("device-config"))
                .accept(MediaType.APPLICATION_JSON)
                .get(String.class);

        if (Strings.isNullOrEmpty(result)) {
            return null;
        }

        return new ObjectMapper().readTree(result);
    }
}
