package org.bluebird.integrations.opennms;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.PostMethod;
import org.opennms.netmgt.dao.api.DistPollerDao;
import org.opennms.netmgt.eventd.EventUtil;
import org.opennms.netmgt.events.api.EventListener;
import org.opennms.netmgt.events.api.EventSubscriptionService;
import org.opennms.netmgt.events.api.ThreadAwareEventListener;
import org.opennms.netmgt.events.api.model.IEvent;
import org.opennms.netmgt.xml.event.Event;
import org.opennms.netmgt.xml.event.Parm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.opennms.netmgt.xml.event.Event.copyFrom;

public class OpennmsToSymbiontEventProducer implements EventListener, ThreadAwareEventListener {
    private static final Logger LOG = LoggerFactory.getLogger(OpennmsToSymbiontEventProducer.class);

    private final EventUtil eventUtil;
    private final DistPollerDao distPollerDao;
    private final EventSubscriptionService eventSubscriptionService;
    private final String symbiontEndpoint;

    private int numEventListenerThreads = 4;

    public OpennmsToSymbiontEventProducer(final DistPollerDao distPollerDao,
                                          final EventUtil eventUtil,
                                          final EventSubscriptionService eventSubscriptionService,
                                          final String symbiontEndpoint) {
        this.distPollerDao = Objects.requireNonNull(distPollerDao);
        this.eventUtil = Objects.requireNonNull(eventUtil);
        this.eventSubscriptionService = Objects.requireNonNull(eventSubscriptionService);
        this.symbiontEndpoint = Objects.requireNonNull(symbiontEndpoint);
        validateEndpointUrl(symbiontEndpoint);
    }

    private static void validateEndpointUrl(final String symbiontEndpoint) {
        if (symbiontEndpoint.trim().isEmpty()) {
            throw new IllegalArgumentException("Symbiont endpoint cannot be empty");
        }
        // Try parsing the URL
        try {
            new URL(symbiontEndpoint);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(String.format("The provided url for the symbiont endpoint '%s' is not valid", symbiontEndpoint), e);
        }
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public void onEvent(IEvent event) {
        final var convertedEvent = convert(event);
        // TODO MVR this is ugly as hell.
        // Also use newer versions of http client, not this very old version
        final var client = new HttpClient();
        final var post = new PostMethod(symbiontEndpoint);
        try {
            final var json = new ObjectMapper().writeValueAsString(convertedEvent);
            post.setRequestHeader("Content-Type", "application/json");
            post.setRequestBody(json);
            final var statusCode = client.executeMethod(post);
            if (statusCode != HttpStatus.SC_OK) {
                LOG.warn("Communication with endpoint {} failed with status {}", symbiontEndpoint, statusCode);
            }
        } catch (HttpException e) {
            LOG.error("Fatal protocol violation: {}", e.getMessage(), e);
        } catch (IOException e) {
            LOG.error("Fatal transport error: {}", e.getMessage(), e);
        } finally {
            post.releaseConnection();
        }
    }

    public void init() {
        eventSubscriptionService.addEventListener(this);
    }

    public void destroy() {
        eventSubscriptionService.removeEventListener(this);
    }

    private Map<String, Object> convert(IEvent event) {
        final var convertedEvent = copyFrom(event); // TODO MVR verify if we still need this, maybe we don't need to invoke copyFrom and can just use IEvent instead

        final var dto = new HashMap<String, Object>();
        final var namespace = parseNamespace(event.getUei());
        dto.put("namespace", namespace); // TODO MVR this is not 100% accurate
        dto.put("source", String.format("%s/%s/%s", distPollerDao.whoami().getType(), distPollerDao.whoami().getId(), distPollerDao.whoami().getLocation()));
        dto.put("ref", event.getDbid() == 0 ? null : event.getDbid());
        // TODO MVR not serializable at the moment :'(
//        dto.put("creationTime", LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)); // TODO MVR use this instead event.getCreationTime().getTime());
        dto.put("consolidationKey", determineConsolidationKey(convertedEvent));

        final var payload = new ObjectMapper().convertValue(convertedEvent, new TypeReference<Map<String, Object>>() {});

        // Manually convert Parameters, as they are not really usable by default
        payload.remove("parmCollection");
        final var parameters = new HashMap<>();
        parameters.put("values", convertedEvent.getParmCollection().stream().collect(Collectors.toMap(Parm::getParmName, it -> it.getValue().getContent())));
        parameters.put("types", convertedEvent.getParmCollection().stream().collect(Collectors.toMap(Parm::getParmName, it -> it.getValue().getType())));
        parameters.put("encoding", convertedEvent.getParmCollection().stream().collect(Collectors.toMap(Parm::getParmName, it -> it.getValue().getEncoding())));
        payload.put("parameters", parameters);
        dto.put("payload", payload);

        return dto;
    }

    private String determineConsolidationKey(Event event) {
        // We must set a consolidation key if alarm data is defined
        // If alarm data is defined, the event is either a raising or clearing event.
        // In case of clear-key, we assume clearing event, even if reduction-key (raise key) is also defined
        if (event.getAlarmData() != null) {
            if (!Strings.isNullOrEmpty(event.getAlarmData().getClearKey())) {
                return eventUtil.expandParms(event.getAlarmData().getClearKey(), event);
            } else if (!Strings.isNullOrEmpty(event.getAlarmData().getReductionKey())) {
                return eventUtil.expandParms(event.getAlarmData().getReductionKey(), event);
            }
        }
        return null;
    }

    private Object parseNamespace(String uei) {
        if (uei.contains("/")) {
            // In case there is a / everything before the last path element is returned
            // This way we remove the concrete type of event, but put it more in a namespace
            // Hopefully with this all existing events can be migrated properly
            // TODO MVR verify that this is actually working properly
            return uei.substring(0, uei.lastIndexOf("/"));
        }
        return uei; // we return uei if there is nothing to split by
    }

    @Override
    public int getNumThreads() {
        return numEventListenerThreads;
    }

    public int getNumEventListenerThreads() {
        return numEventListenerThreads;
    }

    public void setNumEventListenerThreads(int numEventListenerThreads) {
        this.numEventListenerThreads = numEventListenerThreads;
    }
}
