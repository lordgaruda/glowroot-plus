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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Persisted local finding. Analysis never determines severity or lifecycle. */
public class Insight {
    public String id = "";
    public String agentId = "";
    public String transactionType = "";
    public String transactionName = "";
    public String issue = "";
    public String identity = "";
    public String state = "open";
    public String severity = "warning";
    public String analysisStatus = "disabled";
    public String analysisError = "";
    public String job = "";
    public long firstSeen;
    public long lastSeen;
    public long resolvedAt;
    public long occurrences;
    public long evaluatedThrough;
    public long lastAnalysis;
    public long leaseUntil;
    public int anomalous;
    public int healthy;
    public ObjectNode evidence;
    public JsonNode analysis;
    public ObjectNode analyzedEvidence;

    public boolean apply(LocalDetector.Signal signal, InsightsConfig config) {
        if (signal.to <= evaluatedThrough) return false;
        if (evaluatedThrough != 0 && signal.to - evaluatedThrough > 120000) {
            anomalous = 0; healthy = 0;
        }
        evaluatedThrough = signal.to;
        evidence = signal.evidence;
        if (signal.result == LocalDetector.Result.INSUFFICIENT) {
            anomalous = 0; healthy = 0;
            return true;
        }
        if (signal.result == LocalDetector.Result.ANOMALY) {
            healthy = 0;
            anomalous++;
            if (anomalous >= config.sustainedEvaluations) {
                if (firstSeen == 0) firstSeen = signal.to;
                if (state.equals("resolved")) { state = "open"; resolvedAt = 0; }
                lastSeen = signal.to;
                occurrences++;
                severity = signal.severity;
            }
        } else {
            anomalous = 0;
            if (++healthy >= config.healthyEvaluations && firstSeen != 0) {
                if (!state.equals("resolved")) resolvedAt = signal.to;
                state = "resolved";
            }
        }
        return true;
    }
}
