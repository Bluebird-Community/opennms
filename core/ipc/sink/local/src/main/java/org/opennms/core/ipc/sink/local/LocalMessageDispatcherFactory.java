/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.core.ipc.sink.local;

import static org.opennms.core.ipc.sink.api.Message.SINK_METRIC_PRODUCER_DOMAIN;

import java.util.Objects;

import org.opennms.core.ipc.sink.api.Message;
import org.opennms.core.ipc.sink.api.SinkModule;
import org.opennms.core.ipc.sink.common.AbstractMessageDispatcherFactory;
import org.osgi.framework.BundleContext;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import com.codahale.metrics.MetricRegistry;

import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;

/**
 * A broker-free, in-process {@link org.opennms.core.ipc.sink.api.MessageDispatcherFactory}.
 *
 * Producers dispatch straight into the same-JVM {@link LocalMessageConsumerManager}, which
 * fans the message out to the consumers registered for the module. The async queue, thread
 * pool, metrics and tracing all come from {@link AbstractMessageDispatcherFactory}.
 */
public class LocalMessageDispatcherFactory extends AbstractMessageDispatcherFactory<Void> implements InitializingBean, DisposableBean {

    private final LocalMessageConsumerManager consumerManager;

    private MetricRegistry metrics;

    public LocalMessageDispatcherFactory(final LocalMessageConsumerManager consumerManager) {
        this.consumerManager = Objects.requireNonNull(consumerManager);
    }

    @Override
    public <S extends Message, T extends Message> void dispatch(final SinkModule<S, T> module, final Void metadata, final T message) {
        consumerManager.dispatch(module, message);
    }

    @Override
    public String getMetricDomain() {
        return SINK_METRIC_PRODUCER_DOMAIN;
    }

    @Override
    public BundleContext getBundleContext() {
        return null;
    }

    @Override
    public Tracer getTracer() {
        return GlobalTracer.get();
    }

    @Override
    public MetricRegistry getMetrics() {
        if (metrics == null) {
            metrics = new MetricRegistry();
        }
        return metrics;
    }

    public void setMetrics(final MetricRegistry metrics) {
        this.metrics = metrics;
    }

    @Override
    public void afterPropertiesSet() {
        onInit();
    }

    @Override
    public void destroy() {
        onDestroy();
    }
}
