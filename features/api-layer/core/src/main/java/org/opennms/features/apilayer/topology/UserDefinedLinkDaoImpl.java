/*
 * Copyright 2026 The OpenNMS Group, Inc.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */

package org.opennms.features.apilayer.topology;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.opennms.integration.api.v1.topology.UserDefinedLink;
import org.opennms.integration.api.v1.topology.UserDefinedLinkDao;

/**
 * No-op {@link UserDefinedLinkDao} that preserves the OpenNMS Integration API
 * topology contract for plugins that wire this service as a mandatory dependency.
 *
 * User-defined links were persisted by the Enhanced Linkd (enlinkd) subsystem,
 * which has been removed from BluebirdOps. There is no longer any backing store,
 * so reads return nothing and mutations are unsupported. The service is still
 * published so that OIA plugins referencing {@code UserDefinedLinkDao} can start.
 */
public class UserDefinedLinkDaoImpl implements UserDefinedLinkDao {

    @Override
    public List<UserDefinedLink> getLinks() {
        return Collections.emptyList();
    }

    @Override
    public List<UserDefinedLink> getOutLinks(int nodeIdA) {
        return Collections.emptyList();
    }

    @Override
    public List<UserDefinedLink> getInLinks(int nodeIdZ) {
        return Collections.emptyList();
    }

    @Override
    public List<UserDefinedLink> getLinksWithLabel(String label) {
        return Collections.emptyList();
    }

    @Override
    public UserDefinedLink saveOrUpdate(UserDefinedLink link) {
        throw new UnsupportedOperationException("User-defined links are no longer persisted: the enlinkd subsystem has been removed.");
    }

    @Override
    public void delete(UserDefinedLink link) {
        throw new UnsupportedOperationException("User-defined links are no longer persisted: the enlinkd subsystem has been removed.");
    }

    @Override
    public void delete(Collection<UserDefinedLink> links) {
        throw new UnsupportedOperationException("User-defined links are no longer persisted: the enlinkd subsystem has been removed.");
    }
}
