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
package org.opennms.protocols.http;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Thrown by {@link HttpUrlConnection} when a downstream HTTP call returns
 * 401 Unauthorized or 403 Forbidden. Subclasses {@link IOException} so
 * callers that don't care about auth-specific handling can treat it like
 * any other I/O failure; callers that do (the XML collector's
 * dynamic-auth retry path) catch this type specifically and attempt to
 * invalidate-and-refresh the offending token.
 *
 * <p>The {@code attemptedHeaderValues} list, when populated, carries the
 * literal header values that were on the failed request. The controller-
 * side retry path uses these to invalidate matching cache entries when a
 * minion-served collection surfaces an auth failure across the RPC
 * boundary; the in-process retry path on the controller picks the same
 * values out of the live request directly.</p>
 */
public class TokenAuthFailureException extends IOException {
    private static final long serialVersionUID = 2L;

    private final int statusCode;
    private final List<String> attemptedHeaderValues;

    public TokenAuthFailureException(final String message, final int statusCode) {
        this(message, statusCode, Collections.emptyList());
    }

    public TokenAuthFailureException(final String message, final int statusCode, final List<String> attemptedHeaderValues) {
        super(message);
        this.statusCode = statusCode;
        this.attemptedHeaderValues = attemptedHeaderValues == null
                ? Collections.emptyList()
                : List.copyOf(attemptedHeaderValues);
    }

    public int getStatusCode() {
        return statusCode;
    }

    public List<String> getAttemptedHeaderValues() {
        return attemptedHeaderValues;
    }
}
