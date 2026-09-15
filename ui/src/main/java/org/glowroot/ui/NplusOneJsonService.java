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
package org.glowroot.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.CharStreams;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;

import org.glowroot.common.live.ImmutableTracePointFilter;
import org.glowroot.common.live.LiveTraceRepository.TraceKind;
import org.glowroot.common.live.LiveTraceRepository.TracePoint;
import org.glowroot.common.live.LiveTraceRepository.TracePointFilter;
import org.glowroot.common.live.StringComparator;
import org.glowroot.common.model.Result;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common2.repo.ImmutableTraceQuery;
import org.glowroot.common2.repo.TraceRepository;
import org.glowroot.common2.repo.TraceRepository.TraceQuery;

/**
 * JSON service providing REST endpoints for the N+1 query detection dashboard.
 *
 * <p>Queries trace data for transactions that have been tagged with N+1 query detection
 * attributes by the agent-side JDBC plugin, and provides aggregated summaries for
 * dashboard visualization.
 */
@JsonService
class NplusOneJsonService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final TraceRepository traceRepository;
    private final Clock clock;

    NplusOneJsonService(TraceRepository traceRepository, Clock clock) {
        this.traceRepository = traceRepository;
        this.clock = clock;
    }

    /**
     * Returns a summary of N+1 detected traces within a time range.
     * Groups results by transaction name with counts and timing data.
     */
    @GET(path = "/backend/nplus-one/summary", permission = "agent:transaction:overview")
    String getSummary(@BindAgentRollupId String agentRollupId,
            @BindRequest NplusOneRequest request) throws Exception {

        TraceQuery traceQuery = ImmutableTraceQuery.builder()
                .transactionType(request.transactionType())
                .transactionName(null)
                .from(request.from())
                .to(request.to())
                .build();

        // Query for slow traces that have the n-plus-one-detected attribute
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(0)
                .durationNanosHigh(null)
                .attributeName("n-plus-one-detected")
                .attributeValueComparator(StringComparator.EQUALS)
                .attributeValue("true")
                .build();

        Result<TracePoint> result = traceRepository.readSlowPoints(agentRollupId, traceQuery,
                filter, 1000).toCompletableFuture().get();

        // Group by transaction name
        Map<String, TransactionNplusOneSummary> summaryMap =
                new HashMap<String, TransactionNplusOneSummary>();

        List<CompletableFuture<TraceRepository.HeaderPlus>> futures =
                new ArrayList<CompletableFuture<TraceRepository.HeaderPlus>>();
        for (TracePoint point : result.records()) {
            futures.add(traceRepository.readHeaderPlus(point.agentId(), point.traceId()).toCompletableFuture());
        }

        // Wait for all readHeaderPlus calls to finish
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        for (int i = 0; i < result.records().size(); i++) {
            TracePoint point = result.records().get(i);
            TraceRepository.HeaderPlus headerPlus = futures.get(i).join();
            if (headerPlus != null) {
                String txnName = headerPlus.header().getTransactionName();
                TransactionNplusOneSummary summary = summaryMap.get(txnName);
                if (summary == null) {
                    summary = new TransactionNplusOneSummary(txnName);
                    summaryMap.put(txnName, summary);
                }
                summary.addOccurrence(point.durationNanos(), point.captureTime());
            }
        }

        // Sort by occurrence count descending
        List<TransactionNplusOneSummary> summaries =
                new ArrayList<TransactionNplusOneSummary>(summaryMap.values());
        Collections.sort(summaries, new Comparator<TransactionNplusOneSummary>() {
            @Override
            public int compare(TransactionNplusOneSummary a, TransactionNplusOneSummary b) {
                return Long.compare(b.occurrenceCount, a.occurrenceCount);
            }
        });

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeStartObject();
            jg.writeNumberField("totalDetections", result.records().size());
            jg.writeNumberField("affectedTransactions", summaryMap.size());

            jg.writeArrayFieldStart("transactions");
            for (TransactionNplusOneSummary summary : summaries) {
                jg.writeStartObject();
                jg.writeStringField("transactionName", summary.transactionName);
                jg.writeNumberField("occurrenceCount", summary.occurrenceCount);
                jg.writeNumberField("totalDurationNanos", summary.totalDurationNanos);
                jg.writeNumberField("avgDurationNanos",
                        summary.occurrenceCount > 0
                                ? summary.totalDurationNanos / summary.occurrenceCount : 0);
                jg.writeNumberField("lastOccurrence", summary.lastOccurrence);
                jg.writeEndObject();
            }
            jg.writeEndArray();

            jg.writeBooleanField("moreAvailable", result.moreAvailable());
            jg.writeEndObject();
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    /**
     * Returns top N+1 offenders — transaction names ranked by occurrence count.
     */
    @GET(path = "/backend/nplus-one/top-offenders", permission = "agent:transaction:overview")
    String getTopOffenders(@BindAgentRollupId String agentRollupId,
            @BindRequest NplusOneRequest request) throws Exception {
        // Reuse the summary endpoint logic
        return getSummary(agentRollupId, request);
    }

    /**
     * Returns a timeline of N+1 occurrences over time, bucketed by configurable intervals.
     */
    @GET(path = "/backend/nplus-one/timeline", permission = "agent:transaction:overview")
    String getTimeline(@BindAgentRollupId String agentRollupId,
            @BindRequest NplusOneRequest request) throws Exception {

        TraceQuery traceQuery = ImmutableTraceQuery.builder()
                .transactionType(request.transactionType())
                .transactionName(null)
                .from(request.from())
                .to(request.to())
                .build();

        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(0)
                .durationNanosHigh(null)
                .attributeName("n-plus-one-detected")
                .attributeValueComparator(StringComparator.EQUALS)
                .attributeValue("true")
                .build();

        Result<TracePoint> result = traceRepository.readSlowPoints(agentRollupId, traceQuery,
                filter, 5000).toCompletableFuture().get();

        // Create time buckets
        long timeRange = request.to() - request.from();
        int bucketCount = Math.min(60, Math.max(1, (int) (timeRange / (60 * 1000)))); // 1-min buckets
        if (bucketCount == 0) {
            bucketCount = 1;
        }
        long bucketSize = timeRange / bucketCount;

        int[] bucketCounts = new int[bucketCount];
        for (TracePoint point : result.records()) {
            int bucketIndex = (int) ((point.captureTime() - request.from()) / bucketSize);
            if (bucketIndex >= 0 && bucketIndex < bucketCount) {
                bucketCounts[bucketIndex]++;
            }
        }

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeStartObject();
            jg.writeArrayFieldStart("dataPoints");
            for (int i = 0; i < bucketCount; i++) {
                jg.writeStartArray();
                jg.writeNumber(request.from() + (i * bucketSize) + (bucketSize / 2));
                jg.writeNumber(bucketCounts[i]);
                jg.writeEndArray();
            }
            jg.writeEndArray();
            jg.writeEndObject();
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    @Value.Immutable
    interface NplusOneRequest {
        String transactionType();
        long from();
        long to();
    }

    private static class TransactionNplusOneSummary {
        final String transactionName;
        long occurrenceCount;
        double totalDurationNanos;
        long lastOccurrence;

        TransactionNplusOneSummary(String transactionName) {
            this.transactionName = transactionName;
        }

        void addOccurrence(long durationNanos, long captureTime) {
            occurrenceCount++;
            totalDurationNanos += durationNanos;
            if (captureTime > lastOccurrence) {
                lastOccurrence = captureTime;
            }
        }
    }
}
