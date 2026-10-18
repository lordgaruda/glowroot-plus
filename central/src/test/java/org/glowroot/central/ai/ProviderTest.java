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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common2.ai.AnalysisContract;
import org.glowroot.common2.ai.InsightsConfig;
import static org.assertj.core.api.Assertions.*;

class ProviderTest {
    @Test void rateLimitMalformedAndTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rate", exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "2");
            exchange.sendResponseHeaders(429, -1); exchange.close();
        });
        server.createContext("/malformed", exchange -> {
            byte[] body = "{\"status\":\"incomplete\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/timeout", exchange -> {
            try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            InsightsConfig c = new InsightsConfig(); c.model = "mock"; c.timeoutSeconds = 1;
            com.fasterxml.jackson.databind.node.ObjectNode e = ObjectMappers.create().createObjectNode().put("id", "test");
            assertThatThrownBy(() -> new OpenAiProvider(url + "/rate", "mock").analyze(e, c))
                    .isInstanceOf(HttpAnalysisProvider.ProviderException.class)
                    .satisfies(error -> assertThat(((HttpAnalysisProvider.ProviderException) error).retryable).isTrue());
            assertThatThrownBy(() -> new OpenAiProvider(url + "/malformed", "mock").analyze(e, c))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AnthropicProvider(url + "/timeout", "mock").analyze(e, c))
                    .isInstanceOf(java.io.IOException.class);
        } finally { server.stop(0); }
    }
    @Test void bothWireFormatsAcceptValidatedJsonAndRejectRefusals() throws Exception {
        String json = "{\"summary\":\"Observed\",\"possibleCauses\":[],\"supportingEvidenceIds\":[\"e1\"],"
                + "\"missingEvidence\":[],\"diagnosticSteps\":[],\"mitigations\":[],"
                + "\"uncertainty\":\"high\",\"limitations\":\"sampled\"}";
        com.fasterxml.jackson.databind.ObjectMapper mapper = ObjectMappers.create();
        com.fasterxml.jackson.databind.node.ObjectNode open = mapper.createObjectNode().put("status", "completed");
        open.putArray("output").addObject().putArray("content").addObject().put("type", "output_text").put("text", json);
        OpenAiProvider openai = new OpenAiProvider("http://localhost", "mock");
        assertThat(org.glowroot.common2.ai.AnalysisContract.validate(openai.response(open), "e1")).isNotNull();
        com.fasterxml.jackson.databind.node.ObjectNode anth = mapper.createObjectNode().put("stop_reason", "end_turn");
        anth.putArray("content").addObject().put("type", "text").put("text", json);
        AnthropicProvider anthropic = new AnthropicProvider("http://localhost", "mock");
        assertThat(org.glowroot.common2.ai.AnalysisContract.validate(anthropic.response(anth), "e1")).isNotNull();
        InsightsConfig c = new InsightsConfig(); c.model = "configured-model";
        com.fasterxml.jackson.databind.node.ObjectNode evidence = mapper.createObjectNode().put("id", "e1");
        assertThat(openai.request(evidence, c).get("store").asBoolean()).isFalse();
        assertThat(anthropic.request(evidence, c).get("max_tokens").asInt()).isEqualTo(c.maxOutputTokens);
        anth.put("stop_reason", "max_tokens");
        assertThatThrownBy(() -> anthropic.response(anth)).isInstanceOf(IllegalArgumentException.class);
        open.withArray("output").removeAll().addObject().putArray("content").addObject().put("type", "refusal");
        assertThatThrownBy(() -> openai.response(open)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void geminiUsesApiKeyHeaderAndRequiresCompletedJsonCandidate() throws Exception {
        String json = "{\"summary\":\"Observed\",\"possibleCauses\":[],\"supportingEvidenceIds\":[\"e1\"],"
                + "\"missingEvidence\":[],\"diagnosticSteps\":[],\"mitigations\":[],"
                + "\"uncertainty\":\"high\",\"limitations\":\"sampled\"}";
        com.fasterxml.jackson.databind.ObjectMapper mapper = ObjectMappers.create();
        com.fasterxml.jackson.databind.node.ObjectNode result = mapper.createObjectNode();
        result.putArray("candidates").addObject().put("finishReason", "STOP")
                .putObject("content").putArray("parts").addObject().put("text", json);
        GeminiProvider gemini = new GeminiProvider("https://example.invalid", "secret");
        InsightsConfig config = new InsightsConfig(); config.provider = "gemini"; config.model = "gemini-2.5-flash";
        assertThat(gemini.request(mapper.createObjectNode().put("id", "e1"), config)
                .path("generationConfig").path("responseMimeType").asText()).isEqualTo("application/json");
        assertThat(gemini.response(result)).isEqualTo(json);
        assertThat(AnalysisContract.validate(gemini.response(result), "e1")).isNotNull();
        ((com.fasterxml.jackson.databind.node.ObjectNode) result.path("candidates").path(0))
                .put("finishReason", "SAFETY");
        assertThatThrownBy(() -> gemini.response(result)).isInstanceOf(IllegalArgumentException.class);
    }

}
