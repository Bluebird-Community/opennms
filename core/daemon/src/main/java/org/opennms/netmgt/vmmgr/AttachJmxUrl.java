/*
 * Copyright 2026 The OpenNMS Group, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.netmgt.vmmgr;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

/**
 * Finds the local JMX connector URL of a running OpenNMS JVM with the Attach API.
 *
 * <p>This is the only class in this package that references {@code com.sun.tools.attach}.
 * A JRE without the {@code jdk.attach} module cannot load it, so {@link Controller}
 * must check that the Attach API is present before calling it. Keeping the
 * references here lets {@link Controller} itself load on such a JRE.</p>
 */
final class AttachJmxUrl {
    private static final Logger LOG = LoggerFactory.getLogger(AttachJmxUrl.class);

    private AttachJmxUrl() {}

    /**
     * @return the local JMX connector URL, or null if no OpenNMS JVM could be attached to
     */
    static String find(final String displayNameSubstring, final String pid) {
        VirtualMachine vm = null;

        final StringBuilder vmNames = new StringBuilder();
        boolean first = true;

        // Use the Attach API to enumerate all of the JVMs that are running as the same
        // user on this machine
        VirtualMachineDescriptor foundVm = null;
        for (VirtualMachineDescriptor vmDescr : VirtualMachine.list()) {
            if (!first) {
                vmNames.append(", ");
            }
            vmNames.append("\"" + vmDescr.displayName() + "\"");
            first = false;

            if (vmDescr.displayName().contains(displayNameSubstring)) {
                foundVm = vmDescr;
            }
        }

        if (foundVm == null) {
            LOG.debug("Could not find OpenNMS JVM (\"" + displayNameSubstring + "\") among JVMs (" + vmNames + ")");
        } else {
            try {
                vm = VirtualMachine.attach(foundVm);
                LOG.debug("Attached to OpenNMS JVM: " + foundVm.id() + " (" + foundVm.displayName() + ")");
            } catch (AttachNotSupportedException e) {
                // This exception is unexpected so log a warning
                LOG.warn("Cannot attach to OpenNMS JVM", e);
            } catch (IOException e) {
                // This exception is unexpected so log a warning
                LOG.warn("IOException when attaching to OpenNMS JVM", e);
            }
        }

        if (vm == null) {
            if (pid == null) {
                LOG.debug("No PID specified for OpenNMS JVM");
            } else {
                try {
                    vm = VirtualMachine.attach(pid);
                    LOG.debug("Attached to OpenNMS JVM with PID: " + pid);
                } catch (AttachNotSupportedException e) {
                    // This exception is unexpected so log a warning
                    LOG.warn("Cannot attach to OpenNMS JVM at PID: " + pid, e);
                } catch (IOException e) {
                    // This exception will occur if the PID cannot be found
                    // because the process has been terminated
                    LOG.debug("IOException when attaching to OpenNMS JVM at PID: " + pid + ": " + e.getMessage());
                }
            }
        }

        return vm == null ? null : getJmxUriFromVirtualMachine(vm);
    }

    private static String getJmxUriFromVirtualMachine(VirtualMachine vm) {
        String connectorAddress = null;
        try {
            // Get the local JMX connector URI
            connectorAddress = vm.getAgentProperties().getProperty(Controller.CONNECTOR_ADDRESS);
        } catch (IOException e) {
            throw new IllegalStateException("IOException when fetching JMX URI from JVM with ID: " + vm.id(), e);
        }

        // If there is no local JMX connector URI, we need to launch the
        // JMX agent via this VirtualMachine attachment.
        if (connectorAddress == null) {
            LOG.info("Starting local management agent in JVM with ID: " + vm.id());

            try {
                vm.startLocalManagementAgent();
            } catch (IOException e) {
                throw new IllegalStateException("IOException when starting local JMX management agent in JVM with ID: " + vm.id(), e);
            }

            // Agent is started, get the connector address
            try {
                connectorAddress = vm.getAgentProperties().getProperty(Controller.CONNECTOR_ADDRESS);
            } catch (IOException e) {
                throw new IllegalStateException("IOException when fetching JMX URI from JVM with ID: " + vm.id(), e);
            }
        }

        return connectorAddress;
    }
}
