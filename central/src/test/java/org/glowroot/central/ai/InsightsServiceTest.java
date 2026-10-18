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
package org.glowroot.central.ai;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common2.ai.*;
import org.glowroot.common2.repo.ActiveAgentRepository;
import org.glowroot.common2.repo.TransactionTypeRepository;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class InsightsServiceTest {
    static final class Store implements InsightsRepository {
        final Map<String, Document> documents = new HashMap<>();
        public synchronized Document read(String a, String id) { return documents.get(a + ":" + id); }
        public synchronized List<Document> list(String a) {
            List<Document> list = new ArrayList<>();
            documents.forEach((key, value) -> { if (key.startsWith(a + ":")) list.add(value); });
            return list;
        }
        public synchronized boolean compareAndSet(String a, String id, String revision, String json, int ttl) {
            Document old = read(a, id);
            if (!Objects.equals(old == null ? null : old.revision, revision)) return false;
            documents.put(a + ":" + id, new Document(UUID.randomUUID().toString(), json)); return true;
        }
    }
    @Test void queueOverloadDedupAndCrossAgentIsolation() throws Exception {
        Store store = new Store();
        InsightsConfig c = new InsightsConfig();
        c.enabled = true; c.analysisEnabled = true; c.model = "mock";
        c.concurrency = 1; c.queueCapacity = 1; c.sustainedEvaluations = 1;
        store.compareAndSet("@ai-settings", "config", null, ObjectMappers.create().writeValueAsString(c), 0);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AnalysisProvider provider = (e, config) -> { calls.incrementAndGet(); entered.countDown();
            release.await(5, TimeUnit.SECONDS); throw new IllegalArgumentException("malformed"); };
        try (InsightsService service = new InsightsService(store, null, mock(ActiveAgentRepository.class),
                mock(TransactionTypeRepository.class), provider, provider)) {
            for (int n = 0; n < 3; n++) service.record("agent-a", "Web", "txn" + n,
                    LocalDetector.compare("latency", "", 0, 60000, 100, 10, 100, 100,
                            50, 2, 100, true, false), c);
            List<Insight> incidents = service.list("agent-a");
            service.action("agent-a", incidents.get(0).id, "reanalyze");
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            service.action("agent-a", incidents.get(0).id, "reanalyze");
            service.action("agent-a", incidents.get(1).id, "reanalyze");
            service.action("agent-a", incidents.get(2).id, "reanalyze");
            assertThat(service.list("agent-a")).anyMatch(i -> i.analysisError.equals("queue full"));
            assertThat(service.list("agent-b")).isEmpty();
            assertThatThrownBy(() -> service.action("agent-b", incidents.get(0).id, "acknowledge"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(calls.get()).isEqualTo(1);
            release.countDown();
        }
    }
    @Test void duplicateEvaluationsDoNotCountTwiceAcrossNodes() throws Exception {
        Store store = new Store(); InsightsConfig c = new InsightsConfig(); c.sustainedEvaluations = 1;
        AnalysisProvider provider = (e, config) -> { throw new AssertionError("disabled"); };
        try (InsightsService a = new InsightsService(store, null, null, null, provider, provider);
             InsightsService b = new InsightsService(store, null, null, null, provider, provider)) {
            LocalDetector.Signal signal = LocalDetector.compare("latency", "", 0, 60000,
                    100, 10, 100, 100, 50, 2, 100, true, false);
            a.record("a", "Web", "", signal, c); b.record("a", "Web", "", signal, c);
            assertThat(a.list("a")).hasSize(1);
            assertThat(a.list("a").get(0).occurrences).isEqualTo(1);
        }
    }
    @Test void retriesConsumeSharedBudgetAndCircuitStopsOutages() throws Exception {
        Store store = new Store(); AtomicInteger calls = new AtomicInteger();
        AnalysisProvider unavailable = (e, config) -> {
            calls.incrementAndGet();
            throw new HttpAnalysisProvider.ProviderException("outage", true, 0);
        };
        InsightsConfig c = new InsightsConfig(); c.requestsPerHour = 2;
        com.fasterxml.jackson.databind.node.ObjectNode evidence = ObjectMappers.create().createObjectNode().put("id", "e1");
        try (InsightsService service = new InsightsService(store, null, null, null, unavailable, unavailable)) {
            assertThatThrownBy(() -> service.analyze(evidence, c)).hasMessage("hourly request budget exhausted");
            assertThat(calls.get()).isEqualTo(2);
            for (int n = 0; n < 2; n++) assertThatThrownBy(() -> service.analyze(evidence, c)).isInstanceOf(Exception.class);
            assertThatThrownBy(() -> service.analyze(evidence, c)).hasMessage("provider circuit open");
            assertThat(calls.get()).isEqualTo(2);
        }
    }
    @Test void fallbackRequiresExplicitOptIn() throws Exception {
        Store store = new Store(); AtomicInteger fallbackCalls = new AtomicInteger();
        AnalysisProvider failed = (e, c) -> { throw new IllegalArgumentException("invalid"); };
        AnalysisProvider fallback = (e, c) -> { fallbackCalls.incrementAndGet();
            assertThat(c.model).isEqualTo("fallback-model"); return ObjectMappers.create().createObjectNode(); };
        InsightsConfig c = new InsightsConfig();
        com.fasterxml.jackson.databind.node.ObjectNode evidence = ObjectMappers.create().createObjectNode().put("id", "e1");
        try (InsightsService service = new InsightsService(store, null, null, null, failed, fallback)) {
            assertThatThrownBy(() -> service.analyze(evidence, c)).isInstanceOf(IllegalArgumentException.class);
            assertThat(fallbackCalls.get()).isZero();
            c.fallbackEnabled = true; c.fallbackModel = "fallback-model";
            service.analyze(evidence, c);
            assertThat(fallbackCalls.get()).isEqualTo(1);
        }
    }

}
