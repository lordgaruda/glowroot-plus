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
package org.glowroot.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common2.ai.InsightsAccess;
import org.glowroot.common2.ai.InsightsConfig;
import org.glowroot.ui.HttpSessionManager.Authentication;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;

@JsonService
class AiInsightsJsonService {
    private static final ObjectMapper mapper = ObjectMappers.create();
    private final @Nullable InsightsAccess access;
    AiInsightsJsonService(@Nullable InsightsAccess access) { this.access = access; }

    @GET(path = "/backend/ai-insights", permission = "agent:transaction:overview")
    String list(@BindAgentRollupId String agent, @BindAuthentication Authentication auth) throws Exception {
        authorize(agent, auth);
        ObjectNode response = mapper.createObjectNode();
        if (access == null) return response.put("status", "unavailable: central collector required").toString();
        InsightsConfig c = access.config();
        response.put("status", c.enabled ? (c.analysisEnabled ? "enabled" : "AI analysis disabled; local detection enabled") : "disabled");
        response.put("coverage", "Leaf agents; completed minute aggregates. Missing metrics are insufficient data.");
        response.set("incidents", mapper.valueToTree(access.list(agent)));
        response.set("dataStatus", mapper.valueToTree(access.dataStatus(agent)));
        return response.toString();
    }
    @POST(path = "/backend/ai-insights/action", permission = "agent:config:edit:alerts")
    String action(@BindAgentRollupId String agent, @BindRequest String content,
            @BindAuthentication Authentication auth) throws Exception {
        authorize(agent, auth);
        JsonNode request = mapper.readTree(content);
        String id = request.path("id").asText();
        if (!id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid incident id");
        requireAccess().action(agent, id, request.path("action").asText());
        return "{}";
    }
    @GET(path = "/backend/admin/ai-insights", permission = "admin:edit")
    String config() throws Exception {
        ObjectNode response = mapper.createObjectNode();
        response.set("config", mapper.valueToTree(requireAccess().config()));
        response.put("testStatus", requireAccess().testStatus());
        return response.toString();
    }
    @POST(path = "/backend/admin/ai-insights", permission = "admin:edit")
    String configure(@BindRequest String content) throws Exception {
        InsightsConfig config;
        try {
            config = mapper.readValue(content, InsightsConfig.class);
            config.validate();
        } catch (Exception e) {
            // Do not reflect/log rejected input (including accidentally pasted credentials).
            throw new JsonServiceException(io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST,
                    "Invalid AI Insights configuration");
        }
        requireAccess().configure(config);
        return config();
    }
    @POST(path = "/backend/admin/ai-insights/test", permission = "admin:edit")
    String test() throws Exception {
        return mapper.createObjectNode().put("status", requireAccess().testConnection()).toString();
    }
    static void authorize(String agent, Authentication auth) throws Exception {
        // Findings combine these sources; permission to one source cannot expose the others.
        if (agent.isEmpty() || agent.startsWith("@") || agent.endsWith("::")) throw new JsonServiceException(FORBIDDEN);
        for (String permission : new String[] {"agent:transaction:overview", "agent:transaction:queries",
                "agent:transaction:traces", "agent:error:overview", "agent:jvm:gauges"}) {
            if (!auth.isPermittedForAgentRollup(agent, permission)) throw new JsonServiceException(FORBIDDEN);
        }
    }
    private InsightsAccess requireAccess() {
        if (access == null) throw new IllegalStateException("AI Insights requires central collector");
        return access;
    }
}
