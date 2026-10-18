/* global glowroot, angular */
glowroot.controller('AiInsightsCtrl', ['$scope', '$http', '$location', '$timeout', 'queryStrings',
  function ($scope, $http, $location, $timeout, queryStrings) {
    document.title = 'AI Insights · Glowroot';
    $scope.$parent.activeNavbarItem = 'aiInsights';
    $scope.filters = {state: '', severity: '', transaction: '', hours: 24};
    var postOptions = {headers: {'X-Glowroot-AI': '1'}};
    var timer;
    var destroyed = false;
    function query() {
      return queryStrings.encodeObject({'agent-rollup-id': $location.search()['agent-rollup-id'] ||
        $location.search()['agent-id'] || ''});
    }
    function refresh() {
      $http.get('backend/ai-insights' + query()).then(function (response) {
        $scope.data = response.data;
        $scope.error = '';
      }, function () {
        $scope.error = 'Insights unavailable. Select a leaf agent and check data permissions.';
      }).finally(function () {
        if (!destroyed) { timer = $timeout(refresh, 30000); }
      });
    }
    $scope.matches = function (i) {
      var f = $scope.filters;
      return (!f.state || i.state === f.state) && (!f.severity || i.severity === f.severity) &&
        (!f.transaction || (i.transactionType + ' ' + i.transactionName).indexOf(f.transaction) !== -1) &&
        i.lastSeen >= Date.now() - f.hours * 3600000;
    };
    $scope.action = function (i, action) {
      $http.post('backend/ai-insights/action' + query(), {id: i.id, action: action}, postOptions).then(function () {
        $scope.notice = action === 'reanalyze' ? 'Analysis requested; cooldown and queue limits apply.' : 'Acknowledged.';
        $timeout.cancel(timer); refresh();
      }, function () { $scope.error = 'Action failed. Check permissions or refresh and retry.'; });
    };
    $scope.link = function (i, screen) {
      return screen + queryStrings.encodeObject({'agent-id': i.agentId,
        'transaction-type': i.transactionType, 'transaction-name': i.transactionName || undefined,
        from: i.evidence.from, to: i.evidence.to});
    };
    $scope.loadConfig = function () {
      $http.get('backend/admin/ai-insights').then(function (r) {
        $scope.configText = angular.toJson(r.data.config, true);
        $scope.testStatus = r.data.testStatus;
      }, function () { $scope.error = 'Administrator edit permission is required.'; });
    };
    $scope.saveConfig = function () {
      var config;
      try { config = angular.fromJson($scope.configText); }
      catch (e) { $scope.error = 'Invalid configuration JSON.'; return; }
      $http.post('backend/admin/ai-insights', config, postOptions).then(function () {
        $scope.notice = 'Configuration saved. Restart central nodes after changing queue capacity or concurrency.';
      }, function () { $scope.error = 'Configuration rejected. Check values and administrator permission.'; });
    };
    $scope.test = function () {
      $http.post('backend/admin/ai-insights/test', {}, postOptions).then(function (r) {
        $scope.testStatus = r.data.status;
      }, function () { $scope.testStatus = 'unavailable'; });
    };
    $scope.$on('$destroy', function () { destroyed = true; $timeout.cancel(timer); });
    $scope.selectedAgent = $location.search()['agent-rollup-id'] || $location.search()['agent-id'] || '';
    $scope.selectAgent = function () {
      $location.search('agent-id', $scope.selectedAgent).search('agent-rollup-id', null);
    };
    refresh();
  }]);
