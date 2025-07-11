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
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: netflow.proto

// Protobuf Java Version: 3.25.5
package org.opennms.netmgt.telemetry.protocols.netflow.transport;

public interface FlowMessageOrBuilder extends
    // @@protoc_insertion_point(interface_extends:FlowMessage)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Flow timestamp in milliseconds.
   * </pre>
   *
   * <code>uint64 timestamp = 1;</code>
   * @return The timestamp.
   */
  long getTimestamp();

  /**
   * <pre>
   * Number of bytes transferred in the flow
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value num_bytes = 2;</code>
   * @return Whether the numBytes field is set.
   */
  boolean hasNumBytes();
  /**
   * <pre>
   * Number of bytes transferred in the flow
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value num_bytes = 2;</code>
   * @return The numBytes.
   */
  com.google.protobuf.UInt64Value getNumBytes();
  /**
   * <pre>
   * Number of bytes transferred in the flow
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value num_bytes = 2;</code>
   */
  com.google.protobuf.UInt64ValueOrBuilder getNumBytesOrBuilder();

  /**
   * <pre>
   * Direction of the flow (egress vs ingress)
   * </pre>
   *
   * <code>.Direction direction = 3;</code>
   * @return The enum numeric value on the wire for direction.
   */
  int getDirectionValue();
  /**
   * <pre>
   * Direction of the flow (egress vs ingress)
   * </pre>
   *
   * <code>.Direction direction = 3;</code>
   * @return The direction.
   */
  org.opennms.netmgt.telemetry.protocols.netflow.transport.Direction getDirection();

  /**
   * <pre>
   *  Destination address.
   * </pre>
   *
   * <code>string dst_address = 4;</code>
   * @return The dstAddress.
   */
  java.lang.String getDstAddress();
  /**
   * <pre>
   *  Destination address.
   * </pre>
   *
   * <code>string dst_address = 4;</code>
   * @return The bytes for dstAddress.
   */
  com.google.protobuf.ByteString
      getDstAddressBytes();

  /**
   * <pre>
   * Destination address hostname.
   * </pre>
   *
   * <code>string dst_hostname = 5;</code>
   * @return The dstHostname.
   */
  java.lang.String getDstHostname();
  /**
   * <pre>
   * Destination address hostname.
   * </pre>
   *
   * <code>string dst_hostname = 5;</code>
   * @return The bytes for dstHostname.
   */
  com.google.protobuf.ByteString
      getDstHostnameBytes();

  /**
   * <pre>
   * Destination autonomous system (AS).
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value dst_as = 6;</code>
   * @return Whether the dstAs field is set.
   */
  boolean hasDstAs();
  /**
   * <pre>
   * Destination autonomous system (AS).
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value dst_as = 6;</code>
   * @return The dstAs.
   */
  com.google.protobuf.UInt64Value getDstAs();
  /**
   * <pre>
   * Destination autonomous system (AS).
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value dst_as = 6;</code>
   */
  com.google.protobuf.UInt64ValueOrBuilder getDstAsOrBuilder();

  /**
   * <pre>
   * The number of contiguous bits in the source address subnet mask.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value dst_mask_len = 7;</code>
   * @return Whether the dstMaskLen field is set.
   */
  boolean hasDstMaskLen();
  /**
   * <pre>
   * The number of contiguous bits in the source address subnet mask.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value dst_mask_len = 7;</code>
   * @return The dstMaskLen.
   */
  com.google.protobuf.UInt32Value getDstMaskLen();
  /**
   * <pre>
   * The number of contiguous bits in the source address subnet mask.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value dst_mask_len = 7;</code>
   */
  com.google.protobuf.UInt32ValueOrBuilder getDstMaskLenOrBuilder();

  /**
   * <pre>
   * Destination port.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value dst_port = 8;</code>
   * @return Whether the dstPort field is set.
   */
  boolean hasDstPort();
  /**
   * <pre>
   * Destination port.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value dst_port = 8;</code>
   * @return The dstPort.
   */
  com.google.protobuf.UInt32Value getDstPort();
  /**
   * <pre>
   * Destination port.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value dst_port = 8;</code>
   */
  com.google.protobuf.UInt32ValueOrBuilder getDstPortOrBuilder();

  /**
   * <pre>
   * Slot number of the flow-switching engine.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value engine_id = 9;</code>
   * @return Whether the engineId field is set.
   */
  boolean hasEngineId();
  /**
   * <pre>
   * Slot number of the flow-switching engine.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value engine_id = 9;</code>
   * @return The engineId.
   */
  com.google.protobuf.UInt32Value getEngineId();
  /**
   * <pre>
   * Slot number of the flow-switching engine.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value engine_id = 9;</code>
   */
  com.google.protobuf.UInt32ValueOrBuilder getEngineIdOrBuilder();

  /**
   * <pre>
   * Type of flow-switching engine.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value engine_type = 10;</code>
   * @return Whether the engineType field is set.
   */
  boolean hasEngineType();
  /**
   * <pre>
   * Type of flow-switching engine.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value engine_type = 10;</code>
   * @return The engineType.
   */
  com.google.protobuf.UInt32Value getEngineType();
  /**
   * <pre>
   * Type of flow-switching engine.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value engine_type = 10;</code>
   */
  com.google.protobuf.UInt32ValueOrBuilder getEngineTypeOrBuilder();

  /**
   * <pre>
   * Unix timestamp in ms at which the previous exported packet-
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value delta_switched = 11;</code>
   * @return Whether the deltaSwitched field is set.
   */
  boolean hasDeltaSwitched();
  /**
   * <pre>
   * Unix timestamp in ms at which the previous exported packet-
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value delta_switched = 11;</code>
   * @return The deltaSwitched.
   */
  com.google.protobuf.UInt64Value getDeltaSwitched();
  /**
   * <pre>
   * Unix timestamp in ms at which the previous exported packet-
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value delta_switched = 11;</code>
   */
  com.google.protobuf.UInt64ValueOrBuilder getDeltaSwitchedOrBuilder();

  /**
   * <pre>
   * -associated with this flow was switched.
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value first_switched = 12;</code>
   * @return Whether the firstSwitched field is set.
   */
  boolean hasFirstSwitched();
  /**
   * <pre>
   * -associated with this flow was switched.
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value first_switched = 12;</code>
   * @return The firstSwitched.
   */
  com.google.protobuf.UInt64Value getFirstSwitched();
  /**
   * <pre>
   * -associated with this flow was switched.
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value first_switched = 12;</code>
   */
  com.google.protobuf.UInt64ValueOrBuilder getFirstSwitchedOrBuilder();

  /**
   * <pre>
   * -associated with this flow was switched.
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value last_switched = 13;</code>
   * @return Whether the lastSwitched field is set.
   */
  boolean hasLastSwitched();
  /**
   * <pre>
   * -associated with this flow was switched.
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value last_switched = 13;</code>
   * @return The lastSwitched.
   */
  com.google.protobuf.UInt64Value getLastSwitched();
  /**
   * <pre>
   * -associated with this flow was switched.
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value last_switched = 13;</code>
   */
  com.google.protobuf.UInt64ValueOrBuilder getLastSwitchedOrBuilder();

  /**
   * <pre>
   * Number of flow records in the associated packet.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value num_flow_records = 14;</code>
   * @return Whether the numFlowRecords field is set.
   */
  boolean hasNumFlowRecords();
  /**
   * <pre>
   * Number of flow records in the associated packet.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value num_flow_records = 14;</code>
   * @return The numFlowRecords.
   */
  com.google.protobuf.UInt32Value getNumFlowRecords();
  /**
   * <pre>
   * Number of flow records in the associated packet.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value num_flow_records = 14;</code>
   */
  com.google.protobuf.UInt32ValueOrBuilder getNumFlowRecordsOrBuilder();

  /**
   * <pre>
   * Number of packets in the flow.
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value num_packets = 15;</code>
   * @return Whether the numPackets field is set.
   */
  boolean hasNumPackets();
  /**
   * <pre>
   * Number of packets in the flow.
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value num_packets = 15;</code>
   * @return The numPackets.
   */
  com.google.protobuf.UInt64Value getNumPackets();
  /**
   * <pre>
   * Number of packets in the flow.
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value num_packets = 15;</code>
   */
  com.google.protobuf.UInt64ValueOrBuilder getNumPacketsOrBuilder();

  /**
   * <pre>
   * Flow packet sequence number.
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value flow_seq_num = 16;</code>
   * @return Whether the flowSeqNum field is set.
   */
  boolean hasFlowSeqNum();
  /**
   * <pre>
   * Flow packet sequence number.
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value flow_seq_num = 16;</code>
   * @return The flowSeqNum.
   */
  com.google.protobuf.UInt64Value getFlowSeqNum();
  /**
   * <pre>
   * Flow packet sequence number.
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value flow_seq_num = 16;</code>
   */
  com.google.protobuf.UInt64ValueOrBuilder getFlowSeqNumOrBuilder();

  /**
   * <pre>
   * Input SNMP ifIndex.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value input_snmp_ifindex = 17;</code>
   * @return Whether the inputSnmpIfindex field is set.
   */
  boolean hasInputSnmpIfindex();
  /**
   * <pre>
   * Input SNMP ifIndex.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value input_snmp_ifindex = 17;</code>
   * @return The inputSnmpIfindex.
   */
  com.google.protobuf.UInt32Value getInputSnmpIfindex();
  /**
   * <pre>
   * Input SNMP ifIndex.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value input_snmp_ifindex = 17;</code>
   */
  com.google.protobuf.UInt32ValueOrBuilder getInputSnmpIfindexOrBuilder();

  /**
   * <pre>
   * Output SNMP ifIndex.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value output_snmp_ifindex = 18;</code>
   * @return Whether the outputSnmpIfindex field is set.
   */
  boolean hasOutputSnmpIfindex();
  /**
   * <pre>
   * Output SNMP ifIndex.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value output_snmp_ifindex = 18;</code>
   * @return The outputSnmpIfindex.
   */
  com.google.protobuf.UInt32Value getOutputSnmpIfindex();
  /**
   * <pre>
   * Output SNMP ifIndex.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value output_snmp_ifindex = 18;</code>
   */
  com.google.protobuf.UInt32ValueOrBuilder getOutputSnmpIfindexOrBuilder();

  /**
   * <pre>
   * IPv4 vs IPv6.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value ip_protocol_version = 19;</code>
   * @return Whether the ipProtocolVersion field is set.
   */
  boolean hasIpProtocolVersion();
  /**
   * <pre>
   * IPv4 vs IPv6.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value ip_protocol_version = 19;</code>
   * @return The ipProtocolVersion.
   */
  com.google.protobuf.UInt32Value getIpProtocolVersion();
  /**
   * <pre>
   * IPv4 vs IPv6.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value ip_protocol_version = 19;</code>
   */
  com.google.protobuf.UInt32ValueOrBuilder getIpProtocolVersionOrBuilder();

  /**
   * <pre>
   * Next hop IpAddress.
   * </pre>
   *
   * <code>string next_hop_address = 20;</code>
   * @return The nextHopAddress.
   */
  java.lang.String getNextHopAddress();
  /**
   * <pre>
   * Next hop IpAddress.
   * </pre>
   *
   * <code>string next_hop_address = 20;</code>
   * @return The bytes for nextHopAddress.
   */
  com.google.protobuf.ByteString
      getNextHopAddressBytes();

  /**
   * <pre>
   * Next hop hostname.
   * </pre>
   *
   * <code>string next_hop_hostname = 21;</code>
   * @return The nextHopHostname.
   */
  java.lang.String getNextHopHostname();
  /**
   * <pre>
   * Next hop hostname.
   * </pre>
   *
   * <code>string next_hop_hostname = 21;</code>
   * @return The bytes for nextHopHostname.
   */
  com.google.protobuf.ByteString
      getNextHopHostnameBytes();

  /**
   * <pre>
   * IP protocol number i.e 6 for TCP, 17 for UDP
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value protocol = 22;</code>
   * @return Whether the protocol field is set.
   */
  boolean hasProtocol();
  /**
   * <pre>
   * IP protocol number i.e 6 for TCP, 17 for UDP
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value protocol = 22;</code>
   * @return The protocol.
   */
  com.google.protobuf.UInt32Value getProtocol();
  /**
   * <pre>
   * IP protocol number i.e 6 for TCP, 17 for UDP
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value protocol = 22;</code>
   */
  com.google.protobuf.UInt32ValueOrBuilder getProtocolOrBuilder();

  /**
   * <pre>
   * Sampling algorithm ID.
   * </pre>
   *
   * <code>.SamplingAlgorithm sampling_algorithm = 23;</code>
   * @return The enum numeric value on the wire for samplingAlgorithm.
   */
  int getSamplingAlgorithmValue();
  /**
   * <pre>
   * Sampling algorithm ID.
   * </pre>
   *
   * <code>.SamplingAlgorithm sampling_algorithm = 23;</code>
   * @return The samplingAlgorithm.
   */
  org.opennms.netmgt.telemetry.protocols.netflow.transport.SamplingAlgorithm getSamplingAlgorithm();

  /**
   * <pre>
   * Sampling interval.
   * </pre>
   *
   * <code>.google.protobuf.DoubleValue sampling_interval = 24;</code>
   * @return Whether the samplingInterval field is set.
   */
  boolean hasSamplingInterval();
  /**
   * <pre>
   * Sampling interval.
   * </pre>
   *
   * <code>.google.protobuf.DoubleValue sampling_interval = 24;</code>
   * @return The samplingInterval.
   */
  com.google.protobuf.DoubleValue getSamplingInterval();
  /**
   * <pre>
   * Sampling interval.
   * </pre>
   *
   * <code>.google.protobuf.DoubleValue sampling_interval = 24;</code>
   */
  com.google.protobuf.DoubleValueOrBuilder getSamplingIntervalOrBuilder();

  /**
   * <pre>
   * Source address.
   * </pre>
   *
   * <code>string src_address = 26;</code>
   * @return The srcAddress.
   */
  java.lang.String getSrcAddress();
  /**
   * <pre>
   * Source address.
   * </pre>
   *
   * <code>string src_address = 26;</code>
   * @return The bytes for srcAddress.
   */
  com.google.protobuf.ByteString
      getSrcAddressBytes();

  /**
   * <pre>
   * Source hostname.
   * </pre>
   *
   * <code>string src_hostname = 27;</code>
   * @return The srcHostname.
   */
  java.lang.String getSrcHostname();
  /**
   * <pre>
   * Source hostname.
   * </pre>
   *
   * <code>string src_hostname = 27;</code>
   * @return The bytes for srcHostname.
   */
  com.google.protobuf.ByteString
      getSrcHostnameBytes();

  /**
   * <pre>
   * Source AS number.
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value src_as = 28;</code>
   * @return Whether the srcAs field is set.
   */
  boolean hasSrcAs();
  /**
   * <pre>
   * Source AS number.
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value src_as = 28;</code>
   * @return The srcAs.
   */
  com.google.protobuf.UInt64Value getSrcAs();
  /**
   * <pre>
   * Source AS number.
   * </pre>
   *
   * <code>.google.protobuf.UInt64Value src_as = 28;</code>
   */
  com.google.protobuf.UInt64ValueOrBuilder getSrcAsOrBuilder();

  /**
   * <pre>
   * The number of contiguous bits in the destination address subnet mask.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value src_mask_len = 29;</code>
   * @return Whether the srcMaskLen field is set.
   */
  boolean hasSrcMaskLen();
  /**
   * <pre>
   * The number of contiguous bits in the destination address subnet mask.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value src_mask_len = 29;</code>
   * @return The srcMaskLen.
   */
  com.google.protobuf.UInt32Value getSrcMaskLen();
  /**
   * <pre>
   * The number of contiguous bits in the destination address subnet mask.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value src_mask_len = 29;</code>
   */
  com.google.protobuf.UInt32ValueOrBuilder getSrcMaskLenOrBuilder();

  /**
   * <pre>
   * Source port.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value src_port = 30;</code>
   * @return Whether the srcPort field is set.
   */
  boolean hasSrcPort();
  /**
   * <pre>
   * Source port.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value src_port = 30;</code>
   * @return The srcPort.
   */
  com.google.protobuf.UInt32Value getSrcPort();
  /**
   * <pre>
   * Source port.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value src_port = 30;</code>
   */
  com.google.protobuf.UInt32ValueOrBuilder getSrcPortOrBuilder();

  /**
   * <pre>
   * TCP Flags.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value tcp_flags = 31;</code>
   * @return Whether the tcpFlags field is set.
   */
  boolean hasTcpFlags();
  /**
   * <pre>
   * TCP Flags.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value tcp_flags = 31;</code>
   * @return The tcpFlags.
   */
  com.google.protobuf.UInt32Value getTcpFlags();
  /**
   * <pre>
   * TCP Flags.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value tcp_flags = 31;</code>
   */
  com.google.protobuf.UInt32ValueOrBuilder getTcpFlagsOrBuilder();

  /**
   * <pre>
   * TOS
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value tos = 32;</code>
   * @return Whether the tos field is set.
   */
  boolean hasTos();
  /**
   * <pre>
   * TOS
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value tos = 32;</code>
   * @return The tos.
   */
  com.google.protobuf.UInt32Value getTos();
  /**
   * <pre>
   * TOS
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value tos = 32;</code>
   */
  com.google.protobuf.UInt32ValueOrBuilder getTosOrBuilder();

  /**
   * <pre>
   * Netflow version
   * </pre>
   *
   * <code>.NetflowVersion netflow_version = 33;</code>
   * @return The enum numeric value on the wire for netflowVersion.
   */
  int getNetflowVersionValue();
  /**
   * <pre>
   * Netflow version
   * </pre>
   *
   * <code>.NetflowVersion netflow_version = 33;</code>
   * @return The netflowVersion.
   */
  org.opennms.netmgt.telemetry.protocols.netflow.transport.NetflowVersion getNetflowVersion();

  /**
   * <pre>
   * VLAN ID.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value vlan = 34;</code>
   * @return Whether the vlan field is set.
   */
  boolean hasVlan();
  /**
   * <pre>
   * VLAN ID.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value vlan = 34;</code>
   * @return The vlan.
   */
  com.google.protobuf.UInt32Value getVlan();
  /**
   * <pre>
   * VLAN ID.
   * </pre>
   *
   * <code>.google.protobuf.UInt32Value vlan = 34;</code>
   */
  com.google.protobuf.UInt32ValueOrBuilder getVlanOrBuilder();

  /**
   * <pre>
   * node lookup identifier.
   * </pre>
   *
   * <code>string node_identifier = 35;</code>
   * @return The nodeIdentifier.
   */
  java.lang.String getNodeIdentifier();
  /**
   * <pre>
   * node lookup identifier.
   * </pre>
   *
   * <code>string node_identifier = 35;</code>
   * @return The bytes for nodeIdentifier.
   */
  com.google.protobuf.ByteString
      getNodeIdentifierBytes();

  /**
   * <code>repeated .Value rawMessage = 36;</code>
   */
  java.util.List<org.opennms.netmgt.telemetry.protocols.netflow.transport.Value> 
      getRawMessageList();
  /**
   * <code>repeated .Value rawMessage = 36;</code>
   */
  org.opennms.netmgt.telemetry.protocols.netflow.transport.Value getRawMessage(int index);
  /**
   * <code>repeated .Value rawMessage = 36;</code>
   */
  int getRawMessageCount();
  /**
   * <code>repeated .Value rawMessage = 36;</code>
   */
  java.util.List<? extends org.opennms.netmgt.telemetry.protocols.netflow.transport.ValueOrBuilder> 
      getRawMessageOrBuilderList();
  /**
   * <code>repeated .Value rawMessage = 36;</code>
   */
  org.opennms.netmgt.telemetry.protocols.netflow.transport.ValueOrBuilder getRawMessageOrBuilder(
      int index);
}
