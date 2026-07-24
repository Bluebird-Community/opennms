/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.netmgt.syslogd;

import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Finds an available TCP port for the Syslogd tests.
 *
 * <p>Used as a Spring {@code factory-method} from the test contexts to pick a free eventd/syslog
 * port at context-load time. This replaces the identical helper that used to live on the removed
 * {@code org.opennms.core.test.camel.CamelBlueprintTest}.
 */
public final class AvailablePortFinder {

    private AvailablePortFinder() {
    }

    public static int getAvailablePort(final AtomicInteger current, final int max) {
        while (current.get() < max) {
            try (final ServerSocket socket = new ServerSocket(current.get())) {
                return socket.getLocalPort();
            } catch (final Throwable e) {
                // port in use, try the next one
            }
            current.incrementAndGet();
        }
        throw new IllegalStateException("Can't find an available network port");
    }
}
