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
package org.glowroot.common2.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.glowroot.common.live.ImmutableAggregateQuery;
import org.glowroot.common.live.ImmutableTracePointFilter;
import org.glowroot.common.live.LiveAggregateRepository.AggregateQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.live.StringComparator;
import org.glowroot.common.model.MutableQuery;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.model.MutableServiceCall;
import org.glowroot.common.model.TransactionNameSummaryCollector;
import org.glowroot.common.model.TransactionNameSummaryCollector.SummarySortOrder;
import org.glowroot.common.live.ImmutableSummaryQuery;
import org.glowroot.common2.repo.GaugeValueRepository;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage.GaugeValue;
import org.glowroot.common2.repo.AggregateRepository;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ImmutableTraceQuery;
import org.glowroot.common2.repo.TraceRepository;

/** Reads stored minute aggregates only, never live application data. */
public final class InsightsTelemetry {
    private final AggregateRepository aggregates;
    private final TraceRepository traces;
    private final GaugeValueRepository gauges;
    private final org.glowroot.common2.repo.ConfigRepository configs;
    public InsightsTelemetry(AggregateRepository aggregates, TraceRepository traces, GaugeValueRepository gauges, org.glowroot.common2.repo.ConfigRepository configs) {
        this.aggregates = aggregates; this.traces = traces; this.gauges = gauges; this.configs = configs;
    }
    public List<LocalDetector.Signal> read(String agent, String type, String name,
            long to, InsightsConfig c) throws Exception {
        long from = to - c.recentMinutes * 60000L;
        long baselineFrom = from - c.baselineMinutes * 60000L;
        AggregateQuery recent = query(type, name, from, to);
        AggregateQuery prior = query(type, name, baselineFrom, from);
        Window current = window(agent, recent);
        Window baseline = window(agent, prior);
        boolean covered = current.points >= Math.max(1, c.recentMinutes * 0.8)
                && baseline.points >= c.baselineMinutes * 0.8;
        List<LocalDetector.Signal> result = new ArrayList<LocalDetector.Signal>();
        result.add(LocalDetector.compare("error-rate", "", from, to,
                current.count == 0 ? 0 : (double) current.errors / current.count,
                baseline.count == 0 ? 0 : (double) baseline.errors / baseline.count,
                current.count, baseline.count, c.errorRate, c.baselineMultiplier,
                c.minimumSamples, covered && current.errorsKnown && baseline.errorsKnown, false));
        result.add(LocalDetector.compare("mean-latency-ms", "", from, to,
                current.mean(), baseline.mean(), current.count, baseline.count,
                c.latencyMillis, c.baselineMultiplier, c.minimumSamples, covered, false));
        result.add(LocalDetector.compare("throughput-drop", "", from, to,
                (double) current.count / c.recentMinutes,
                (double) baseline.count / c.baselineMinutes,
                current.points, baseline.count, c.throughputDropRatio, 1, 1,
                covered && c.expectedTraffic && baseline.count >= c.minimumSamples, true));
        try { addServices(result, agent, recent, prior, c, covered); }
        catch (Exception unavailable) {
            result.add(LocalDetector.compare("external-mean-latency-ms", "", from, to,
                    0, 0, 0, 0, c.queryMillis, c.baselineMultiplier, 1, false, false));
        }
        Map<String, double[]> nowQueries = new HashMap<String, double[]>();
        Map<String, double[]> oldQueries = new HashMap<String, double[]>();
        try { nowQueries = queries(agent, recent); oldQueries = queries(agent, prior); }
        catch (Exception unavailable) { nowQueries.clear(); oldQueries.clear(); }
        if (nowQueries.isEmpty()) result.add(LocalDetector.compare("slow-sql-ms", "", from, to,
                0, 0, 0, 0, c.queryMillis, c.baselineMultiplier, 1, false, false));
        // Include baseline identities so disappeared queries become insufficient, not healthy.
        Map<String, double[]> union = new HashMap<String, double[]>(oldQueries);
        union.putAll(nowQueries);
        for (String identity : union.keySet()) {
            double[] n = nowQueries.get(identity), b = oldQueries.get(identity);
            result.add(LocalDetector.compare("slow-sql-ms", identity, from, to,
                    n == null ? 0 : n[0] / n[1], b == null ? 0 : b[0] / b[1],
                    n == null ? 0 : (long) n[1], b == null ? 0 : (long) b[1],
                    c.queryMillis, c.baselineMultiplier, c.minimumQuerySamples,
                    covered && n != null && b != null, false));
        }
        boolean nplusAvailable = true;
        org.glowroot.common.model.Result<org.glowroot.common.live.LiveTraceRepository.TracePoint> nplusPoints;
        try {
            nplusPoints = await(traces.readSlowPoints(agent, ImmutableTraceQuery.builder()
                .transactionType(type).transactionName(name.isEmpty() ? null : name)
                .from(from).to(to).build(), ImmutableTracePointFilter.builder()
                .durationNanosLow(0).durationNanosHigh(null)
                .attributeName("n-plus-one-detected")
                .attributeValueComparator(StringComparator.EQUALS).attributeValue("true")
                .build(), 100));
        } catch (Exception unavailable) {
            nplusAvailable = false;
            nplusPoints = new org.glowroot.common.model.Result<org.glowroot.common.live.LiveTraceRepository.TracePoint>(
                    java.util.Collections.emptyList(), false);
        }
        long nplus = nplusPoints.records().size();
        boolean enabled = nplus > 0;
        try { enabled |= nplusEnabled(agent); }
        catch (Exception unavailable) { /* absent config is insufficient, never healthy */ }
        // Existing JDBC detector supplies the finding; no baseline ratio is inferred for N+1.
        result.add(LocalDetector.compare("captured-n-plus-one", "", from, to,
                nplus, 0, current.count, baseline.count, 1, 1, c.minimumSamples,
                covered && nplusAvailable && enabled, false));
        com.fasterxml.jackson.databind.node.ArrayNode representative = org.glowroot.common.util.ObjectMappers.create().createArrayNode();
        // Read at most three captured trace headers. Messages, attributes, SQL, request
        // bodies and binds are never copied. Frames are opaque identities, not raw text.
        List<org.glowroot.common.live.LiveTraceRepository.TracePoint> representativePoints =
                new ArrayList<org.glowroot.common.live.LiveTraceRepository.TracePoint>(nplusPoints.records());
        if (result.get(0).result == LocalDetector.Result.ANOMALY && representativePoints.size() < 3) {
            try {
                representativePoints.addAll(await(traces.readErrorPoints(agent, ImmutableTraceQuery.builder()
                        .transactionType(type).transactionName(name.isEmpty() ? null : name)
                        .from(from).to(to).build(), ImmutableTracePointFilter.builder()
                        .durationNanosLow(0).durationNanosHigh(null).build(), 3)).records());
            } catch (Exception unavailable) { /* optional trace enrichment */ }
        }
        int read = 0;
        for (org.glowroot.common.live.LiveTraceRepository.TracePoint point : representativePoints) {
            if (++read > 3) break;
            if (!point.agentId().equals(agent)) continue;
            com.fasterxml.jackson.databind.node.ObjectNode trace = representative.addObject();
            trace.put("trace", Evidence.id(point.agentId(), point.traceId()));
            trace.put("durationNanos", point.durationNanos()); trace.put("captureTime", point.captureTime());
            TraceRepository.HeaderPlus header;
            try { header = await(traces.readHeaderPlus(agent, point.traceId())); }
            catch (Exception unavailable) { continue; }
            if (header != null && header.header().hasError()) {
                org.glowroot.wire.api.model.Proto.Throwable error = header.header().getError().getException();
                trace.put("exceptionIdentity", Evidence.id(error.getClassName()));
                com.fasterxml.jackson.databind.node.ArrayNode frames = trace.putArray("stackFrameIdentities");
                int frameCount = 0;
                for (org.glowroot.wire.api.model.Proto.StackTraceElement frame : error.getStackTraceElementList()) {
                    if (++frameCount > 10) break;
                    frames.add(Evidence.id(frame.getClassName(), frame.getMethodName()));
                }
            }
        }
        for (LocalDetector.Signal signal : result) {
            signal.evidence.set("capturedTraces", representative);
            signal.evidence.put("capturedNplusOneCountCappedAt100", nplus);
            signal.evidence.put("baselineFrom", baselineFrom);
            signal.evidence.put("baselineTo", from);
            signal.evidence.put("resolutionMillis", 60000);
            signal.evidence.put("recentPoints", current.points);
            signal.evidence.put("baselinePoints", baseline.points);
        }
        return result;
    }
    private boolean nplusEnabled(String agent) throws Exception {
        for (org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig plugin : configs.getPluginConfigs(agent)) {
            if (!plugin.getId().equals("jdbc")) continue;
            for (org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty property : plugin.getPropertyList()) {
                if (property.getName().equals("detectNplusOneQueries")) return property.getValue().getBval();
            }
        }
        return false;
    }
    public List<String> transactions(String agent, String type, long to, InsightsConfig c) throws Exception {
        TransactionNameSummaryCollector collector = new TransactionNameSummaryCollector();
        await(aggregates.mergeTransactionNameSummariesInto(agent, ImmutableSummaryQuery.builder()
                .transactionType(type).from(to - (c.recentMinutes + c.baselineMinutes) * 60000L)
                .to(to).rollupLevel(0).build(), SummarySortOrder.THROUGHPUT, 20,
                collector, CassandraProfile.rollup));
        List<String> names = new ArrayList<String>();
        for (TransactionNameSummaryCollector.TransactionNameSummary item : collector.getResult(
                SummarySortOrder.THROUGHPUT, 20).records()) names.add(item.transactionName());
        return names;
    }
    private Map<String, double[]> services(String agent, AggregateQuery query) throws Exception {
        ServiceCallCollector collector = new ServiceCallCollector(25);
        await(aggregates.mergeServiceCallsInto(agent, query, collector, CassandraProfile.rollup));
        Map<String, double[]> values = new HashMap<String, double[]>();
        for (MutableServiceCall call : collector.getSortedAndTruncatedServiceCalls()) {
            if (call.getExecutionCount() > 0 && !call.getText().equals("LIMIT EXCEEDED BUCKET")) {
                // URL values, path identifiers and all service text remain local.
                String key = Evidence.id(call.getType(), call.getText().replaceAll("[?].*", ""));
                values.put(key, new double[] {call.getTotalDurationNanos() / 1000000 / call.getExecutionCount(),
                        call.getExecutionCount()});
            }
        }
        return values;
    }
    private void addServices(List<LocalDetector.Signal> result, String agent, AggregateQuery recent,
            AggregateQuery prior, InsightsConfig c, boolean covered) throws Exception {
        Map<String, double[]> now = services(agent, recent), old = services(agent, prior);
        for (Map.Entry<String, double[]> entry : now.entrySet()) {
            double[] n = entry.getValue(), b = old.get(entry.getKey());
            result.add(LocalDetector.compare("external-mean-latency-ms", entry.getKey(), recent.from(),
                    recent.to(), n[0], b == null ? 0 : b[0], (long) n[1], b == null ? 0 : (long) b[1],
                    c.queryMillis, c.baselineMultiplier, c.minimumQuerySamples, covered && b != null, false));
        }
    }
    public List<LocalDetector.Signal> jvm(String agent, long to, InsightsConfig c) throws Exception {
        long from = to - c.recentMinutes * 60000L;
        long baselineFrom = from - c.baselineMinutes * 60000L;
        List<LocalDetector.Signal> signals = new ArrayList<LocalDetector.Signal>();
        String[] names = {"java.lang:type=OperatingSystem:ProcessCpuLoad",
                "java.lang:type=Threading:ThreadCount", "java.lang:type=Memory:HeapMemoryUsage.used"};
        String[] rules = {"cpu-saturation", "thread-growth", "heap-pressure"};
        double[] thresholds = {c.cpuRatio, c.threadCount, c.heapRatio};
        for (int i = 0; i < names.length; i++) {
            double[] n = gauge(agent, names[i], from, to), b = gauge(agent, names[i], baselineFrom, from);
            if (i == 2) {
                double[] max = gauge(agent, "java.lang:type=Memory:HeapMemoryUsage.max", from, to);
                double[] oldMax = gauge(agent, "java.lang:type=Memory:HeapMemoryUsage.max", baselineFrom, from);
                if (max[0] <= 0 || oldMax[0] <= 0) { n[1] = 0; b[1] = 0; }
                else { n[0] /= max[0]; b[0] /= oldMax[0]; }
            }
            signals.add(LocalDetector.compare(rules[i], "", from, to, n[0], b[0], (long) n[1],
                    (long) b[1], thresholds[i], i == 1 ? c.baselineMultiplier : 1,
                    1, n[2] >= c.recentMinutes * 0.8 && b[2] >= c.baselineMinutes * 0.8, false));
        }
        boolean hasGc = false;
        int gcCollectors = 0;
        for (GaugeValueRepository.Gauge g : await(gauges.getRecentlyActiveGauges(agent))) {
            if (!g.name().startsWith("java.lang:type=GarbageCollector,")
                    || !g.name().endsWith(":CollectionTime[counter]")) continue;
            if (++gcCollectors > 5) break;
            hasGc = true;
            double[] n = gauge(agent, g.name(), from, to), b = gauge(agent, g.name(), baselineFrom, from);
            signals.add(LocalDetector.compare("gc-time-ms-per-second", Evidence.id(g.name()), from, to,
                    n[0], b[0], (long) n[1], (long) b[1], c.gcMillisPerSecond, c.baselineMultiplier,
                    1, n[2] >= c.recentMinutes * 0.8 && b[2] >= c.baselineMinutes * 0.8, false));
        }
        if (!hasGc) signals.add(LocalDetector.compare("gc-time-ms-per-second", "", from, to,
                0, 0, 0, 0, c.gcMillisPerSecond, c.baselineMultiplier, 1, false, false));
        for (LocalDetector.Signal signal : signals) {
            signal.evidence.put("baselineFrom", baselineFrom); signal.evidence.put("baselineTo", from);
        }
        return signals;
    }
    private double[] gauge(String agent, String name, long from, long to) throws Exception {
        double total = 0, weight = 0;
        long count = 0;
        java.util.Set<Long> minutes = new java.util.HashSet<Long>();
        for (GaugeValue value : await(gauges.readGaugeValues(agent, name, from + 1, to,
                0, CassandraProfile.rollup))) {
            // Agent stores counter deltas/sec. A negative delta indicates reset; invalidate the window.
            if (!Double.isFinite(value.getValue()) || value.getValue() < 0) return new double[3];
            if (value.getWeight() <= 0) continue;
            total += value.getValue() * value.getWeight(); weight += value.getWeight(); count++;
            minutes.add(value.getCaptureTime() / 60000);
        }
        return new double[] {weight == 0 ? 0 : total / weight, count, minutes.size()};
    }
    private Window window(String agent, AggregateQuery query) throws Exception {
        Window w = new Window();
        for (OverviewAggregate a : await(aggregates.readOverviewAggregates(agent, query,
                CassandraProfile.rollup))) {
            if (a.captureTime() <= query.from()) continue;
            w.duration += a.totalDurationNanos(); w.count += a.transactionCount(); w.points++;
        }
        long throughputCount = 0;
        for (ThroughputAggregate a : await(aggregates.readThroughputAggregates(agent, query,
                CassandraProfile.rollup))) {
            if (a.captureTime() <= query.from()) continue;
            throughputCount += a.transactionCount();
            if (a.errorCount() == null) w.errorsKnown = false;
            else w.errors += a.errorCount();
        }
        w.errorsKnown &= throughputCount == w.count && w.count > 0;
        return w;
    }
    private Map<String, double[]> queries(String agent, AggregateQuery query) throws Exception {
        QueryCollector collector = new QueryCollector(25);
        await(aggregates.mergeQueriesInto(agent, query, collector, CassandraProfile.rollup));
        Map<String, double[]> result = new HashMap<String, double[]>();
        for (MutableQuery q : collector.getSortedAndTruncatedQueries()) {
            if (!q.getType().equals("SQL") || q.getExecutionCount() <= 0
                    || q.getTruncatedText().equals("LIMIT EXCEEDED BUCKET")) continue;
            String identity = Evidence.sqlIdentity(q.getTruncatedText());
            double[] values = result.get(identity);
            if (values == null) { values = new double[2]; result.put(identity, values); }
            values[0] += q.getTotalDurationNanos() / 1000000;
            values[1] += q.getExecutionCount();
        }
        return result;
    }
    private static AggregateQuery query(String type, String name, long from, long to) {
        return ImmutableAggregateQuery.builder().transactionType(type)
                .transactionName(name.isEmpty() ? null : name).from(from).to(to).rollupLevel(0).build();
    }
    public static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
    private static class Window {
        long count, errors;
        int points;
        double duration;
        boolean errorsKnown = true;
        double mean() { return count == 0 ? 0 : duration / count / 1000000; }
    }
}
