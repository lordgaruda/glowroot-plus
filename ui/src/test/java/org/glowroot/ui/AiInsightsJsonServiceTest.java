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

import org.junit.jupiter.api.Test;
import org.glowroot.ui.HttpSessionManager.Authentication;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiInsightsJsonServiceTest {
    @Test void everyEvidencePermissionIsRequiredForTheSelectedAgent() throws Exception {
        Authentication auth = mock(Authentication.class);
        when(auth.isPermittedForAgentRollup(eq("a"), anyString())).thenReturn(true);
        AiInsightsJsonService.authorize("a", auth);
        assertThatThrownBy(() -> AiInsightsJsonService.authorize("b", auth)).isInstanceOf(JsonServiceException.class);
        when(auth.isPermittedForAgentRollup("a", "agent:transaction:queries")).thenReturn(false);
        assertThatThrownBy(() -> AiInsightsJsonService.authorize("a", auth)).isInstanceOf(JsonServiceException.class);
        assertThatThrownBy(() -> AiInsightsJsonService.authorize("@ai-settings", auth)).isInstanceOf(JsonServiceException.class);
    }
    @Test void browserPostWithoutPreflightHeaderIsForbidden() throws Exception {
        CommonHandler.CommonRequest request = mock(CommonHandler.CommonRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getPath()).thenReturn("/backend/admin/ai-insights/test");
        when(request.getParameters()).thenReturn(new java.util.HashMap<>());
        Authentication auth = mock(Authentication.class);
        HttpSessionManager sessions = mock(HttpSessionManager.class);
        when(sessions.getAuthentication(request, true)).thenReturn(auth);
        LayoutService layout = mock(LayoutService.class);
        when(layout.getLayoutVersion(auth)).thenReturn("test");
        CommonHandler handler = new CommonHandler(true, layout, java.util.Collections.emptyMap(),
                sessions, java.util.Collections.emptyList(), org.glowroot.common.util.Clock.systemClock());
        assertThat(handler.handle(request).getStatus()).isEqualTo(io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN);
    }
    @Test void rejectsSecretsInConfigurationWithoutReflectingInput() {
        AiInsightsJsonService service = new AiInsightsJsonService(null);
        assertThatThrownBy(() -> service.configure("{\"apiKey\":\"very-secret-value\"}"))
                .isInstanceOf(JsonServiceException.class).hasMessage("Invalid AI Insights configuration");
    }

}
