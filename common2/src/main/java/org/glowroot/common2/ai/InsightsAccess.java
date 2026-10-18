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

/** UI boundary; no provider call is executed on a UI request thread. */
public interface InsightsAccess {
    List<Insight> list(String agent) throws Exception;
    java.util.Map<String, String> dataStatus(String agent) throws Exception;
    void action(String agent, String id, String action) throws Exception;
    InsightsConfig config() throws Exception;
    void configure(InsightsConfig config) throws Exception;
    String testConnection() throws Exception;
    String testStatus();
}
