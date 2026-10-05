/*
 * Copyright 2026 the original author or authors.
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

/* global glowroot, angular */

glowroot.controller('AdminCassandraCtrl', [
  '$scope',
  '$http',
  '$timeout',
  'httpErrors',
  function ($scope, $http, $timeout, httpErrors) {

    $scope.loaded = false;
    $scope.loading = false;
    $scope.stats = null;
    $scope.pruneStatus = null;
    $scope.categoryFilter = 'All';
    $scope.sortField = 'estimatedBytes';
    $scope.sortReverse = true;

    $scope.pruneForm = {
      days: 7,
      updateRetentionPolicy: false
    };

    var pollPromise = null;

    $scope.categories = ['All', 'Trace', 'Aggregate', 'Gauge', 'Synthetic', 'Agent', 'System / Config', 'Other'];

    $scope.filterByCategory = function (category) {
      $scope.categoryFilter = category;
    };

    $scope.tableFilter = function (table) {
      if ($scope.categoryFilter === 'All') {
        return true;
      }
      return table.category === $scope.categoryFilter;
    };

    $scope.sortBy = function (field) {
      if ($scope.sortField === field) {
        $scope.sortReverse = !$scope.sortReverse;
      } else {
        $scope.sortField = field;
        $scope.sortReverse = (field === 'estimatedBytes' || field === 'estimatedPartitions' || field === 'recentBytesWritten');
      }
    };

    $scope.setPruneDays = function (days) {
      $scope.pruneForm.days = days;
    };

    function refreshStats() {
      $scope.loading = true;
      $http.get('backend/admin/cassandra-db-stats')
          .then(function (response) {
            $scope.loaded = true;
            $scope.loading = false;
            $scope.stats = response.data;

            // Calculate category breakdown
            var traceBytes = 0;
            var aggregateBytes = 0;
            var gaugeBytes = 0;
            var otherBytes = 0;

            angular.forEach($scope.stats.tables, function (t) {
              if (t.category === 'Trace') {
                traceBytes += t.estimatedBytes;
              } else if (t.category === 'Aggregate') {
                aggregateBytes += t.estimatedBytes;
              } else if (t.category === 'Gauge') {
                gaugeBytes += t.estimatedBytes;
              } else {
                otherBytes += t.estimatedBytes;
              }
            });

            $scope.traceBytes = traceBytes;
            $scope.aggregateBytes = aggregateBytes;
            $scope.gaugeBytes = gaugeBytes;
            $scope.otherBytes = otherBytes;

            var total = $scope.stats.totalEstimatedBytes || 1;
            $scope.tracePct = Math.min(100, (100 * traceBytes / total)).toFixed(1);
            $scope.aggregatePct = Math.min(100, (100 * aggregateBytes / total)).toFixed(1);
            $scope.gaugePct = Math.min(100, (100 * gaugeBytes / total)).toFixed(1);
          }, function (response) {
            $scope.loading = false;
            httpErrors.handle(response);
          });
    }

    $scope.refreshStats = refreshStats;

    function pollStatus() {
      $http.get('backend/admin/trace-prune-status')
          .then(function (response) {
            $scope.pruneStatus = response.data;
            if ($scope.pruneStatus && $scope.pruneStatus.running) {
              pollPromise = $timeout(pollStatus, 2000);
            } else {
              pollPromise = null;
            }
          }, function () {
            pollPromise = null;
          });
    }

    $scope.truncateAllTraces = function (deferred) {
      $http.post('backend/admin/truncate-all-traces', {})
          .then(function (response) {
            deferred.resolve('Truncated ' + response.data.truncatedCount + ' trace tables');
            refreshStats();
            pollStatus();
          }, function (response) {
            httpErrors.handle(response, deferred);
            pollStatus();
          });
    };

    $scope.pruneTracesByDays = function (deferred) {
      var days = parseInt($scope.pruneForm.days, 10);
      if (isNaN(days) || days < 0) {
        deferred.reject('Please enter a valid number of days');
        return;
      }
      $http.post('backend/admin/prune-traces-by-days', {
        days: days,
        updateRetentionPolicy: $scope.pruneForm.updateRetentionPolicy
      }).then(function () {
        deferred.resolve('Pruning started in background');
        if (pollPromise) {
          $timeout.cancel(pollPromise);
        }
        pollStatus();
      }, function (response) {
        httpErrors.handle(response, deferred);
      });
    };

    $scope.cancelTracePruning = function (deferred) {
      $http.post('backend/admin/cancel-trace-prune', {})
          .then(function () {
            deferred.resolve('Cancellation requested');
            pollStatus();
          }, function (response) {
            httpErrors.handle(response, deferred);
          });
    };

    // Initial load
    refreshStats();
    pollStatus();

    $scope.$on('$destroy', function () {
      if (pollPromise) {
        $timeout.cancel(pollPromise);
      }
    });
  }
]);

