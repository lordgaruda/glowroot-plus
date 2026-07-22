/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* global glowroot, angular, $, moment */

glowroot.controller('NplusOneCtrl', [
  '$scope',
  '$location',
  '$filter',
  '$http',
  '$timeout',
  'locationChanges',
  'charts',
  'queryStrings',
  function ($scope, $location, $filter, $http, $timeout, locationChanges, charts, queryStrings) {

    document.title = 'N+1 Queries \u00b7 Glowroot';
    $scope.$parent.activeNavbarItem = 'nplusOne';

    var chartState = charts.createState();

    var chartOptions = {
      tooltip: true,
      yaxis: {
        label: 'detections',
        tickDecimals: 0
      },
      series: {
        stack: false,
        lines: {
          fill: true,
          fillColor: 'rgba(217, 83, 79, 0.1)'
        }
      },
      tooltipOpts: {
        content: function (label, xval, yval) {
          var tooltip = '<table class="gt-chart-tooltip">';
          tooltip += '<tr><td colspan="2" style="font-weight: 600;">' + label;
          tooltip += '</td></tr><tr><td style="padding-right: 10px;">Time:</td><td style="font-weight: 400;">';
          tooltip += moment(xval).format('h:mm:ss a (Z)') + '</td></tr>';
          tooltip += '<tr><td style="padding-right: 10px;">Detections:</td><td style="font-weight: 600;">';
          tooltip += yval + '</td></tr>';
          tooltip += '</table>';
          return tooltip;
        }
      }
    };

    $scope.chartNoData = true;

    $scope.currentTabUrl = function () {
      return 'nplus-one';
    };

    $scope.range = {};

    $scope.agentRollupUrl = function (agentRollupId) {
      var query = $scope.agentRollupQuery(agentRollupId);
      return $location.path().substring(1) + queryStrings.encodeObject(query);
    };

    $scope.buildQueryObjectForChartRange = function (last) {
      return buildQueryObject(last);
    };

    function buildQueryObject(last) {
      var query = {};
      var agentId = $location.search()['agent-id'];
      if (agentId) {
        query['agent-id'] = agentId;
      } else {
        query['agent-rollup-id'] = $location.search()['agent-rollup-id'] || '';
      }
      var transactionType = $location.search()['transaction-type'];
      if (transactionType) {
        query['transaction-type'] = transactionType;
      }
      if (!last) {
        query.from = $scope.range.chartFrom;
        query.to = $scope.range.chartTo;
      } else if (last !== 4 * 60 * 60 * 1000) {
        query.last = last;
      }
      return query;
    }

    $scope.hideMainContent = function () {
      return $scope.layout.central && !$scope.agentRollupId && !$scope.agentId;
    };

    function buildActiveQueryObject() {
      var query = {};
      query.agentRollupId = $scope.agentRollupId;

      var transactionType = $location.search()['transaction-type'];
      if (!transactionType) {
        var transactionTypes = $scope.agentRollup ? $scope.agentRollup.transactionTypes : [];
        if (transactionTypes && transactionTypes.length) {
          transactionType = transactionTypes[0];
        } else {
          transactionType = 'Web';
        }
      }
      query['transaction-type'] = transactionType;
      $scope.transactionType = transactionType;

      query.from = $scope.range.chartFrom;
      query.to = $scope.range.chartTo;

      return query;
    }

    function refreshData(autoRefresh) {
      if ($scope.hideMainContent()) {
        return;
      }
      $scope.showSpinner = true;
      var activeQuery = buildActiveQueryObject();

      // Fetch summary statistics and list of offending transactions
      $http.get('backend/nplus-one/summary' + queryStrings.encodeObject(activeQuery))
        .then(function (response) {
          $scope.showSpinner = false;
          $scope.loaded = true;
          $scope.summary = response.data;
        }, function (response) {
          $scope.showSpinner = false;
          $scope.httpError = true;
        });

      // Fetch timeline data for chart
      $scope.showChartSpinner = true;
      $http.get('backend/nplus-one/timeline' + queryStrings.encodeObject(activeQuery))
        .then(function (response) {
          $scope.showChartSpinner = false;
          var dataPoints = response.data.dataPoints;
          $scope.chartNoData = !dataPoints || dataPoints.length === 0 ||
            dataPoints.every(function (dp) { return dp[1] === 0; });

          var plotData = [{
            data: dataPoints,
            label: 'N+1 Detections',
            color: '#d9534f' // Red color for issues
          }];
          charts.plot(plotData, chartOptions, chartState, $('#chart'), $scope);
        }, function (response) {
          $scope.showChartSpinner = false;
        });
    }

    $scope.clickTransaction = function (transaction) {
      // Redirect the user to trace search filtered by N+1 for this transaction
      var query = buildQueryObject($scope.range.last);
      var traceSearchQuery = {
        'agent-id': query['agent-id'],
        'agent-rollup-id': query['agent-rollup-id'],
        'transaction-type': query['transaction-type'],
        'transaction-name': transaction.transactionName,
        'last': query.last,
        'from': query.from,
        'to': query.to,
        'attribute-name': 'n-plus-one-detected',
        'attribute-value-comparator': 'EQUALS',
        'attribute-value': 'true'
      };
      $location.url('transaction/traces' + queryStrings.encodeObject(traceSearchQuery));
    };
    $scope.$watch('[range.chartFrom, range.chartTo, range.chartRefresh, range.chartAutoRefresh]',
        function (newValues, oldValues) {
          var autoRefresh = newValues[3] !== oldValues[3];
          refreshData(autoRefresh);
        });

    var priorLocation;
    locationChanges.on($scope, function () {
      var location = {};
      location.last = Number($location.search().last);
      location.chartFrom = Number($location.search().from);
      location.chartTo = Number($location.search().to);
      if (!isNaN(location.chartFrom) && !isNaN(location.chartTo)) {
        location.last = 0;
      } else if (!location.last) {
        location.last = 4 * 60 * 60 * 1000;
      }
      location.transactionType = $location.search()['transaction-type'];

      if (!priorLocation || !angular.equals(location, priorLocation)) {
        $scope.range.last = location.last;
        $scope.range.chartFrom = location.chartFrom;
        $scope.range.chartTo = location.chartTo;
        $scope.transactionType = location.transactionType;
        charts.applyLast($scope);
        $scope.range.chartRefresh++;
        priorLocation = location;
      }
    });


    charts.init(chartState, $('#chart'), $scope);
    charts.plot([[]], chartOptions, chartState, $('#chart'), $scope);
    charts.initResize(chartState.plot, $scope);
    charts.startAutoRefresh($scope, 60000);

    $scope.$watchGroup(['range.chartFrom', 'range.chartTo'], function (newValue, oldValue) {
      if (newValue !== oldValue) {
        $timeout(function () {
          $('#topLevelAgentRollupDropdown').selectpicker('refresh');
          $('#childAgentRollupDropdown').selectpicker('refresh');
        });
      }
    });

    var refreshTopLevelAgentRollups = function () {
      $scope.refreshTopLevelAgentRollups($scope.range.chartFrom, $scope.range.chartTo);
    };
    var refreshChildAgentRollups = function () {
      $scope.refreshChildAgentRollups($scope.range.chartFrom, $scope.range.chartTo);
    };

    $('#topLevelAgentRollupDropdown').on('show.bs.select', refreshTopLevelAgentRollups);
    $('#childAgentRollupDropdown').on('show.bs.select', refreshChildAgentRollups);

    if ($scope.topLevelAgentRollups === undefined) {
      refreshTopLevelAgentRollups();
      refreshChildAgentRollups();
    }
  }
]);
