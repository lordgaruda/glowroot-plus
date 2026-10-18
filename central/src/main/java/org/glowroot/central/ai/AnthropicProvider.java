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

public final class AnthropicProvider extends HttpAnalysisProvider {
    public AnthropicProvider() { this("https://api.anthropic.com/v1/messages", System.getenv("ANTHROPIC_API_KEY")); }
    AnthropicProvider(String endpoint, String key) { super(endpoint, key); }
    protected ObjectNode request(ObjectNode evidence, InsightsConfig c) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", c.model); request.put("max_tokens", c.maxOutputTokens);
        request.put("system", AnalysisContract.INSTRUCTIONS);
        request.putArray("messages").addObject().put("role", "user").put("content", evidence.toString());
        return request;
    }
    protected void authenticate(HttpURLConnection connection, String key) {
        connection.setRequestProperty("x-api-key", key);
        connection.setRequestProperty("anthropic-version", "2023-06-01");
    }
    protected String response(JsonNode body) throws Exception {
        if (!body.path("stop_reason").asText().equals("end_turn")) throw new IllegalArgumentException("Incomplete response");
        StringBuilder text = new StringBuilder();
        for (JsonNode content : body.path("content")) {
            if (content.path("type").asText().equals("text")) text.append(content.path("text").asText());
        }
        return text.toString();
    }
}
