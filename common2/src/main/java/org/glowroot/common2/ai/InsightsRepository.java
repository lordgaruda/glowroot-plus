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
package org.glowroot.common2.ai;

import java.util.List;

/** Storage boundary for future embedded support. All mutations are compare-and-set. */
public interface InsightsRepository {
    final class Document {
        public final String revision;
        public final String json;
        public Document(String revision, String json) { this.revision = revision; this.json = json; }
    }
    Document read(String agent, String id) throws Exception;
    List<Document> list(String agent) throws Exception;
    boolean compareAndSet(String agent, String id, String revision, String json, int ttlSeconds)
            throws Exception;
}
