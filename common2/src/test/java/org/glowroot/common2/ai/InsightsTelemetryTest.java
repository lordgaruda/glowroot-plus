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

import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.glowroot.common.live.*;
import org.glowroot.common.live.LiveAggregateRepository.*;
import org.glowroot.common.model.Result;
import org.glowroot.common2.repo.*;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage.GaugeValue;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InsightsTelemetryTest {
    @Test void disjointWindowsUseCountsAndStoredDurations() throws Exception {
        AggregateRepository aggregates = mock(AggregateRepository.class);
        TraceRepository traces = mock(TraceRepository.class);
        GaugeValueRepository gauges = mock(GaugeValueRepository.class);
        ConfigRepository configs = mock(ConfigRepository.class);
        when(configs.getPluginConfigs("a")).thenReturn(Collections.emptyList());
        when(aggregates.readOverviewAggregates(eq("a"), any(), any())).thenAnswer(invocation -> {
            AggregateQuery q = invocation.getArgument(1);
            List<OverviewAggregate> values = new ArrayList<>();
            for (long t = q.from() + 60000; t <= q.to(); t += 60000) {
                values.add(ImmutableOverviewAggregate.builder().captureTime(t).transactionCount(100)
                        .totalDurationNanos(t > 3600000 ? 200000000000.0 : 10000000000.0)
                        .asyncTransactions(false).mainThreadStats(Aggregate.ThreadStats.getDefaultInstance()).build());
            }
            return CompletableFuture.completedFuture(values);
        });
        when(aggregates.readThroughputAggregates(eq("a"), any(), any())).thenAnswer(invocation -> {
            AggregateQuery q = invocation.getArgument(1); List<ThroughputAggregate> values = new ArrayList<>();
            for (long t = q.from() + 60000; t <= q.to(); t += 60000) {
                values.add(ImmutableThroughputAggregate.builder().captureTime(t).transactionCount(100)
                        .errorCount(t > 3600000 ? 10L : 1L).build());
            }
            return CompletableFuture.completedFuture(values);
        });
        when(aggregates.mergeQueriesInto(anyString(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(aggregates.mergeServiceCallsInto(anyString(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(traces.readSlowPoints(anyString(), any(), any(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(new Result<>(Collections.emptyList(), false)));
        List<LocalDetector.Signal> signals = new InsightsTelemetry(aggregates, traces, gauges, configs)
                .read("a", "Web", "", 3900000, new InsightsConfig());
        assertThat(signals.get(0).result).isEqualTo(LocalDetector.Result.ANOMALY);
        assertThat(signals.get(0).evidence.get("samples").asLong()).isEqualTo(500);
        assertThat(signals.get(0).evidence.get("baselineSamples").asLong()).isEqualTo(6000);
        assertThat(signals.get(1).evidence.get("current").asDouble()).isEqualTo(2000);
        assertThat(signals.get(1).evidence.get("baseline").asDouble()).isEqualTo(100);
        assertThat(signals.get(signals.size() - 1).result).isEqualTo(LocalDetector.Result.INSUFFICIENT);
    }
    @Test void counterResetAndAbsentGaugesAreInsufficient() throws Exception {
        GaugeValueRepository gauges = mock(GaugeValueRepository.class);
        when(gauges.getRecentlyActiveGauges("a")).thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        when(gauges.readGaugeValues(eq("a"), anyString(), anyLong(), anyLong(), anyInt(), any()))
                .thenReturn(CompletableFuture.completedFuture(Collections.singletonList(GaugeValue.newBuilder()
                        .setCaptureTime(60000).setValue(-1).setWeight(1).build())));
        List<LocalDetector.Signal> signals = new InsightsTelemetry(null, null, gauges, null)
                .jvm("a", 3900000, new InsightsConfig());
        assertThat(signals).hasSize(4).allMatch(signal -> signal.result == LocalDetector.Result.INSUFFICIENT);
    }
}
