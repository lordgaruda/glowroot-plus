/*
 * Copyright 2024 the original author or authors.
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
package org.glowroot.agent.plugin.jdbc;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.config.BooleanProperty;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

/**
 * Aspect that triggers N+1 query analysis at the end of JDBC connection lifecycle events.
 *
 * <p>This aspect intercepts {@code Connection.close()} to trigger the N+1 analysis. Since
 * connections are typically closed at the end of a request/transaction (or returned to a pool),
 * this is a natural point to analyze accumulated query patterns.
 *
 * <p>The analysis is also triggered by intercepting {@code Connection.commit()} and
 * {@code Connection.setAutoCommit(true)} as additional signals that a unit of work has
 * completed.
 */
public class NplusOneAnalysisAspect {

    private static final ConfigService configService = Agent.getConfigService("jdbc");

    private static final BooleanProperty detectEnabled =
            configService.getBooleanProperty("detectNplusOneQueries");

    // =================== Connection Close ===================

    @Pointcut(className = "java.sql.Connection", methodName = "close",
            methodParameterTypes = {}, nestingGroup = "jdbc",
            order = 100) // high order to run after other close advice
    public static class ConnectionCloseAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return detectEnabled.value();
        }
        @OnAfter
        public static void onAfter(OptionalThreadContext context) {
            NplusOneDetector.analyzeAndReport(context, configService);
        }
    }

    // =================== Connection Commit ===================

    @Pointcut(className = "java.sql.Connection", methodName = "commit",
            methodParameterTypes = {}, nestingGroup = "jdbc",
            order = 100)
    public static class ConnectionCommitAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return detectEnabled.value();
        }
        @OnAfter
        public static void onAfter(OptionalThreadContext context) {
            NplusOneDetector.analyzeAndReport(context, configService);
        }
    }
}
