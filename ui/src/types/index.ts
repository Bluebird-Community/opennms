///
/// Licensed to The OpenNMS Group, Inc (TOG) under one or more
/// contributor license agreements.  See the LICENSE.md file
/// distributed with this work for additional information
/// regarding copyright ownership.
///
/// TOG licenses this file to You under the GNU Affero General
/// Public License Version 3 (the "License") or (at your option)
/// any later version.  You may not use this file except in
/// compliance with the License.  You may obtain a copy of the
/// License at:
///
///      https://www.gnu.org/licenses/agpl-3.0.txt
///
/// Unless required by applicable law or agreed to in writing,
/// software distributed under the License is distributed on an
/// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
/// either express or implied.  See the License for the specific
/// language governing permissions and limitations under the
/// License.
///

import { SORT } from '@featherds/table'

export type UpdateModelFunction = (_value: any) => any

export interface SnackbarProps {
  msg: string
  center?: boolean
  error?: boolean
  timeout?: number
}

export interface SearchResultResponse {
  label?: string
  context: {
    name: string
    weight: number
  }
  empty: boolean
  more: boolean
  results: SearchResultMatch[]
}

export interface SearchResultMatch {
    label: string;
    value: string;
}

export interface SearchResultItem {
    identifier: string
    icon: string;
    label: string;
    url: string;
    properties: any;
    matches: SearchResultMatch[];
    weight: number;
}

export type SearchResultsByContext = Array<{label: string, results: SearchResultResponse[]}>

export interface ApiResponse {
  count: number
  offset: number
  totalCount: number
}

export interface CategoryApiResponse extends ApiResponse {
  category: Category[]
}
export interface NodeApiResponse extends ApiResponse {
  node: Node[]
}
export interface EventApiResponse extends ApiResponse {
  event: Event[]
}

export interface AlarmApiResponse extends ApiResponse {
  alarm: Alarm[]
}
export interface GraphNodesApiResponse {
  vertices: Vertice[]
  edges: Edge[]
}

export interface SnmpInterfaceApiResponse extends ApiResponse {
  snmpInterface: SnmpInterface[]
}

export interface IpInterfaceApiResponse extends ApiResponse {
  ipInterface: IpInterface[]
}

export interface OutagesApiResponse extends ApiResponse {
  outage: Outage[]
}

export interface IfServiceApiResponse extends ApiResponse {
  'monitored-service': IfService[]
}

export interface MonitoringLocationApiResponse extends ApiResponse {
  location: MonitoringLocation[]
}

export interface MonitoringSystemMainResponse {
  id: string
  label: string
  location: string
  type: string
}

export interface Node {
  location: string
  type: string
  label: string
  id: string
  assetRecord: {
    longitude: string
    latitude: string
    category: string
    description: string
    maintcontract: string
  }
  categories: Category[]
  createTime: number
  foreignId: string
  foreignSource: string
  lastEgressFlow: number      // timestamp
  lastIngressFlow: number
  labelSource: string
  lastCapabilitiesScan: string
  primaryInterface: number
  sysObjectId: string
  sysDescription: string
  sysName: string
  sysContact: string
  sysLocation: string
}

export interface NodeColumnSelectionItem {
  id: string
  label: string
  selected: boolean
  order: number // 0-based
}

export interface MapNode {
  id: string
  coordinates: [number, number]
  foreignSource: string
  foreignId: string
  label: string
  labelSource: any
  lastCapabilitiesScan: string
  primaryInterface: number
  sysObjectId: string
  sysDescription: string
  sysName: string
  sysContact: any
  sysLocation: any
  alarm: Alarm[]
}

export interface Alarm {
  id: string
  severity: string
  nodeId: number
  nodeLabel: string
  uei: string
  count: number
  lastEventTime: number
  logMessage: string
}

export interface Event {
  createTime: number
  description: string
  display: string
  id: number
  label: string
  location: string
  log: string
  logMessage: string
  nodeId: number
  nodeLabel: string
  parameters: Array<any>
  severity: string
  source: string
  time: number
  uei: string
}

export interface MonitoringLocation {
  tags: string[]
  geolocation: string | null
  longitude: number
  latitude: number
  priority: number
  'location-name': string
  'monitoring-area': string
  name: string    // mapped from 'location-name' after API GET call response
  area: string    // mapped from 'monitoring-area' after API GET call response
}

export interface SnmpInterface {
  collect: boolean
  collectFlag: string
  collectionUserSpecified: boolean
  hasEgressFlows: boolean
  hasFlows: boolean
  hasIngressFlows: boolean
  id: number
  ifAdminStatus: number
  ifAlias: any
  ifDescr: any
  ifIndex: number
  ifName: any
  ifOperStatus: number
  ifSpeed: number
  ifType: number
  lastCapsdPoll: number
  lastEgressFlow: any
  lastIngressFlow: any
  lastSnmpPoll: number
  physAddr: any
  poll: boolean
}

export interface IpInterface {
  ifIndex: string
  isManaged: null | string
  id: string
  ipAddress: string
  isDown: boolean
  lastCapsdPoll: number
  lastEgressFlow: any
  lastIngressFlow: any
  monitoredServiceCount: number
  nodeId: number
  snmpInterface: SnmpInterface
  snmpPrimary: string
  hostName: string
}

export interface Outage {
  nodeId: number
  ipAddress: string
  serviceIs: number
  nodeLabel: string
  location: string
  hostname: string
  serviceName: string
  outageId: number
}

export interface IfService {
  id: string
  ipAddress: string
  ipInterfaceId: number
  isDown: false
  isMonitored: boolean
  node: string
  serviceName: string
  status: string
  statusCode: string
}

export interface Category {
  authorizedGroups: string[]
  id: number
  name: string
}

export interface QueryParameters {
  limit?: number
  offset?: number
  _s?: string
  orderBy?: string
  order?: SORT
  search?: string
  groupBy?: string
  groupByValue?: string
  [x: string]: any
}

export interface FeatherSortObject {
  property: string
  value: SORT | any
}

export interface SortProps extends FeatherSortObject {
  filters: Record<string, unknown>
  first: number
  multiSortMeta: Record<string, unknown>
  originalEvent: MouseEvent
  rows: number
  sortField: string
  sortOrder: 1 | -1
}

export interface NodeAvailability {
  availability: number
  id: number
  ipinterfaces: {
    address: string
    availability: number
    id: number
    services: [
      {
        id: number
        name: string
        availability: number
      }
    ]
  }[]
  'service-count': number
  'service-down-count': number
}

export interface FileEditorResponseLog {
  success: boolean
  msg: string
}

export interface BreadCrumb {
  label: string
  to: string
  position?: string
  isAbsoluteLink?: boolean
}

export interface Vertice {
  tooltipText: string
  namespace: string
  ipAddress: string
  x: string
  y: string
  id: string
  label: string
  iconKey: string
  nodeId: string
}

export interface Edge {
  source: { namespace: string; id: number }
  target: { namespace: string; id: number }
}

export interface Coordinates {
  latitude: number | string
  longitude: number | string
}

export interface AlarmQueryParameters {
  ack?: boolean
  clear?: boolean
  escalate?: boolean
}

export interface AlarmModificationQueryVariable {
  pathVariable: string
  queryParameters: AlarmQueryParameters
}
export interface WhoAmIResponse {
  fullName: string
  id: string
  internal: boolean
  roles: string[]
}

export interface AppInfo {
  datetimeformatConfig: {
    zoneId: string
    datetimeformat: string
  }
  displayVersion: string
  packageDescription: string
  packageName: string
  services: object
  ticketerConfig: {
    plugin: string | null
    enabled: boolean
  }
  version: string
}

export interface StartEndTime {
  startTime: string | number
  endTime: string | number
  format: string
}

export interface ResourceDefinitionsApiResponse extends ApiResponse {
  name: string[]
}

export interface ResourcesApiResponse extends ApiResponse {
  resource: Resource[]
}

export interface Resource {
  children?: ResourcesApiResponse
  externalValueAttributes: Record<string, unknown>
  id: string
  label: string
  link: string
  name: string
  parentId: string | null
  rrdGraphAttributes: Record<string, unknown>
  stringPropertyAttributes: Record<string, unknown>
  typeLabel: string
}

export interface PreFabGraph {
  columns: string[]
  command: string
  description: string | null
  externalValues: string[]
  height: number | null
  name: string
  order: number
  propertiesValues: string[]
  suppress: string[]
  title: string
  types: string[]
  width: number | null
}

export interface Metric {
  expression?: string
  aggregation: string
  attribute: string
  label?: string
  name?: string
  datasource?: string
  resourceId: string
  transient?: true | false
}

export interface GraphMetricsPayload {
  end: number
  start: number
  step: number
  source: Metric[]
  expression?: { label: string; transient: boolean; value: string }[]
}

export interface GraphDefinition {
  id: string
  definitions: string[]
  label: string
}

export interface GraphMetricsResponse {
  columns: [{ values: number[] }]
  constants: Record<string, any>[]
  end: number
  labels: string[]
  metadata: {
    nodes: {
      id: number
      label: string
      'foreign-source': string
      'foreign-id': string
    }[]
    resources: {
      id: string
      label: string
      name: string
      'node-id': number
      'parent-id': string
    }[]
  }
  start: number
  step: number
  timestamps: number[]
  formattedTimestamps: string[]
  formattedLabels: { name: string; statement: string }[]
}

export interface ConvertedGraphData {
  title: string
  metrics: Metric[]
  printStatements: PrintStatement[]
  series: Series[]
  properties: Record<string, unknown>
  values: ConvertedGraphValue[]
  verticalLabel: string
}

export interface PrintStatement {
  format: string
  header?: string
  text?: string
  metric: string
  value: number | typeof NaN
}

export interface Series {
  color: string
  metric: string
  name: string
  type: string
  title: string
  legend?: string
}

export interface ConvertedGraphValue {
  name: string
  expression: Expression
}

export interface Expression {
  argument: string | typeof NaN
  consolidate: (metrics: GraphMetricsResponse) => any
  functionName: string
  metricName: string
}

export interface Plugin {
  extensionClass?: string
  extensionId: string
  menuEntry: string
  moduleFileName: string
  resourceRootPath: string
}

export enum SetOperator {
  Union = 1,
  Intersection = 2
}

export enum MatchType {
  Equals = 1,
  Contains = 2
}

export interface NodeQueryForeignSourceParams {
  foreignId: string
  foreignSource: string
  foreignSourceId: string
}

export interface NodeQuerySnmpParams {
  snmpIfAlias: string
  snmpIfDescription: string
  snmpIfIndex: string | number
  snmpIfName: string
  snmpIfType: string
  snmpMatchType: MatchType
}

export interface NodeQuerySysParams {
  sysContact: string
  sysDescription: string
  sysLocation: string
  sysName: string
  sysObjectId: string
}

export interface NodeQueryExtendedSearchParams {
  ipAddress?: string
  foreignSourceParams?: NodeQueryForeignSourceParams
  snmpParams?: NodeQuerySnmpParams
  sysParams?: NodeQuerySysParams
}

/** All components of a node structure query */
export interface NodeQueryFilter {
  searchTerm: string
  categoryMode: SetOperator
  selectedCategories: Category[]
  selectedFlows: string[]
  selectedMonitoringLocations: MonitoringLocation[]
  extendedSearch: NodeQueryExtendedSearchParams
}

export interface NodePreferences {
  nodeColumns?: NodeColumnSelectionItem[]
  nodeFilter?: NodeQueryFilter
}

export interface OpenNmsPreferences {
  nodePreferences: NodePreferences
  isSideMenuExpanded?: boolean
}

export interface IpInterfaceInfo {
  label: string
  managed: boolean
  primaryLabel: string
  primaryType: string
}
