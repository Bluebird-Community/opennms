/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.core.rpc.local;

import java.util.concurrent.CompletableFuture;

import org.opennms.core.rpc.api.RpcClient;
import org.opennms.core.rpc.api.RpcClientFactory;
import org.opennms.core.rpc.api.RpcModule;
import org.opennms.core.rpc.api.RpcRequest;
import org.opennms.core.rpc.api.RpcResponse;

/**
 * A broker-free, in-process {@link RpcClientFactory}.
 *
 * Every request is executed directly against the {@link RpcModule} in the same JVM. This is
 * the single-node (Minion-less) counterpart to the transport factories, which short-circuit
 * local-location requests to {@code module.execute(request)} and only use the wire for a
 * remote (Minion) location. With no Minion present there is no remote location, so this
 * factory simply always executes locally.
 */
public class LocalRpcClientFactory implements RpcClientFactory {

    @Override
    public <R extends RpcRequest, S extends RpcResponse> RpcClient<R, S> getClient(final RpcModule<R, S> module) {
        return new RpcClient<R, S>() {
            @Override
            public CompletableFuture<S> execute(final R request) {
                return module.execute(request);
            }
        };
    }
}
