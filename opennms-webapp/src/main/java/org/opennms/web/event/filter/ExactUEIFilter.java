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
package org.opennms.web.event.filter;

import org.opennms.web.filter.EqualsFilter;
import org.opennms.web.filter.SQLType;

/**
 * Encapsulates filtering on exact unique event identifiers.
 *
 * @author ranger
 * @version $Id: $
 * @since 1.8.1
 */
public class ExactUEIFilter extends EqualsFilter<String> {
    /** Constant <code>TYPE="exactUei"</code> */
    public static final String TYPE = "exactUei";

    /**
     * <p>Constructor for ExactUEIFilter.</p>
     *
     * @param uei a {@link java.lang.String} object.
     */
    public ExactUEIFilter(String uei) {
        super(TYPE, SQLType.STRING, "EVENTUEI", "eventUei", uei);
    }

    /**
     * <p>toString</p>
     *
     * @return a {@link java.lang.String} object.
     */
    @Override
    public String toString() {
        return ("<WebEventRepository.ExactUEIFilter: " + this.getDescription() + ">");
    }

    /**
     * <p>getUEI</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getUEI() {
        return getValue();
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (!(obj instanceof ExactUEIFilter)) return false;
        return (this.toString().equals(obj.toString()));
    }
}
