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
package org.opennms.netmgt.config.collectortokenauth;

import java.util.Objects;

import org.apache.commons.lang.StringUtils;
import org.opennms.netmgt.config.CollectorTokenAuthConfigFactory;
import org.opennms.netmgt.events.api.EventConstants;
import org.opennms.netmgt.events.api.EventIpcManager;
import org.opennms.netmgt.events.api.EventListener;
import org.opennms.netmgt.events.api.model.IEvent;
import org.opennms.netmgt.events.api.model.IParm;
import org.opennms.netmgt.model.events.EventBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * Reacts to a {@code reloadDaemonConfig} event with
 * {@code daemonName=CollectorTokenAuth} by re-reading
 * {@code etc/collector-token-auth-configuration.xml}. Cached tokens are
 * flushed as part of the reload so the next request through a collector
 * drives a fresh acquisition against the (possibly updated) token-auth
 * definition.
 *
 * <p>To trigger:
 * <pre>
 *   uei.opennms.org/internal/reloadDaemonConfig
 *   parm: daemonName=CollectorTokenAuth
 * </pre>
 *
 * <p>{@link EventIpcManager} is constructor-injected from the parent
 * context (daemonContext); {@link #register()} runs as the Spring
 * init-method and adds this listener to the manager. No
 * {@code ContextRefreshedEvent} dance is needed.</p>
 */
public class CollectorTokenAuthConfigReloadListener
        implements EventListener, DisposableBean {

    /** Name advertised for daemon-reload routing. */
    public static final String NAME = "CollectorTokenAuth";

    private static final Logger LOG = LoggerFactory.getLogger(CollectorTokenAuthConfigReloadListener.class);

    private final TokenCache tokenCache;
    private final EventIpcManager eventIpcManager;
    private boolean registered;

    public CollectorTokenAuthConfigReloadListener(final TokenCache tokenCache,
                                                  final EventIpcManager eventIpcManager) {
        this.tokenCache = Objects.requireNonNull(tokenCache, "tokenCache");
        this.eventIpcManager = Objects.requireNonNull(eventIpcManager, "eventIpcManager");
    }

    /** Spring init-method. */
    public void register() {
        if (registered) {
            return;
        }
        eventIpcManager.addEventListener(this, EventConstants.RELOAD_DAEMON_CONFIG_UEI);
        registered = true;
        LOG.info("Registered as listener for {} (daemonName={})",
                EventConstants.RELOAD_DAEMON_CONFIG_UEI, NAME);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void onEvent(final IEvent e) {
        if (e == null || !EventConstants.RELOAD_DAEMON_CONFIG_UEI.equals(e.getUei())) {
            return;
        }
        final IParm daemonNameParm = e.getParm(EventConstants.PARM_DAEMON_NAME);
        if (daemonNameParm == null || daemonNameParm.getValue() == null) {
            return;
        }
        if (!NAME.equalsIgnoreCase(daemonNameParm.getValue().getContent())) {
            return;
        }
        LOG.info("Reloading collector-token-auth-configuration.xml");
        try {
            CollectorTokenAuthConfigFactory.reload();
            tokenCache.invalidateAll();
            LOG.info("collector-token-auth-configuration.xml reload successful; cache flushed");
            sendEventQuietly(new EventBuilder(EventConstants.RELOAD_DAEMON_CONFIG_SUCCESSFUL_UEI, NAME)
                    .addParam(EventConstants.PARM_DAEMON_NAME, NAME)
                    .getEvent());
        } catch (final Exception t) {
            LOG.error("collector-token-auth-configuration.xml reload failed", t);
            sendEventQuietly(new EventBuilder(EventConstants.RELOAD_DAEMON_CONFIG_FAILED_UEI, NAME)
                    .addParam(EventConstants.PARM_DAEMON_NAME, NAME)
                    .addParam(EventConstants.PARM_REASON, StringUtils.abbreviate(t.getLocalizedMessage(), 128))
                    .getEvent());
        }
    }

    /**
     * Spring {@link DisposableBean} hook. On context shutdown,
     * unregister from {@link EventIpcManager} so the manager does not
     * retain a reference to a dead listener.
     */
    @Override
    public void destroy() {
        if (registered) {
            try {
                eventIpcManager.removeEventListener(this);
            } catch (final Exception t) {
                LOG.warn("Failed to remove collector-token-auth reload listener on shutdown", t);
            }
            registered = false;
        }
    }

    private void sendEventQuietly(final org.opennms.netmgt.xml.event.Event event) {
        try {
            eventIpcManager.sendNow(event);
        } catch (final Exception t) {
            LOG.warn("Failed to publish {}", event.getUei(), t);
        }
    }
}
