/*
 * Copyright 2017-2023 the original author or authors.
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
package org.glowroot.agent.central;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CentralCollectorTest {

    @Test
    public void shouldEscape() {
        assertThat(CentralCollector.escapeHostname("")).isEqualTo("");
        assertThat(CentralCollector.escapeHostname("abc")).isEqualTo("abc");
        assertThat(CentralCollector.escapeHostname("a:b:c")).isEqualTo("a:b:c");
        assertThat(CentralCollector.escapeHostname(":a:b:c:")).isEqualTo("\\:a:b:c:");
        assertThat(CentralCollector.escapeHostname("::a::b::c::"))
                .isEqualTo("\\:\\:a:\\:b:\\:c:\\:");
        assertThat(CentralCollector.escapeHostname(":::a:::b:::c:::"))
                .isEqualTo("\\:\\:\\:a:\\:\\:b:\\:\\:c:\\:\\:");
        assertThat(CentralCollector.escapeHostname("::::")).isEqualTo("\\:\\:\\:\\:");

        assertThat(CentralCollector.escapeHostname("a\\b\\c")).isEqualTo("a\\\\b\\\\c");
    }

    @Test
    public void shouldCheckAgentVersionAgainstCentralVersion() {
        assertThat(CentralCollector.isAgentVersionGreaterThanCentralVersion("1.10.2", "1.10.1"))
                .isTrue();
        assertThat(CentralCollector.isAgentVersionGreaterThanCentralVersion("1.10.2", "1.10.2"))
                .isFalse();
        assertThat(CentralCollector.isAgentVersionGreaterThanCentralVersion("1.10.2", "1.10.3"))
                .isFalse();

        assertThat(CentralCollector.isAgentVersionGreaterThanCentralVersion("1.10.2", "1.9.2"))
                .isTrue();
        assertThat(CentralCollector.isAgentVersionGreaterThanCentralVersion("1.10.2", "1.10.2"))
                .isFalse();
        assertThat(CentralCollector.isAgentVersionGreaterThanCentralVersion("1.10.2", "1.11.2"))
                .isFalse();

        assertThat(CentralCollector.isAgentVersionGreaterThanCentralVersion("1.10.2", "0.10.2"))
                .isTrue();
        assertThat(CentralCollector.isAgentVersionGreaterThanCentralVersion("1.10.2", "1.10.2"))
                .isFalse();
        assertThat(CentralCollector.isAgentVersionGreaterThanCentralVersion("1.10.2", "2.10.2"))
                .isFalse();
    }

    @Test
    public void shouldCheckConfigSynced(@org.junit.jupiter.api.io.TempDir java.io.File tempDir) throws Exception {
        java.io.File configFile = new java.io.File(tempDir, "config.json");
        java.io.File configSyncedFile = new java.io.File(tempDir, "config.synced");

        // not synced when config.synced doesn't exist
        assertThat(CentralCollector.isConfigSynced(configSyncedFile, configFile, "test-agent")).isFalse();

        com.google.common.io.Files.asCharSink(configFile, java.nio.charset.StandardCharsets.UTF_8)
                .write("{\"hello\":\"world\"}");
        String hash = com.google.common.io.Files.asByteSource(configFile)
                .hash(com.google.common.hash.Hashing.sha256()).toString();

        CentralCollector.writeConfigSyncedFile(configSyncedFile, "test-agent", hash);

        // synced when hash and agentId match
        assertThat(CentralCollector.isConfigSynced(configSyncedFile, configFile, "test-agent")).isTrue();

        // not synced when agentId differs
        assertThat(CentralCollector.isConfigSynced(configSyncedFile, configFile, "other-agent")).isFalse();

        // not synced when config.json content is modified
        com.google.common.io.Files.asCharSink(configFile, java.nio.charset.StandardCharsets.UTF_8)
                .write("{\"hello\":\"modified\"}");
        assertThat(CentralCollector.isConfigSynced(configSyncedFile, configFile, "test-agent")).isFalse();
    }
}
