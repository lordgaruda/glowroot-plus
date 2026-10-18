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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common2.ai.*;
import org.glowroot.common2.ai.InsightsRepository.Document;
import org.glowroot.common2.repo.ActiveAgentRepository;
import org.glowroot.common2.repo.ActiveAgentRepository.AgentRollup;
import org.glowroot.common2.repo.TransactionTypeRepository;
import org.glowroot.common2.repo.CassandraProfile;

/** Central-only owner of background evaluation and vendor work. */
public final class InsightsService implements InsightsAccess, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(InsightsService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();
    private static final String SETTINGS = "@ai-settings";
    private final InsightsRepository store;
    private final InsightsTelemetry telemetry;
    private final ActiveAgentRepository agents;
    private final TransactionTypeRepository types;
    private final AnalysisProvider openai, anthropic, gemini;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Glowroot-AI-Detector"); t.setDaemon(true); return t;
    });
    private final ThreadPoolExecutor workers;
    private final AtomicBoolean testing = new AtomicBoolean();
    private volatile String testState = "unavailable";
    private volatile long circuitUntil;
    private int failures;
    private long nextEvaluation;

    public InsightsService(InsightsRepository store, InsightsTelemetry telemetry,
            ActiveAgentRepository agents, TransactionTypeRepository types) throws Exception {
        this(store, telemetry, agents, types, new OpenAiProvider(), new AnthropicProvider(),
                new GeminiProvider());
    }
    InsightsService(InsightsRepository store, InsightsTelemetry telemetry,
            ActiveAgentRepository agents, TransactionTypeRepository types,
            AnalysisProvider openai, AnalysisProvider anthropic) throws Exception {
        this(store, telemetry, agents, types, openai, anthropic, new GeminiProvider());
    }
    InsightsService(InsightsRepository store, InsightsTelemetry telemetry,
            ActiveAgentRepository agents, TransactionTypeRepository types,
            AnalysisProvider openai, AnalysisProvider anthropic, AnalysisProvider gemini) throws Exception {
        this.store = store; this.telemetry = telemetry; this.agents = agents; this.types = types;
        this.openai = openai; this.anthropic = anthropic; this.gemini = gemini;
        InsightsConfig c = config();
        workers = new ThreadPoolExecutor(c.concurrency, c.concurrency, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(c.queueCapacity), r -> {
                    Thread t = new Thread(r, "Glowroot-AI-Analysis"); t.setDaemon(true); return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
    public void start() { scheduler.scheduleWithFixedDelay(this::tick, 5, 5, TimeUnit.SECONDS); }
    private void tick() {
        try {
            InsightsConfig c = config();
            long now = System.currentTimeMillis();
            if (!c.enabled || now < nextEvaluation) return;
            nextEvaluation = now + c.intervalSeconds * 1000L;
            long to = (now - c.ingestionDelaySeconds * 1000L) / 60000 * 60000;
            for (AgentRollup agent : InsightsTelemetry.await(agents.readRecentlyActiveAgentRollups(
                    (c.baselineMinutes + c.recentMinutes) * 60000L, CassandraProfile.rollup))) {
                evaluateTree(agent, to, c);
            }
        } catch (Exception e) {
            // No telemetry, provider bodies, or credentials in logs.
            logger.warn("AI Insights evaluation unavailable ({})", e.getClass().getSimpleName());
        }
    }
    private void evaluateTree(AgentRollup agent, long to, InsightsConfig c) throws Exception {
        if (Thread.currentThread().isInterrupted()) return;
        if (!agent.children().isEmpty()) {
            for (AgentRollup child : agent.children()) evaluateTree(child, to, c);
            return;
        }
        // Leaf agents only: no duplicated rollup evidence or cross-agent trace access.
        if (agent.id().endsWith("::")) return;
        // Persistent evaluation lease serializes nodes; CAS timestamps fence stale results.
        long now = System.currentTimeMillis();
        Document lock = store.read("@ai-evaluation", agent.id());
        if (lock != null && Long.parseLong(lock.json) > now) return;
        if (!store.compareAndSet("@ai-evaluation", agent.id(), lock == null ? null : lock.revision,
                Long.toString(now + 1800000), 3600)) return;
        try {
        long evaluationDeadline = now + 120000;
        for (String type : InsightsTelemetry.await(types.read(agent.id()))) {
            if (System.currentTimeMillis() > evaluationDeadline) break;
            try {
                List<String> names = telemetry.transactions(agent.id(), type, to, c);
                names.add(0, "");
                for (String name : names) {
                    if (System.currentTimeMillis() > evaluationDeadline) break;
                    for (LocalDetector.Signal signal : telemetry.read(agent.id(), type, name, to, c)) {
                        record(agent.id(), type, name, signal, c);
                    }
                }
            } catch (Exception e) {
                logger.debug("AI Insights telemetry unavailable ({})", e.getClass().getSimpleName());
            }
        }
        for (LocalDetector.Signal signal : telemetry.jvm(agent.id(), to, c)) {
            record(agent.id(), "JVM", "", signal, c);
        }
        for (Insight insight : list(agent.id())) {
            if (!insight.state.equals("resolved") && insight.evaluatedThrough >= to && insight.evidence != null
                    && insight.evidence.path("dataStatus").asText().equals("available")) enqueue(insight, c);
        }
        } finally {
            Document held = store.read("@ai-evaluation", agent.id());
            if (held != null && held.json.equals(Long.toString(now + 1800000))) {
                store.compareAndSet("@ai-evaluation", agent.id(), held.revision, "0", 3600);
            }
        }
    }
    void record(String agent, String type, String name, LocalDetector.Signal signal,
            InsightsConfig c) throws Exception {
        String id = Evidence.id(agent, type, name, signal.issue, signal.identity);
        for (int attempt = 0; attempt < 5; attempt++) {
            Document doc = store.read(agent, id);
            if (doc == null && store.list(agent).size() >= 1000) return;
            Insight incident = doc == null ? new Insight() : mapper.readValue(doc.json, Insight.class);
            incident.id = id; incident.agentId = agent; incident.transactionType = type;
            incident.transactionName = name; incident.issue = signal.issue; incident.identity = signal.identity;
            signal.evidence.put("id", Evidence.id(agent, type, name, signal.issue,
                    signal.identity, Long.toString(signal.to)));
            if (!incident.apply(signal, c)) return;
            if (!c.analysisEnabled && !incident.analysisStatus.equals("completed")) incident.analysisStatus = "disabled";
            if (store.compareAndSet(agent, id, doc == null ? null : doc.revision,
                    mapper.writeValueAsString(incident), retention(incident, c))) return;
        }
    }
    private static int retention(Insight incident, InsightsConfig c) {
        long age = incident.resolvedAt == 0 ? 0 : Math.max(0, System.currentTimeMillis() - incident.resolvedAt) / 1000;
        return (int) Math.max(1, c.retentionDays * 86400L - age);
    }
    public List<Insight> list(String agent) throws Exception {
        List<Insight> result = new ArrayList<>();
        for (Document doc : store.list(agent)) {
            Insight i = mapper.readValue(doc.json, Insight.class);
            if (i.firstSeen != 0 && agent.equals(i.agentId)) result.add(i);
        }
        result.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
        return result;
    }
    public java.util.Map<String, String> dataStatus(String agent) throws Exception {
        java.util.Map<String, String> status = new java.util.TreeMap<>();
        for (String rule : new String[] {"error-rate", "mean-latency-ms", "slow-sql-ms",
                "captured-n-plus-one", "throughput-drop", "external-mean-latency-ms",
                "cpu-saturation", "heap-pressure", "thread-growth", "gc-time-ms-per-second"}) {
            status.put(rule, "insufficient data");
        }
        long staleBefore = System.currentTimeMillis() - config().ingestionDelaySeconds * 1000L - 180000;
        List<Document> documents = store.list(agent);
        if (documents.size() >= 1000) status.put("capacity", "1000-document cap reached; new fingerprints suppressed");
        for (Document doc : documents) {
            Insight i = mapper.readValue(doc.json, Insight.class);
            if (i.agentId.equals(agent) && i.evaluatedThrough >= staleBefore && i.evidence != null
                    && i.evidence.path("dataStatus").asText().equals("available")) {
                status.put(i.issue, "available for at least one evaluated transaction");
            }
        }
        return status;
    }
    private void enqueue(Insight snapshot, InsightsConfig c) throws Exception {
        if (!c.enabled || !c.analysisEnabled) return;
        long now = System.currentTimeMillis();
        Document doc = store.read(snapshot.agentId, snapshot.id);
        if (doc == null) return;
        Insight i = mapper.readValue(doc.json, Insight.class);
        if (i.leaseUntil > now || now - i.lastAnalysis < c.cooldownSeconds * 1000L) return;
        i.job = UUID.randomUUID().toString();
        // Queue wait is not a live lease. Worker checks token and claims a bounded execution lease.
        i.leaseUntil = now + 300000;
        i.analysisStatus = "pending"; i.analysisError = "";
        if (!store.compareAndSet(i.agentId, i.id, doc.revision, mapper.writeValueAsString(i), retention(i, c))) return;
        try { workers.execute(() -> runJob(i, c)); }
        catch (RejectedExecutionException e) { complete(i, null, null, "unavailable", "queue full", false); }
    }
    private void runJob(Insight queued, InsightsConfig ignoredConfig) {
        try {
            InsightsConfig c = config();
            if (!c.enabled || !c.analysisEnabled) {
                complete(queued, null, null, "disabled", "", false); return;
            }
            Document doc = store.read(queued.agentId, queued.id);
            if (doc == null) return;
            Insight fresh = mapper.readValue(doc.json, Insight.class);
            if (!fresh.job.equals(queued.job) || fresh.leaseUntil < System.currentTimeMillis()) return;
            fresh.leaseUntil = System.currentTimeMillis() + 1800000;
            fresh.lastAnalysis = System.currentTimeMillis();
            if (!store.compareAndSet(fresh.agentId, fresh.id, doc.revision,
                    mapper.writeValueAsString(fresh), retention(fresh, c))) {
                complete(queued, null, null, "unavailable", "concurrent evidence update; retry later", false); return;
            }
            ObjectNode evidence = Evidence.export(fresh, c);
            if (!evidence.has("id")) throw new IllegalArgumentException("Evidence ID excluded");
            JsonNode result = analyze(evidence, c);
            complete(fresh, result, evidence, "completed", "", true);
        } catch (Exception e) {
            try { complete(queued, null, null, e instanceof HttpAnalysisProvider.ProviderException
                    && e.getMessage().equals("credentials unavailable") ? "unavailable" : "failed", safeError(e), true); }
            catch (Exception storageError) { logger.warn("AI Insights result persistence unavailable"); }
        }
    }
    private static String safeError(Exception e) {
        if (e instanceof HttpAnalysisProvider.ProviderException) return e.getMessage();
        if (e instanceof java.io.IOException) return "provider network error or timeout";
        return "analysis unavailable or invalid response";
    }
    JsonNode analyze(ObjectNode evidence, InsightsConfig c) throws Exception {
        if (System.currentTimeMillis() < circuitUntil) throw new HttpAnalysisProvider.ProviderException("provider circuit open", false, 0);
        try {
            JsonNode result;
            try { result = attempts(evidence, c); }
            catch (Exception primary) {
                if (!c.fallbackEnabled) throw primary;
                InsightsConfig fallback = mapper.readValue(mapper.writeValueAsString(c), InsightsConfig.class);
                fallback.provider = c.provider.equals("openai") ? "anthropic"
                        : c.provider.equals("anthropic") ? "openai" : "openai";
                fallback.model = c.fallbackModel;
                result = attempts(evidence, fallback);
            }
            synchronized (this) { failures = 0; }
            return result;
        } catch (Exception e) {
            synchronized (this) { if (++failures >= 3) circuitUntil = System.currentTimeMillis() + 60000; }
            throw e;
        }
    }
    private JsonNode attempts(ObjectNode evidence, InsightsConfig c) throws Exception {
        for (int n = 0; ; n++) {
            if (!budget(c)) throw new HttpAnalysisProvider.ProviderException("hourly request budget exhausted", false, 0);
            try { return provider(c.provider).analyze(evidence, c); }
            catch (Exception e) {
                boolean retryable = e instanceof java.io.IOException;
                long delay = (1L << n) * 1000;
                if (e instanceof HttpAnalysisProvider.ProviderException) {
                    HttpAnalysisProvider.ProviderException failure = (HttpAnalysisProvider.ProviderException) e;
                    retryable = failure.retryable; delay = Math.max(delay, failure.retryAfterMillis);
                }
                if (!retryable || n == 2) throw e;
                Thread.sleep(delay + ThreadLocalRandom.current().nextInt(500));
            }
        }
    }
    private AnalysisProvider provider(String id) {
        if (id.equals("openai")) return openai;
        if (id.equals("anthropic")) return anthropic;
        if (id.equals("gemini")) return gemini;
        throw new IllegalArgumentException("Unsupported provider");
    }
    private boolean budget(InsightsConfig c) throws Exception {
        String hour = Long.toString(System.currentTimeMillis() / 3600000);
        for (int attempt = 0; attempt < 10; attempt++) {
            Document doc = store.read("@ai-budget", hour);
            int count = doc == null ? 0 : Integer.parseInt(doc.json);
            if (count >= c.requestsPerHour) return false;
            if (store.compareAndSet("@ai-budget", hour, doc == null ? null : doc.revision,
                    Integer.toString(count + 1), 7200)) return true;
        }
        return false;
    }
    private void complete(Insight job, JsonNode result, ObjectNode evidence, String status,
            String error, boolean cooldown) throws Exception {
        InsightsConfig c = config();
        for (int attempt = 0; attempt < 10; attempt++) {
            Document doc = store.read(job.agentId, job.id);
            if (doc == null) return;
            Insight fresh = mapper.readValue(doc.json, Insight.class);
            if (!fresh.job.equals(job.job)) return;
            fresh.analysisStatus = status; fresh.analysisError = error; fresh.leaseUntil = 0;
            fresh.job = "";
            if (cooldown) fresh.lastAnalysis = System.currentTimeMillis();
            if (result != null) { fresh.analysis = result; fresh.analyzedEvidence = evidence; }
            if (store.compareAndSet(job.agentId, job.id, doc.revision,
                    mapper.writeValueAsString(fresh), retention(fresh, c))) return;
        }
    }
    public void action(String agent, String id, String action) throws Exception {
        if (!action.equals("acknowledge") && !action.equals("reanalyze")) throw new IllegalArgumentException("Invalid action");
        Document doc = store.read(agent, id);
        if (doc == null) throw new IllegalArgumentException("Unknown incident");
        Insight i = mapper.readValue(doc.json, Insight.class);
        if (!i.agentId.equals(agent) || i.firstSeen == 0) throw new IllegalArgumentException("Unknown incident");
        if (action.equals("reanalyze")) { enqueue(i, config()); return; }
        if (!i.state.equals("open")) return;
        i.state = "acknowledged";
        if (!store.compareAndSet(agent, id, doc.revision, mapper.writeValueAsString(i), retention(i, config()))) {
            throw new IllegalStateException("Incident changed; refresh and retry");
        }
    }
    public InsightsConfig config() throws Exception {
        Document doc = store.read(SETTINGS, "config");
        InsightsConfig c = doc == null ? new InsightsConfig() : mapper.readValue(doc.json, InsightsConfig.class);
        c.validate(); return c;
    }
    public void configure(InsightsConfig c) throws Exception {
        c.validate();
        Document doc = store.read(SETTINGS, "config");
        if (!store.compareAndSet(SETTINGS, "config", doc == null ? null : doc.revision,
                mapper.writeValueAsString(c), 0)) throw new IllegalStateException("Configuration changed; retry");
    }
    public String testConnection() throws Exception {
        final InsightsConfig c = config();
        if (!c.analysisEnabled) return "disabled";
        if (!testing.compareAndSet(false, true)) return "pending";
        testState = "pending";
        try {
            workers.execute(() -> {
                try {
                    ObjectNode evidence = mapper.createObjectNode();
                    evidence.put("id", "connection-test"); evidence.put("rule", "synthetic connection test; no incident");
                    analyze(evidence, c); testState = "completed";
                } catch (Exception e) { testState = safeError(e); }
                finally { testing.set(false); }
            });
        } catch (RejectedExecutionException e) { testing.set(false); testState = "unavailable: queue full"; }
        return testState;
    }
    public String testStatus() { return testState; }
    public void close() throws InterruptedException {
        scheduler.shutdownNow(); workers.shutdownNow();
        scheduler.awaitTermination(35, TimeUnit.SECONDS); workers.awaitTermination(65, TimeUnit.SECONDS);
    }
}
