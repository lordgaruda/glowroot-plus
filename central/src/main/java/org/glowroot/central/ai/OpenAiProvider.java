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

import java.net.HttpURLConnection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.glowroot.common2.ai.AnalysisContract;
import org.glowroot.common2.ai.InsightsConfig;

public final class OpenAiProvider extends HttpAnalysisProvider {
    public OpenAiProvider() { this("https://api.openai.com/v1/responses", System.getenv("OPENAI_API_KEY")); }
    OpenAiProvider(String endpoint, String key) { super(endpoint, key); }
    protected ObjectNode request(ObjectNode evidence, InsightsConfig c) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", c.model); request.put("store", false);
        request.put("instructions", AnalysisContract.INSTRUCTIONS);
        request.put("input", evidence.toString()); request.put("max_output_tokens", c.maxOutputTokens);
        return request;
    }
    protected void authenticate(HttpURLConnection connection, String key) {
        connection.setRequestProperty("Authorization", "Bearer " + key);
    }
    protected String response(JsonNode body) throws Exception {
        if (!body.path("status").asText().equals("completed")) {
            throw new IllegalArgumentException("Incomplete response");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode output : body.path("output")) {
            for (JsonNode content : output.path("content")) {
                if (content.path("type").asText().equals("refusal")) throw new IllegalArgumentException("Refusal");
                if (content.path("type").asText().equals("output_text")) text.append(content.path("text").asText());
            }
        }
        return text.toString();
    }
}
