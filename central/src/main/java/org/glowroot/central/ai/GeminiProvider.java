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

/** Gemini Developer API provider. The official free tier still uses a server-side API key. */
public final class GeminiProvider extends HttpAnalysisProvider {
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    public GeminiProvider() {
        this(BASE + "gemini-2.5-flash:generateContent", System.getenv("GEMINI_API_KEY"));
    }

    GeminiProvider(String endpoint, String key) { super(endpoint, key); }

    protected String endpoint(InsightsConfig config) {
        String model = config.model;
        if (model == null || !model.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("Invalid Gemini model");
        }
        return BASE + model + ":generateContent";
    }

    protected ObjectNode request(ObjectNode evidence, InsightsConfig config) {
        ObjectNode request = MAPPER.createObjectNode();
        request.putObject("systemInstruction").putArray("parts").addObject()
                .put("text", AnalysisContract.INSTRUCTIONS);
        request.putArray("contents").addObject().put("role", "user")
                .putArray("parts").addObject().put("text", evidence.toString());
        ObjectNode generation = request.putObject("generationConfig");
        generation.put("responseMimeType", "application/json");
        generation.put("maxOutputTokens", config.maxOutputTokens);
        return request;
    }

    protected void authenticate(HttpURLConnection connection, String key) {
        connection.setRequestProperty("x-goog-api-key", key);
    }

    protected String response(JsonNode body) throws Exception {
        JsonNode candidate = body.path("candidates").path(0);
        if (!candidate.path("finishReason").asText().equals("STOP")) {
            throw new IllegalArgumentException("Incomplete or refused Gemini response");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.has("text")) text.append(part.path("text").asText());
        }
        return text.toString();
    }
}
