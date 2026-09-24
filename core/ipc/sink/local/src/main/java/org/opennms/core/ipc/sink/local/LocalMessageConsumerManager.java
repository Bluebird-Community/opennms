/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.core.ipc.sink.local;

import org.opennms.core.ipc.sink.api.Message;
import org.opennms.core.ipc.sink.api.SinkModule;
import org.opennms.core.ipc.sink.common.AbstractMessageConsumerManager;

/**
 * A broker-free, in-process {@link org.opennms.core.ipc.sink.api.MessageConsumerManager}.
 *
 * Delivery is handled entirely by {@link AbstractMessageConsumerManager#dispatch}, which
 * routes each message to the consumers registered for its module in the same JVM. Because
 * there is no external transport to attach to, {@link #startConsumingForModule} and
 * {@link #stopConsumingForModule} are no-ops.
 */
public class LocalMessageConsumerManager extends AbstractMessageConsumerManager {

    @Override
    protected void startConsumingForModule(final SinkModule<?, Message> module) {
        // no-op: messages are delivered in-process via dispatch(), there is no transport to start
    }

    @Override
    protected void stopConsumingForModule(final SinkModule<?, Message> module) {
        // no-op: see startConsumingForModule
    }
}
