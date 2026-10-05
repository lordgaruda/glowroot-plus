package org.glowroot.ui;

import org.junit.jupiter.api.Test;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.Proto;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigJsonServiceTest {

    @Test
    void sanitizeUiDefaultsConfigDto_xss() {
        ConfigJsonService.SanitizationService sanitizationService = new ConfigJsonService.SanitizationService();

        // given
        ConfigJsonService.UiDefaultsConfigDto configDto = ImmutableUiDefaultsConfigDto.builder()
                .defaultTransactionType("<script>alert('xss')</script>Some Text")
                .version("version")
                .build();
        // when
        ConfigJsonService.UiDefaultsConfigDto ret = sanitizationService.sanitize(configDto);

        // then
        assertEquals("Some Text", ret.defaultTransactionType());
    }

    @Test
    void sanitizeUiDefaultsConfigDto_nominalCase() {
        ConfigJsonService.SanitizationService sanitizationService = new ConfigJsonService.SanitizationService();

        // given
        ConfigJsonService.UiDefaultsConfigDto configDto = ImmutableUiDefaultsConfigDto.builder()
                .defaultTransactionType("Web")
                .version("version")
                .build();
        // when
        ConfigJsonService.UiDefaultsConfigDto ret = sanitizationService.sanitize(configDto);

        // then
        assertEquals("Web", ret.defaultTransactionType());
    }

    @Test
    void shouldConvertAgentConfigToJson() throws Exception {
        AgentConfig config = AgentConfig.newBuilder()
                .setTransactionConfig(AgentConfig.TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(Proto.OptionalInt32.newBuilder().setValue(1234)))
                .setAdvancedConfig(AgentConfig.AdvancedConfig.newBuilder()
                        .setMaxQueryAggregates(Proto.OptionalInt32.newBuilder().setValue(500)))
                .addPluginConfig(AgentConfig.PluginConfig.newBuilder()
                        .setId("test-plugin")
                        .setName("Test Plugin")
                        .addProperty(AgentConfig.PluginProperty.newBuilder()
                                .setName("prop1")
                                .setValue(AgentConfig.PluginProperty.Value.newBuilder()
                                        .setBval(true))))
                .build();

        String json = AllConfigDto.toJson(config);
        assertThat(json).isNotNull();
        assertThat(json).contains("\"slowThresholdMillis\": 1234");
        assertThat(json).contains("\"maxQueryAggregates\": 500");
        assertThat(json).contains("\"test-plugin\"");
        assertThat(json).contains("\"prop1\": true");
    }
}