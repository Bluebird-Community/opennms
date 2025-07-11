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
package org.opennms.netmgt.flows.elastic;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import org.opennms.integration.api.v1.flows.Flow;

import com.google.gson.annotations.SerializedName;

/**
 * Member variables are sorted by the value of the @SerializedName annotation.
 */
public class FlowDocument {
    private static final int DOCUMENT_VERSION = 1;

    public FlowDocument() {
    }

    /**
     * Flow timestamp in milliseconds.
     */
    @SerializedName("@timestamp")
    private long timestamp;

    /**
     * Applied clock correction im milliseconds.
     */
    @SerializedName("@clock_correction")
    private long clockCorrection;

    /**
     * Schema version.
     */
    @SerializedName("@version")
    private Integer version = DOCUMENT_VERSION;

    /**
     * Exporter IP address.
     */
    @SerializedName("host")
    private String host;

    /**
     * The set of all hosts that are involved in this flow. This should include at a minimum the src and dst IP
     * addresses and may also include host names for those IPs.
     */
    @SerializedName("hosts")
    private Set<String> hosts = new LinkedHashSet<>();

    /**
     * Exported location.
     */
    @SerializedName("location")
    private String location;

    /**
     * Application name as determined by the
     * classification engine.
     */
    @SerializedName("netflow.application")
    private String application;

    /**
     * Number of bytes transferred in the flow.
     */
    @SerializedName("netflow.bytes")
    private Long bytes;

    /**
     * Key used to group and identify conversations
     */
    @SerializedName("netflow.convo_key")
    private String convoKey;

    /**
     * Direction of the flow (egress vs ingress)
     */
    @SerializedName("netflow.direction")
    private Direction direction;

    /**
     * Destination address.
     */
    @SerializedName("netflow.dst_addr")
    private String dstAddr;

    /**
     * Destination address hostname.
     */
    @SerializedName("netflow.dst_addr_hostname")
    private String dstAddrHostname;

    /**
     * Destination autonomous system (AS).
     */
    @SerializedName("netflow.dst_as")
    private Long dstAs;

    /**
     * Locality of the destination address (i.e. private vs public address)
     */
    @SerializedName("netflow.dst_locality")
    private Locality dstLocality;

    /**
     * The number of contiguous bits in the source address subnet mask.
     */
    @SerializedName("netflow.dst_mask_len")
    private Integer dstMaskLen;

    /**
     * Destination port.
     */
    @SerializedName("netflow.dst_port")
    private Integer dstPort;

    /**
     * Slot number of the flow-switching engine.
     */
    @SerializedName("netflow.engine_id")
    private Integer engineId;

    /**
     * Type of flow-switching engine.
     */
    @SerializedName("netflow.engine_type")
    private Integer engineType;

    /**
     * Unix timestamp in ms at which the first packet
     * associated with this flow was switched.
     */
    @SerializedName("netflow.first_switched")
    private Long firstSwitched;

    /**
     * Locality of the flow:
     * private if both the source and destination localities are private,
     * and public otherwise.
     */
    @SerializedName("netflow.flow_locality")
    private Locality flowLocality;

    /**
     * Number of flow records in the associated packet.
     */
    @SerializedName("netflow.flow_records")
    private int flowRecords;

    /**
     * Flow packet sequence number.
     */
    @SerializedName("netflow.flow_seq_num")
    private long flowSeqNum;

    /**
     * SNMP ifIndex
     */
    @SerializedName("netflow.input_snmp")
    private Integer inputSnmp;

    /**
     * IPv4 vs IPv6
     */
    @SerializedName("netflow.ip_protocol_version")
    private Integer ipProtocolVersion;

    /**
     * Unix timestamp in ms at which the last packet
     * associated with this flow was switched.
     */
    @SerializedName("netflow.last_switched")
    private Long lastSwitched;

    /**
     * Next hop
     */
    @SerializedName("netflow.next_hop")
    private String nextHop;

    /**
     * Next hop hostname
     */
    @SerializedName("netflow.next_hop_hostname")
    private String nextHopHostname;

    /**
     * SNMP ifIndex
     */
    @SerializedName("netflow.output_snmp")
    private Integer outputSnmp;

    /**
     * Number of packets in the flow
     */
    @SerializedName("netflow.packets")
    private Long packets;

    /**
     * IP protocol number i.e 6 for TCP, 17 for UDP
     */
    @SerializedName("netflow.protocol")
    private Integer protocol;

    /**
     * Sampling algorithm ID
     */
    @SerializedName("netflow.sampling_algorithm")
    private SamplingAlgorithm samplingAlgorithm;

    /**
     * Sampling interval
     */
    @SerializedName("netflow.sampling_interval")
    private Double samplingInterval;

    /**
     * Source address.
     */
    @SerializedName("netflow.src_addr")
    private String srcAddr;

    /**
     * Source address hostname.
     */
    @SerializedName("netflow.src_addr_hostname")
    private String srcAddrHostname;

    /**
     * Source autonomous system (AS).
     */
    @SerializedName("netflow.src_as")
    private Long srcAs;

    /**
     * Locality of the source address (i.e. private vs public address)
     */
    @SerializedName("netflow.src_locality")
    private Locality srcLocality;

    /**
     * The number of contiguous bits in the destination address subnet mask.
     */
    @SerializedName("netflow.src_mask_len")
    private Integer srcMaskLen;

    /**
     * Source port.
     */
    @SerializedName("netflow.src_port")
    private Integer srcPort;

    /**
     * TCP Flags.
     */
    @SerializedName("netflow.tcp_flags")
    private Integer tcpFlags;

    /**
     * Unix timestamp in ms at which the previous exported packet
     * associated with this flow was switched.
     */
    @SerializedName("netflow.delta_switched")
    private Long deltaSwitched;

    /**
     * TOS.
     */
    @SerializedName("netflow.tos")
    private Integer tos;

    @SerializedName("netflow.ecn")
    private Integer ecn;

    @SerializedName("netflow.dscp")
    private Integer dscp;

    /**
     * Netfow version
     */
    @SerializedName("netflow.version")
    private NetflowVersion netflowVersion;

    /**
     * VLAN Name.
     */
    @SerializedName("netflow.vlan")
    private String vlan;

    /**
     * Destination node details.
     */
    @SerializedName("node_dst")
    private NodeDocument nodeDst;

    /**
     * Exported node details.
     */
    @SerializedName("node_exporter")
    private NodeDocument nodeExporter;

    /**
     * Source node details.
     */
    @SerializedName("node_src")
    private NodeDocument nodeSrc;

    public void addHost(String host) {
        Objects.requireNonNull(host);
        hosts.add(host);
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public long getClockCorrection() {
        return this.clockCorrection;
    }

    public void setClockCorrection(final long clockCorrection) {
        this.clockCorrection = clockCorrection;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Set<String> getHosts() {
        return hosts;
    }

    public void setHosts(Set<String> hosts) {
        this.hosts = hosts;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getApplication() {
        return application;
    }

    public void setApplication(String application) {
        this.application = application;
    }

    public Long getBytes() {
        return bytes;
    }

    public void setBytes(Long bytes) {
        this.bytes = bytes;
    }

    public String getConvoKey() {
        return convoKey;
    }

    public void setConvoKey(String convoKey) {
        this.convoKey = convoKey;
    }

    public Direction getDirection() {
        return direction;
    }

    public void setDirection(Direction direction) {
        this.direction = direction;
    }

    public String getDstAddr() {
        return dstAddr;
    }

    public void setDstAddr(String dstAddr) {
        addHost(dstAddr);
        this.dstAddr = dstAddr;
    }

    public String getDstAddrHostname() {
        return dstAddrHostname;
    }

    public void setDstAddrHostname(String dstAddrHostname) {
        this.dstAddrHostname = dstAddrHostname;
    }

    public Long getDstAs() {
        return dstAs;
    }

    public void setDstAs(Long dstAs) {
        this.dstAs = dstAs;
    }

    public Locality getDstLocality() {
        return dstLocality;
    }

    public void setDstLocality(Locality dstLocality) {
        this.dstLocality = dstLocality;
    }

    public Integer getDstMaskLen() {
        return dstMaskLen;
    }

    public void setDstMaskLen(Integer dstMaskLen) {
        this.dstMaskLen = dstMaskLen;
    }

    public Integer getDstPort() {
        return dstPort;
    }

    public void setDstPort(Integer dstPort) {
        this.dstPort = dstPort;
    }

    public Integer getEngineId() {
        return engineId;
    }

    public void setEngineId(Integer engineId) {
        this.engineId = engineId;
    }

    public Integer getEngineType() {
        return engineType;
    }

    public void setEngineType(Integer engineType) {
        this.engineType = engineType;
    }

    public Long getFirstSwitched() {
        return firstSwitched;
    }

    public void setFirstSwitched(Long firstSwitched) {
        this.firstSwitched = firstSwitched;
    }

    public Locality getFlowLocality() {
        return flowLocality;
    }

    public void setFlowLocality(Locality flowLocality) {
        this.flowLocality = flowLocality;
    }

    public int getFlowRecords() {
        return flowRecords;
    }

    public void setFlowRecords(int flowRecords) {
        this.flowRecords = flowRecords;
    }

    public long getFlowSeqNum() {
        return flowSeqNum;
    }

    public void setFlowSeqNum(long flowSeqNum) {
        this.flowSeqNum = flowSeqNum;
    }

    public Integer getInputSnmp() {
        return inputSnmp;
    }

    public void setInputSnmp(Integer inputSnmp) {
        this.inputSnmp = inputSnmp;
    }

    public Integer getIpProtocolVersion() {
        return ipProtocolVersion;
    }

    public void setIpProtocolVersion(Integer ipProtocolVersion) {
        this.ipProtocolVersion = ipProtocolVersion;
    }

    public Long getLastSwitched() {
        return lastSwitched;
    }

    public void setLastSwitched(Long lastSwitched) {
        this.lastSwitched = lastSwitched;
    }

    public String getNextHop() {
        return nextHop;
    }

    public void setNextHop(String nextHop) {
        this.nextHop = nextHop;
    }

    public String getNextHopHostname() {
        return nextHopHostname;
    }

    public void setNextHopHostname(String nextHopHostname) {
        this.nextHopHostname = nextHopHostname;
    }

    public Integer getOutputSnmp() {
        return outputSnmp;
    }

    public void setOutputSnmp(Integer outputSnmp) {
        this.outputSnmp = outputSnmp;
    }

    public Long getPackets() {
        return packets;
    }

    public void setPackets(Long packets) {
        this.packets = packets;
    }

    public Integer getProtocol() {
        return protocol;
    }

    public void setProtocol(Integer protocol) {
        this.protocol = protocol;
    }

    public SamplingAlgorithm getSamplingAlgorithm() {
        return samplingAlgorithm;
    }

    public void setSamplingAlgorithm(SamplingAlgorithm samplingAlgorithm) {
        this.samplingAlgorithm = samplingAlgorithm;
    }

    public Double getSamplingInterval() {
        return samplingInterval;
    }

    public void setSamplingInterval(Double samplingInterval) {
        this.samplingInterval = samplingInterval;
    }

    public String getSrcAddr() {
        return srcAddr;
    }

    public void setSrcAddr(String srcAddr) {
        addHost(srcAddr);
        this.srcAddr = srcAddr;
    }

    public String getSrcAddrHostname() {
        return srcAddrHostname;
    }

    public void setSrcAddrHostname(String srcAddrHostname) {
        this.srcAddrHostname = srcAddrHostname;
    }

    public Long getSrcAs() {
        return srcAs;
    }

    public void setSrcAs(Long srcAs) {
        this.srcAs = srcAs;
    }

    public Locality getSrcLocality() {
        return srcLocality;
    }

    public void setSrcLocality(Locality srcLocality) {
        this.srcLocality = srcLocality;
    }

    public Integer getSrcMaskLen() {
        return srcMaskLen;
    }

    public void setSrcMaskLen(Integer srcMaskLen) {
        this.srcMaskLen = srcMaskLen;
    }

    public Integer getSrcPort() {
        return srcPort;
    }

    public void setSrcPort(Integer srcPort) {
        this.srcPort = srcPort;
    }

    public Integer getTcpFlags() {
        return tcpFlags;
    }

    public void setTcpFlags(Integer tcpFlags) {
        this.tcpFlags = tcpFlags;
    }

    public Long getDeltaSwitched() {
        return deltaSwitched;
    }

    public void setDeltaSwitched(Long deltaSwitched) {
        this.deltaSwitched = deltaSwitched;
    }

    public Integer getTos() {
        return tos;
    }

    public void setTos(final Integer tos) {
        this.tos = tos;
    }

    private void setEcn(final Integer ecn) {
        this.ecn = ecn;
    }

    private void setDscp(final Integer dscp) {
        this.dscp = dscp;
    }

    public Integer getEcn() {
        return ecn;
    }

    public Integer getDscp() {
        return dscp;
    }

    public NetflowVersion getNetflowVersion() {
        return netflowVersion;
    }

    public void setNetflowVersion(NetflowVersion netflowVersion) {
        this.netflowVersion = netflowVersion;
    }

    public String getVlan() {
        return vlan;
    }

    public void setVlan(String vlan) {
        this.vlan = vlan;
    }

    public NodeDocument getNodeDst() {
        return nodeDst;
    }

    public void setNodeDst(NodeDocument nodeDst) {
        this.nodeDst = nodeDst;
    }

    public NodeDocument getNodeExporter() {
        return nodeExporter;
    }

    public void setNodeExporter(NodeDocument nodeExporter) {
        this.nodeExporter = nodeExporter;
    }

    public NodeDocument getNodeSrc() {
        return nodeSrc;
    }

    public void setNodeSrc(NodeDocument nodeSrc) {
        this.nodeSrc = nodeSrc;
    }

    public static FlowDocument from(final Flow flow) {
        final FlowDocument doc = new FlowDocument();
        doc.setTimestamp(flow.getTimestamp() != null ? flow.getTimestamp().toEpochMilli() : 0);
        doc.setBytes(flow.getBytes());
        doc.setDirection(Direction.from(flow.getDirection()));
        doc.setDstAddr(flow.getDstAddr());
        flow.getDstAddrHostname().ifPresent(doc::setDstAddrHostname);
        doc.setDstAs(flow.getDstAs());
        doc.setDstMaskLen(flow.getDstMaskLen());
        doc.setDstPort(flow.getDstPort());
        doc.setEngineId(flow.getEngineId());
        doc.setEngineType(flow.getEngineType());
        doc.setFirstSwitched(flow.getFirstSwitched() != null ? flow.getFirstSwitched().toEpochMilli() : 0);
        doc.setFlowRecords(flow.getFlowRecords());
        doc.setFlowSeqNum(flow.getFlowSeqNum());
        doc.setInputSnmp(flow.getInputSnmp());
        doc.setIpProtocolVersion(flow.getIpProtocolVersion());
        doc.setLastSwitched(flow.getLastSwitched() != null ? flow.getLastSwitched().toEpochMilli() : 0);
        doc.setNextHop(flow.getNextHop());
        flow.getNextHopHostname().ifPresent(doc::setNextHopHostname);
        doc.setOutputSnmp(flow.getOutputSnmp());
        doc.setPackets(flow.getPackets());
        doc.setProtocol(flow.getProtocol());
        doc.setSamplingAlgorithm(SamplingAlgorithm.from(flow.getSamplingAlgorithm()));
        doc.setSamplingInterval(flow.getSamplingInterval());
        doc.setSrcAddr(flow.getSrcAddr());
        flow.getSrcAddrHostname().ifPresent(doc::setSrcAddrHostname);
        doc.setSrcAs(flow.getSrcAs());
        doc.setSrcMaskLen(flow.getSrcMaskLen());
        doc.setSrcPort(flow.getSrcPort());
        doc.setTcpFlags(flow.getTcpFlags());
        doc.setDeltaSwitched(flow.getDeltaSwitched() != null ? flow.getDeltaSwitched().toEpochMilli() : 0);
        doc.setTos(flow.getTos());
        doc.setDscp(flow.getDscp());
        doc.setEcn(flow.getEcn());
        doc.setNetflowVersion(NetflowVersion.from(flow.getNetflowVersion()));
        doc.setVlan(flow.getVlan() != null ? Integer.toUnsignedString(flow.getVlan()) : null);

        doc.setApplication(flow.getApplication());
        doc.setHost(flow.getHost());
        doc.setLocation(flow.getLocation());
        doc.setSrcLocality(Locality.from(flow.getSrcLocality()));
        doc.setDstLocality(Locality.from(flow.getDstLocality()));
        doc.setFlowLocality(Locality.from(flow.getFlowLocality()));
        doc.setNodeSrc(NodeDocument.from(flow.getSrcNodeInfo()));
        doc.setNodeDst(NodeDocument.from(flow.getDstNodeInfo()));
        doc.setNodeExporter(NodeDocument.from(flow.getExporterNodeInfo()));
        doc.setClockCorrection(flow.getClockCorrection() != null ? flow.getClockCorrection().toMillis() : 0);
        doc.setConvoKey(flow.getConvoKey());

        return doc;
    }
}
