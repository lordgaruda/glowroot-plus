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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common2.ai.AnalysisContract;
import org.glowroot.common2.ai.AnalysisProvider;
import org.glowroot.common2.ai.InsightsConfig;

/** Fixed HTTPS destinations: UI configuration cannot redirect credentials or telemetry. */
public abstract class HttpAnalysisProvider implements AnalysisProvider {
    protected static final ObjectMapper MAPPER = ObjectMappers.create();
    private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private static java.util.concurrent.ScheduledThreadPoolExecutor deadlines() {
        java.util.concurrent.ScheduledThreadPoolExecutor executor = new java.util.concurrent.ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "Glowroot-AI-HTTP-Deadline"); thread.setDaemon(true); return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
    private final String endpoint;
    private final String key;
    protected HttpAnalysisProvider(String endpoint, String key) { this.endpoint = endpoint; this.key = key; }
    protected abstract ObjectNode request(ObjectNode evidence, InsightsConfig config);
    protected abstract void authenticate(HttpURLConnection connection, String key);
    protected abstract String response(JsonNode body) throws Exception;
    protected String endpoint(InsightsConfig config) { return endpoint; }
    public final JsonNode analyze(ObjectNode evidence, InsightsConfig config) throws Exception {
        if (key == null || key.isEmpty()) throw new ProviderException("credentials unavailable", false, 0);
        byte[] bytes = MAPPER.writeValueAsBytes(request(evidence, config));
        if (bytes.length > config.maxPayloadBytes) throw new ProviderException("payload limit", false, 0);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint(config)).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(config.timeoutSeconds * 1000);
        connection.setReadTimeout(config.timeoutSeconds * 1000);
        connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(bytes.length);
        connection.setRequestProperty("Content-Type", "application/json");
        authenticate(connection, key);
        long deadline = System.nanoTime() + config.timeoutSeconds * 1000000000L;
        java.util.concurrent.ScheduledFuture<?> timeout = DEADLINES.schedule(connection::disconnect,
                config.timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        try {
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int status = connection.getResponseCode();
            if (status != 200) {
                long retry = 0;
                try { retry = Long.parseLong(connection.getHeaderField("Retry-After")) * 1000; }
                catch (Exception ignored) { /* bounded exponential fallback */ }
                throw new ProviderException("provider HTTP " + status,
                        status == 429 || status == 408 || status >= 500, Math.min(60000, Math.max(0, retry)));
            }
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int size;
                while ((size = in.read(buffer)) != -1) {
                    if (out.size() + size > 65536 || System.nanoTime() > deadline) {
                        throw new ProviderException("response size or deadline limit", false, 0);
                    }
                    out.write(buffer, 0, size);
                }
                String text = response(MAPPER.readTree(out.toByteArray()));
                return AnalysisContract.validate(text, evidence.path("id").asText());
            }
        } finally { timeout.cancel(false); connection.disconnect(); }
    }
    public static final class ProviderException extends Exception {
        public final boolean retryable;
        public final long retryAfterMillis;
        public ProviderException(String message, boolean retryable, long retryAfterMillis) {
            super(message); this.retryable = retryable; this.retryAfterMillis = retryAfterMillis;
        }
    }
}
