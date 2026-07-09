/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Copyright 2026 The OpenNMS Group, Inc.
 *
 * Created by Ronny Trommer <ronny@opennms.com>
 */
package org.opennms.netmgt.flows.clickhouse;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ClickhouseFlowQueryServiceTest {

    /** A single quote must be doubled and a backslash escaped, so a value cannot break out of the literal. */
    @Test
    public void quoteEscapesQuotesAndBackslashes() {
        assertEquals("'http'", ClickhouseFlowQueryService.quote("http"));
        assertEquals("'O''Brien'", ClickhouseFlowQueryService.quote("O'Brien"));
        // trailing backslash must not escape the closing quote
        assertEquals("'foo\\\\'", ClickhouseFlowQueryService.quote("foo\\"));
        assertEquals("'a\\\\''b'", ClickhouseFlowQueryService.quote("a\\'b"));
    }
}
