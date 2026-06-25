/*
 * Copyright 2026 The OpenNMS Group, Inc.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */

package org.opennms.features.apilayer.topology;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.opennms.integration.api.v1.topology.UserDefinedLink;
import org.opennms.integration.api.v1.topology.UserDefinedLinkDao;
import org.opennms.integration.api.v1.topology.immutables.ImmutableUserDefinedLink;

/**
 * In-memory {@link UserDefinedLinkDao} that preserves the OpenNMS Integration API
 * topology contract for plugins that wire this service as a mandatory dependency.
 *
 * User-defined links used to be persisted by the Enhanced Linkd (enlinkd) subsystem,
 * which has been removed from BluebirdOps. There is no longer a database-backed store,
 * so links are held only in memory and do not survive a restart. This is sufficient to
 * keep OIA plugins (which expect a functional {@code UserDefinedLinkDao}) operating.
 */
public class UserDefinedLinkDaoImpl implements UserDefinedLinkDao {

    private final Map<Integer, UserDefinedLink> linksById = new ConcurrentHashMap<>();
    private final AtomicInteger dbIdSequence = new AtomicInteger();

    @Override
    public List<UserDefinedLink> getLinks() {
        return new ArrayList<>(linksById.values());
    }

    @Override
    public List<UserDefinedLink> getOutLinks(int nodeIdA) {
        return linksById.values().stream()
                .filter(link -> link.getNodeIdA() == nodeIdA)
                .collect(Collectors.toList());
    }

    @Override
    public List<UserDefinedLink> getInLinks(int nodeIdZ) {
        return linksById.values().stream()
                .filter(link -> link.getNodeIdZ() == nodeIdZ)
                .collect(Collectors.toList());
    }

    @Override
    public List<UserDefinedLink> getLinksWithLabel(String label) {
        return linksById.values().stream()
                .filter(link -> Objects.equals(link.getLinkLabel(), label))
                .collect(Collectors.toList());
    }

    @Override
    public UserDefinedLink saveOrUpdate(UserDefinedLink link) {
        UserDefinedLink toStore = link;
        if (toStore.getDbId() == null) {
            toStore = ImmutableUserDefinedLink.newBuilderFrom(toStore)
                    .setDbId(dbIdSequence.incrementAndGet())
                    .build();
        }
        linksById.put(toStore.getDbId(), toStore);
        return toStore;
    }

    @Override
    public void delete(UserDefinedLink link) {
        if (link.getDbId() != null) {
            linksById.remove(link.getDbId());
        } else {
            linksById.values().removeIf(stored -> Objects.equals(stored.getLinkId(), link.getLinkId()));
        }
    }

    @Override
    public void delete(Collection<UserDefinedLink> links) {
        links.forEach(this::delete);
    }
}
