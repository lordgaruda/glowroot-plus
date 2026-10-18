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

import java.util.ArrayList;
import java.util.List;

/** Non-secret, cluster-wide settings. Credentials are environment variables only. */
public class InsightsConfig {
    public boolean enabled = false;
    public boolean analysisEnabled = false;
    public String provider = "openai";
    public String model = "";
    public boolean fallbackEnabled = false;
    public String fallbackModel = "";
    public int intervalSeconds = 30;
    public int recentMinutes = 5;
    public int baselineMinutes = 60;
    public int ingestionDelaySeconds = 120;
    public int minimumSamples = 100;
    public int minimumQuerySamples = 10;
    public int sustainedEvaluations = 3;
    public int healthyEvaluations = 3;
    public int cooldownSeconds = 600;
    public double errorRate = 0.05;
    public double latencyMillis = 1000;
    public double queryMillis = 100;
    public double baselineMultiplier = 2;
    public boolean expectedTraffic = false;
    public double throughputDropRatio = 0.25;
    public double cpuRatio = 0.9;
    public double heapRatio = 0.9;
    public double gcMillisPerSecond = 200;
    public double threadCount = 500;
    public int timeoutSeconds = 20;
    public int concurrency = 2;
    public int queueCapacity = 100;
    public int requestsPerHour = 60;
    public int maxOutputTokens = 1500;
    public int maxPayloadBytes = 12000;
    public int retentionDays = 30;
    public List<String> excludedFields = new ArrayList<String>();

    public void validate() {
        range(intervalSeconds, 10, 3600); range(recentMinutes, 1, 60);
        range(baselineMinutes, 10, 1440); range(ingestionDelaySeconds, 60, 3600);
        range(minimumSamples, 1, 1000000); range(minimumQuerySamples, 1, 1000000);
        range(sustainedEvaluations, 1, 100); range(healthyEvaluations, 1, 100);
        range(cooldownSeconds, 60, 86400); range(timeoutSeconds, 1, 60);
        range(concurrency, 1, 8); range(queueCapacity, 1, 1000);
        range(requestsPerHour, 1, 10000); range(maxOutputTokens, 128, 8192);
        range(maxPayloadBytes, 2048, 32768); range(retentionDays, 1, 90);
        positive(latencyMillis); positive(queryMillis); positive(threadCount);
        positive(gcMillisPerSecond); positive(baselineMultiplier);
        if (baselineMultiplier < 1 || !ratio(errorRate) || !ratio(cpuRatio)
                || !ratio(heapRatio) || !ratio(throughputDropRatio)
                || !(provider.equals("openai") || provider.equals("anthropic")
                        || provider.equals("gemini"))
                || model == null || model.length() > 128 || fallbackModel == null
                || fallbackModel.length() > 128 || excludedFields == null
                || excludedFields.size() > 30 || (analysisEnabled && model.trim().isEmpty())
                || (fallbackEnabled && fallbackModel.trim().isEmpty())) {
            throw new IllegalArgumentException("Invalid AI Insights configuration");
        }
        for (String field : excludedFields) {
            if (field == null || field.length() > 64) {
                throw new IllegalArgumentException("Invalid field exclusion");
            }
        }
    }
    private static boolean ratio(double n) { return Double.isFinite(n) && n > 0 && n <= 1; }
    private static void positive(double n) {
        if (!Double.isFinite(n) || n <= 0) throw new IllegalArgumentException("Invalid threshold");
    }
    private static void range(int n, int low, int high) {
        if (n < low || n > high) throw new IllegalArgumentException("Configuration out of range");
    }
}
