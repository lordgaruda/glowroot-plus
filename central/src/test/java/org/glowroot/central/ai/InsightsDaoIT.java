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

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.CassandraContainer;
import org.glowroot.central.util.Session;
import org.glowroot.common2.ai.InsightsRepository.Document;
import static org.assertj.core.api.Assertions.*;

/** Requires Docker, like the existing central repository integration tests. */
class InsightsDaoIT {
    @Test void cassandraCompareAndSetAndPartitionIsolation() throws Exception {
        try (CassandraContainer<?> cassandra = new CassandraContainer<>("cassandra:3.11.16")) {
            cassandra.start();
            try (Session session = new Session(CqlSession.builder()
                    .addContactPoint(cassandra.getContactPoint())
                    .withLocalDatacenter(cassandra.getLocalDatacenter())
                    .withConfigLoader(DriverConfigLoader.fromClasspath("datastax-driver.conf"))
                    .build(), "glowroot_unit_tests", null, 0)) {
                InsightsDao first = new InsightsDao(session), second = new InsightsDao(session);
                assertThat(first.compareAndSet("a", "incident", null, "one", 60)).isTrue();
                assertThat(second.compareAndSet("a", "incident", null, "duplicate", 60)).isFalse();
                Document original = first.read("a", "incident");
                assertThat(second.compareAndSet("a", "incident", original.revision, "two", 60)).isTrue();
                assertThat(first.compareAndSet("a", "incident", original.revision, "stale", 60)).isFalse();
                assertThat(first.read("a", "incident").json).isEqualTo("two");
                assertThat(first.list("b")).isEmpty();
            }
        }
    }
}
