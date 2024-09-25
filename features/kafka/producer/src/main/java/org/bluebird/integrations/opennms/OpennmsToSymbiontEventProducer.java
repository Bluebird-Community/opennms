package org.bluebird.integrations.opennms;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.PostMethod;
import org.opennms.netmgt.dao.api.DistPollerDao;
import org.opennms.netmgt.events.api.EventListener;
import org.opennms.netmgt.events.api.EventSubscriptionService;
import org.opennms.netmgt.events.api.ThreadAwareEventListener;
import org.opennms.netmgt.events.api.model.IEvent;
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

    private final DistPollerDao distPollerDao;
    private final EventSubscriptionService eventSubscriptionService;
    private final String symbiontEndpoint;

    private int numEventListenerThreads = 4;

    public OpennmsToSymbiontEventProducer(final DistPollerDao distPollerDao,
                                          final EventSubscriptionService eventSubscriptionService,
                                          final String symbiontEndpoint) {
        this.distPollerDao = Objects.requireNonNull(distPollerDao);
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
        // TODO MVR this is ugly as hell
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
        final var dto = new HashMap<String, Object>();
        dto.put("namespace", event.getUei()); // TODO MVR this is not 100% accurate
        dto.put("source", String.format("%s/%s/%s", distPollerDao.whoami().getType(), distPollerDao.whoami().getId(), distPollerDao.whoami().getLocation()));
        dto.put("ref", event.getDbid());
        // TODO MVR not serializable at the moment :'(
//        dto.put("creationTime", LocalDateTime.now()); // TODO MVR use this instead event.getCreationTime().getTime());

        final var convertedEvent = copyFrom(event); // TODO MVR verify if we still need this, maybe we don't need to invoke copyFrom and can just use IEvent instead
        final var payload = new ObjectMapper().convertValue(convertedEvent, new TypeReference<Map<String, Object>>() {});

        // Manually convert Parameters, as they are not really usable by default
        payload.remove("parmCollection");
        final var parameters = new HashMap<>();
        parameters.put("values", convertedEvent.getParmCollection().stream().collect(Collectors.toMap(Parm::getParmName, it -> it.getValue().getContent())));
        parameters.put("types", convertedEvent.getParmCollection().stream().collect(Collectors.toMap(Parm::getParmName, it -> it.getValue().getType())));
        parameters.put("encoding", convertedEvent.getParmCollection().stream().collect(Collectors.toMap(Parm::getParmName, it -> it.getValue().getEncoding())));
        payload.put("parameters", parameters);
        try {
            LOG.info("{}", new ObjectMapper().writeValueAsString(payload));
        } catch (Exception ex) {
            // silently swallow
        }

        dto.put("payload", payload);

        return dto;
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
