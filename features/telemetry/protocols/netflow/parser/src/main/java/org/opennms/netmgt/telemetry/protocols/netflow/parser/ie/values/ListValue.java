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
package org.opennms.netmgt.telemetry.protocols.netflow.parser.ie.values;

import static org.opennms.netmgt.telemetry.listeners.utils.BufferUtils.slice;
import static org.opennms.netmgt.telemetry.listeners.utils.BufferUtils.uint16;
import static org.opennms.netmgt.telemetry.listeners.utils.BufferUtils.uint8;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.opennms.netmgt.telemetry.protocols.netflow.parser.InvalidPacketException;
import org.opennms.netmgt.telemetry.protocols.netflow.parser.MissingTemplateException;
import org.opennms.netmgt.telemetry.protocols.netflow.parser.ie.InformationElement;
import org.opennms.netmgt.telemetry.protocols.netflow.parser.ie.Semantics;
import org.opennms.netmgt.telemetry.protocols.netflow.parser.ie.Value;
import org.opennms.netmgt.telemetry.protocols.netflow.parser.ipfix.proto.DataRecord;
import org.opennms.netmgt.telemetry.protocols.netflow.parser.ipfix.proto.FieldSpecifier;
import org.opennms.netmgt.telemetry.protocols.netflow.parser.ipfix.proto.FlowSetHeader;
import org.opennms.netmgt.telemetry.protocols.netflow.parser.session.Field;
import org.opennms.netmgt.telemetry.protocols.netflow.parser.session.Session;
import org.opennms.netmgt.telemetry.protocols.netflow.parser.session.Template;

import io.netty.buffer.ByteBuf;

public class ListValue extends Value<List<List<Value<?>>>> {

    public enum Semantic {
        UNDEFINED,
        NONE_OF,
        EXACTLY_ONE_OF,
        ONE_OR_MORE_OF,
        ALL_OF,
        ORDERED;

        public static Semantic parse(final ByteBuf buffer, final int i) throws InvalidPacketException {
            switch (i) {
                case 0xFF:
                    return UNDEFINED;
                case 0x00:
                    return NONE_OF;
                case 0x01:
                    return EXACTLY_ONE_OF;
                case 0x02:
                    return ONE_OR_MORE_OF;
                case 0x03:
                    return ALL_OF;
                case 0x04:
                    return ORDERED;
                default:
                    throw new InvalidPacketException(buffer, "Illegal semantic value: 0x%02x", i);
            }
        }
    }

    private final Semantic semantic;

    private final List<List<Value<?>>> values;

    public ListValue(final String name,
                     final Optional<Semantics> semantics,
                     final Semantic semantic,
                     final List<List<Value<?>>> values) {
        super(name, semantics);
        this.semantic = Objects.requireNonNull(semantic);
        this.values = Objects.requireNonNull(values);
    }

    /*
      0                   1                   2                   3
      0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |   Semantic    |0|          Field ID           |   Element...  |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     | ...Length     |           basicList Content ...               |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |                              ...                              |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |                              ...                              |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    */
    public static InformationElement parserWithBasicList(final String name, final Optional<Semantics> semantics) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) throws InvalidPacketException, MissingTemplateException {
                final Semantic semantic = Semantic.parse(buffer, uint8(buffer));
                final FieldSpecifier field = new FieldSpecifier(buffer);

                final List<List<Value<?>>> values = new LinkedList<>();
                while (buffer.isReadable()) {
                    values.add(Collections.singletonList(DataRecord.parseField(field, resolver, buffer)));
                }

                return new ListValue(name, semantics, semantic, values);
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getMinimumFieldLength() {
                return 1 + 4;
            }

            @Override
            public int getMaximumFieldLength() {
                return 0xFFFF;
            }
        };
    }

    /*
      0                   1                   2                   3
      0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |   Semantic    |         Template ID           |     ...       |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |                subTemplateList Content    ...                 |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |                              ...                              |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    */
    public static InformationElement parserWithSubTemplateList(final String name, final Optional<Semantics> semantics) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) throws InvalidPacketException, MissingTemplateException {
                final Semantic semantic = Semantic.parse(buffer, uint8(buffer));
                final int templateId = uint16(buffer);

                final Template template = resolver.lookupTemplate(templateId);

                final List<List<Value<?>>> values = new LinkedList<>();
                while (buffer.isReadable()) {
                    final List<Value<?>> record = new ArrayList(template.count());
                    for (final Field field : template) {
                        record.add(DataRecord.parseField(field, resolver, buffer));
                    }
                    values.add(record);
                }

                return new ListValue(name, semantics, semantic, values);
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getMinimumFieldLength() {
                return 3;
            }

            @Override
            public int getMaximumFieldLength() {
                return 0xFFFF;
            }
        };
    }

    /*
      0                   1                   2                   3
      0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |    Semantic   |         Template ID X         |Data Records...|
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     | ... Length X  |        Data Record X.1 Content ...            |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |                              ...                              |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |      ...      |        Data Record X.2 Content ...            |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |                              ...                              |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |      ...      |        Data Record X.L Content ...            |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |                              ...                              |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |      ...      |         Template ID Y         |Data Records...|
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     | ... Length Y  |        Data Record  Y.1 Content ...           |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |                              ...                              |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |      ...      |        Data Record Y.2 Content ...            |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |                              ...                              |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |      ...      |        Data Record Y.M Content ...            |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |                              ...                              |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |      ...      |         Template ID Z         |Data Records...|
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     | ... Length Z  |        Data Record Z.1 Content ...            |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |                              ...                              |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |      ...      |        Data Record Z.2 Content ...            |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |                              ...                              |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |      ...      |        Data Record Z.N Content ...            |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |                              ...                              |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |      ...      |
     +-+-+-+-+-+-+-+-+
    */
    public static InformationElement parserWithSubTemplateMultiList(final String name, final Optional<Semantics> semantics) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) throws InvalidPacketException, MissingTemplateException {
                final Semantic semantic = Semantic.parse(buffer, uint8(buffer));

                final List<List<Value<?>>> values = new LinkedList<>();
                while (buffer.isReadable()) {
                    final FlowSetHeader header = new FlowSetHeader(buffer);
                    if (header.setId <= 255) {
                        throw new InvalidPacketException(buffer, "Invalid template ID: %d", header.setId);
                    }

                    final ByteBuf payloadBuffer = slice(buffer, header.length - FlowSetHeader.SIZE);
                    final Template template = resolver.lookupTemplate(header.setId);

                    while (payloadBuffer.isReadable()) {
                        final List<Value<?>> record = new ArrayList(template.count());
                        for (final Field field : template) {
                            record.add(DataRecord.parseField(field, resolver, payloadBuffer));
                        }
                        values.add(record);
                    }
                }

                return new ListValue(name, semantics, semantic, values);
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getMinimumFieldLength() {
                return 0;
            }

            @Override
            public int getMaximumFieldLength() {
                return 0xFFFF;
            }
        };
    }

    @Override
    public List<List<Value<?>>> getValue() {
        return this.values;
    }

    public Semantic getSemantic() {
        return this.semantic;
    }

    @Override
    public void visit(final Visitor visitor) {
        visitor.accept(this);
    }
}
