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

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.glowroot.common.util.ObjectMappers;

public final class LocalDetector {
    public enum Result { ANOMALY, HEALTHY, INSUFFICIENT }
    public static final class Signal {
        public String issue;
        public String identity;
        public long to;
        public Result result;
        public String severity;
        public ObjectNode evidence;
    }
    private LocalDetector() {}
    public static Signal compare(String issue, String identity, long from, long to,
            double current, double baseline, long samples, long baselineSamples,
            double absolute, double multiplier, int minimum, boolean available, boolean drop) {
        Signal signal = new Signal();
        signal.issue = issue; signal.identity = identity; signal.to = to;
        boolean valid = available && samples >= minimum && baselineSamples >= minimum
                && Double.isFinite(current) && Double.isFinite(baseline)
                && current >= 0 && baseline >= 0;
        boolean anomaly = drop ? baseline > 0 && current < baseline * absolute
                : current >= absolute && (baseline == 0 || current >= baseline * multiplier);
        signal.result = !valid ? Result.INSUFFICIENT : anomaly ? Result.ANOMALY : Result.HEALTHY;
        signal.severity = !drop && current >= absolute * 2 ? "critical" : "warning";
        ObjectNode e = ObjectMappers.create().createObjectNode();
        e.put("id", Evidence.id(issue, identity, Long.toString(to)));
        e.put("rule", issue); e.put("identity", identity);
        e.put("from", from); e.put("to", to);
        e.put("samples", samples); e.put("baselineSamples", baselineSamples);
        if (Double.isFinite(current)) e.put("current", current);
        if (Double.isFinite(baseline)) e.put("baseline", baseline);
        e.put("absoluteThreshold", absolute); e.put("baselineMultiplier", multiplier);
        e.put("dataStatus", valid ? "available" : "insufficient data");
        signal.evidence = e;
        return signal;
    }
}
