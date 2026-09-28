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
package org.glowroot.central.repo;

import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.Proto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class AgentConfigDaoTest {

    @Test
    public void shouldConvertToConfigJson() {
        // given
        AgentConfig agentConfig = AgentConfig.newBuilder()
                .setTransactionConfig(TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(Proto.OptionalInt32.newBuilder().setValue(2500)))
                .addPluginConfig(PluginConfig.newBuilder()
                        .setId("cassandra-plugin")
                        .setName("Cassandra Plugin")
                        .addProperty(PluginProperty.newBuilder()
                                .setName("stackTraceThresholdMillis")
                                .setValue(PluginProperty.Value.newBuilder().setDval(100.0))))
                .build();

        // when
        String json = AgentConfigDao.toConfigJson(agentConfig);

        // then
        assertThat(json).isNotNull();
        assertThat(json).contains("\"slowThresholdMillis\": 2500");
        assertThat(json).contains("\"cassandra-plugin\"");
        assertThat(json).contains("\"stackTraceThresholdMillis\": 100.0");
    }

    @Test
    public void shouldHandleNullOrEmptyConfigInToJson() {
        // given
        AgentConfig agentConfig = AgentConfig.getDefaultInstance();

        // when
        String json = AgentConfigDao.toConfigJson(agentConfig);

        // then
        assertThat(json).isNotNull();
        assertThat(json).contains("\"transactions\"");
        assertThat(json).contains("\"jvm\"");
        assertThat(json).contains("\"uiDefaults\"");
        assertThat(json).contains("\"advanced\"");
    }
}

