/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.core.rpc.local;

import static org.opennms.core.rpc.common.RpcStrategy.Strategy.LOCAL;

import org.opennms.core.rpc.api.RpcClientFactory;
import org.opennms.core.rpc.common.RpcStrategy;
import org.opennms.core.logging.Logging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.context.annotation.ImportResource;
import org.springframework.core.type.AnnotatedTypeMetadata;

@Configuration
@Conditional(ConditionalLocalRpcContext.Condition.class)
@ImportResource("/META-INF/opennms/applicationContext-ipc-local-rpc.xml")
public class ConditionalLocalRpcContext {

    private static final Logger LOG = LoggerFactory.getLogger(ConditionalLocalRpcContext.class);

    static class Condition implements ConfigurationCondition {
        @Override
        public ConfigurationPhase getConfigurationPhase() {
            return ConfigurationPhase.PARSE_CONFIGURATION;
        }

        @Override
        public boolean matches(final ConditionContext context, final AnnotatedTypeMetadata metadata) {
            final boolean enabled = LOCAL.equals(RpcStrategy.getRpcStrategy());
            try (Logging.MDCCloseable mdc = Logging.withPrefixCloseable(RpcClientFactory.LOG_PREFIX)) {
                LOG.debug("Enable local (in-process) RPC strategy {}", enabled);
            }
            return enabled;
        }
    }
}
