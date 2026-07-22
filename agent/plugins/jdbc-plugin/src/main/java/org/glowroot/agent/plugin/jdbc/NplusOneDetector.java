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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.config.BooleanProperty;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.jdbc.message.BindParameterList;

/**
 * Detects N+1 query patterns and duplicate queries within a single transaction.
 */
public class NplusOneDetector {

    // Pattern to strip bind parameter values for normalization
    // Matches quoted strings, numeric literals, and IN-clause lists
    private static final Pattern PARAM_PATTERN = Pattern.compile(
            "'[^']*'" // single-quoted strings
            + "|\"[^\"]*\"" // double-quoted strings
            + "|\\b\\d+\\.?\\d*\\b" // numeric literals
            + "|\\?" // JDBC parameter placeholders (keep for grouping)
    );

    private static final int MAX_QUERY_TEXT_LENGTH = 10000;
    private static final int MAX_OFFENDING_QUERIES = 10;

    private static class NplusOneState {
        // Key: normalized query (placeholders for parameters), Value: count
        final Map<String, Integer> normalizedQueryCounts = new HashMap<String, Integer>();
        // Key: exact query (including parameter values if available), Value: count
        final Map<String, Integer> exactQueryCounts = new HashMap<String, Integer>();
        // Key: normalized query, Value: first raw query text encountered (for display)
        final Map<String, String> normalizedRawQueryTexts = new HashMap<String, String>();
    }

    /**
     * Records a query execution. Called from StatementAspect advice methods.
     */
    static void recordQuery(ThreadContext context, ConfigService configService, @Nullable String queryText) {
        recordQuery(context, configService, queryText, null);
    }

    /**
     * Records a query execution with bind parameters. Called from StatementAspect advice methods.
     */
    static void recordQuery(ThreadContext context, ConfigService configService, @Nullable String queryText,
            @Nullable BindParameterList parameters) {
        if (queryText == null) {
            return;
        }
        BooleanProperty detectEnabled = configService.getBooleanProperty("detectNplusOneQueries");
        if (!detectEnabled.value()) {
            return;
        }

        NplusOneState state = (NplusOneState) context.getPluginData("nplusOneState");
        if (state == null) {
            state = new NplusOneState();
            context.putPluginData("nplusOneState", state);
        }

        // 1. Track normalized query (for N+1 detection)
        String normalizedQuery = normalizeQuery(queryText);
        Integer count = state.normalizedQueryCounts.get(normalizedQuery);
        if (count == null) {
            state.normalizedQueryCounts.put(normalizedQuery, 1);
            state.normalizedRawQueryTexts.put(normalizedQuery, queryText);
        } else {
            state.normalizedQueryCounts.put(normalizedQuery, count + 1);
        }

        // 2. Track exact query with parameters (for exact duplicate detection)
        String exactQuery = getExactQuery(queryText, parameters);
        Integer exactCount = state.exactQueryCounts.get(exactQuery);
        if (exactCount == null) {
            state.exactQueryCounts.put(exactQuery, 1);
        } else {
            state.exactQueryCounts.put(exactQuery, exactCount + 1);
        }
    }

    /**
     * Analyzes the accumulated queries for the current transaction and sets transaction attributes
     * if N+1 or duplicate patterns are detected. Should be called near transaction end.
     *
     * @param context the thread context to set attributes on
     * @param configService the JDBC plugin's config service for reading thresholds
     */
    static void analyzeAndReport(ThreadContext context, ConfigService configService) {
        NplusOneState state = (NplusOneState) context.getPluginData("nplusOneState");
        if (state == null || (state.normalizedQueryCounts.isEmpty() && state.exactQueryCounts.isEmpty())) {
            return;
        }

        try {
            double nplusOneThresholdDbl = getDoubleProperty(configService, "nplusOneThreshold", 5.0);
            int nplusOneThreshold = (int) nplusOneThresholdDbl;
            double duplicateThresholdDbl = getDoubleProperty(configService, "duplicateQueryThreshold", 3.0);
            int duplicateThreshold = (int) duplicateThresholdDbl;

            List<String> nplusOneQueries = new ArrayList<String>();
            List<String> duplicateQueries = new ArrayList<String>();
            int nplusOneCount = 0;
            int duplicateCount = 0;
            int maxRepeat = 0;

            // Analyze N+1 Queries (from normalized counts)
            for (Map.Entry<String, Integer> entry : state.normalizedQueryCounts.entrySet()) {
                int execCount = entry.getValue();
                if (execCount > maxRepeat) {
                    maxRepeat = execCount;
                }

                if (execCount >= nplusOneThreshold) {
                    nplusOneCount++;
                    String rawText = state.normalizedRawQueryTexts.get(entry.getKey());
                    String displayText = rawText != null ? rawText : entry.getKey();
                    if (displayText.length() > MAX_QUERY_TEXT_LENGTH) {
                        displayText = displayText.substring(0, MAX_QUERY_TEXT_LENGTH) + "...";
                    }
                    if (nplusOneQueries.size() < MAX_OFFENDING_QUERIES) {
                        nplusOneQueries.add(displayText + " [x" + execCount + "]");
                    }
                }
            }

            // Analyze Duplicate Queries (from exact parameter-aware counts)
            for (Map.Entry<String, Integer> entry : state.exactQueryCounts.entrySet()) {
                int execCount = entry.getValue();
                if (execCount >= duplicateThreshold) {
                    duplicateCount++;
                    String displayText = entry.getKey();
                    if (displayText.length() > MAX_QUERY_TEXT_LENGTH) {
                        displayText = displayText.substring(0, MAX_QUERY_TEXT_LENGTH) + "...";
                    }
                    if (duplicateQueries.size() < MAX_OFFENDING_QUERIES) {
                        duplicateQueries.add(displayText + " [x" + execCount + "]");
                    }
                }
            }

            if (nplusOneCount > 0) {
                context.setTransactionAttribute("n-plus-one-detected", "true");
                context.setTransactionAttribute("n-plus-one-count",
                        String.valueOf(nplusOneCount));
                context.setTransactionAttribute("n-plus-one-max-repeat",
                        String.valueOf(maxRepeat));
                context.removeTransactionAttribute("n-plus-one-queries");
                for (String query : nplusOneQueries) {
                    context.addTransactionAttribute("n-plus-one-queries", query);
                }
                // Force transaction trace capture
                context.setTransactionSlowThreshold(0, java.util.concurrent.TimeUnit.MILLISECONDS, ThreadContext.Priority.CORE_PLUGIN);
            }

            if (duplicateCount > 0) {
                context.setTransactionAttribute("duplicate-query-detected", "true");
                context.setTransactionAttribute("duplicate-query-count",
                        String.valueOf(duplicateCount));
                context.removeTransactionAttribute("duplicate-queries");
                for (String query : duplicateQueries) {
                    context.addTransactionAttribute("duplicate-queries", query);
                }
                // Force transaction trace capture
                context.setTransactionSlowThreshold(0, java.util.concurrent.TimeUnit.MILLISECONDS, ThreadContext.Priority.CORE_PLUGIN);
            }
        } catch (Exception e) {
            // ignore or log
        }
    }

    /**
     * Clears the per-transaction query tracking state. No longer needed since
     * the state is stored on the ThreadContext and GCed. Keep for backward compatibility/compilation.
     */
    static void clearState() {
    }

    /**
     * Normalizes a SQL query by replacing literal values with placeholders.
     * This groups queries that differ only in parameter values (e.g., different IDs in WHERE
     * clauses) into the same bucket, which is essential for N+1 detection.
     */
    static String normalizeQuery(String sql) {
        return PARAM_PATTERN.matcher(sql).replaceAll("?");
    }

    private static String getExactQuery(String queryText, @Nullable BindParameterList parameters) {
        if (parameters == null || parameters.size() == 0) {
            return queryText;
        }
        StringBuilder sb = new StringBuilder(queryText);
        sb.append(" [");
        boolean first = true;
        for (Object parameter : parameters) {
            if (!first) {
                sb.append(", ");
            }
            if (parameter instanceof String) {
                sb.append("\'");
                sb.append((String) parameter);
                sb.append("\'");
            } else if (parameter == null) {
                sb.append("NULL");
            } else {
                sb.append(String.valueOf(parameter));
            }
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    private static double getDoubleProperty(ConfigService configService, String name,
            double defaultValue) {
        try {
            // ConfigService doesn't have a getDoubleProperty, so we use the boolean check
            // and fall through to the default. The actual value is stored as a plugin property.
            // For double properties, we access through the general property mechanism.
            return configService.getDoubleProperty(name).value();
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
