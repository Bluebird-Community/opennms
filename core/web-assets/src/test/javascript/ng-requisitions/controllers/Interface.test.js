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
/*global RequisitionNode:true */

/**
* @author Alejandro Galue <agalue@opennms.org>
* @copyright 2014 The OpenNMS Group, Inc.
*/

'use strict';

const angular = require('angular-js');
require('angular-mocks');
require('../../../../../src/main/assets/js/apps/onms-requisitions/requisitions');

const RequisitionNode = require('../../../../../src/main/assets/js/apps/onms-requisitions/lib/scripts/model/RequisitionNode');

var scope, $q, controllerFactory, mockModalInstance, mockRequisitionsService = {};

var foreignSource = 'test-requisition';
var foreignId = '1001';
var node = new RequisitionNode(foreignSource, { 'foreign-id': foreignId });
var services = ['ICMP', 'SNMP', 'HTTP'];
node.addNewInterface();
node.interfaces[0].ipAddress = '10.0.0.1';

function createController() {
  return controllerFactory('InterfaceController', {
    $scope: scope,
    $uibModalInstance: mockModalInstance,
    RequisitionsService: mockRequisitionsService,
    foreignSource: foreignSource,
    foreignId: foreignId,
    requisitionInterface: node.interfaces[0],
    primaryInterface: '10.0.0.1',
    ipBlackList: []
  });
}

beforeEach(angular.mock.module('onms-requisitions', function($provide) {
  $provide.value('$log', console);
}));

beforeEach(angular.mock.inject(function($rootScope, $controller, _$q_) {
  scope = $rootScope.$new();
  controllerFactory = $controller;
  $q = _$q_;
}));

beforeEach(function() {
  mockRequisitionsService.getAvailableServices = jasmine.createSpy('getAvailableServices');
  var servicesDefer = $q.defer();
  servicesDefer.resolve(services);
  mockRequisitionsService.getAvailableServices.and.returnValue(servicesDefer.promise);

  mockModalInstance = {
    close: function(obj) { console.info(obj); },
    dismiss: function(msg) { console.info(msg); }
  };
});

test('Controller: InterfaceController: test controller', function() {
  createController();
  scope.$digest();
  expect(scope.requisitionInterface.ipAddress).toBe(node.interfaces[0].ipAddress);
  expect(scope.snmpPrimaryFields[0].title).toBe('Primary');
  scope.addService();
  expect(scope.requisitionInterface.services.length).toBe(1);
  scope.removeService(0);
  expect(scope.requisitionInterface.services.length).toBe(0);
  expect(scope.availableServices.length).toBe(3);
  expect(scope.availableServices[0]).toBe('ICMP');

  expect(scope.getAvailableServices()).toEqual(['ICMP','SNMP','HTTP']);
  scope.requisitionInterface.services.push({name: 'ICMP'});
  expect(scope.getAvailableServices()).toEqual(['SNMP','HTTP']);
});
