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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Fail-closed export: raw telemetry strings never enter the provider payload. */
public final class Evidence {
    private Evidence() {}
    public static String id(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':'); digest.update(bytes);
            }
            StringBuilder out = new StringBuilder();
            for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String sqlIdentity(String sql) {
        // Only the digest leaves this function. Includes comments, quoted identifiers,
        // dollar quotes, hexadecimal and numeric literals in normalization.
        String normalized = sql.replaceAll("(?s)/\\*.*?\\*/|--[^\\r\\n]*", " ")
                .replaceAll("(?s)\\$[A-Za-z_0-9]*\\$.*?\\$[A-Za-z_0-9]*\\$", "?")
                .replaceAll("'([^']|'')*'|\"([^\"]|\"\")*\"|`[^`]*`", "?")
                .replaceAll("(?i)\\b(?:0x[0-9a-f]+|[0-9]+(?:\\.[0-9]+)?)\\b", "?")
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        return id(normalized);
    }
    public static ObjectNode export(Insight incident, InsightsConfig config) {
        ObjectNode node = incident.evidence.deepCopy();
        node.put("agent", id(incident.agentId));
        node.put("transaction", id(incident.transactionType, incident.transactionName));
        for (String excluded : config.excludedFields) node.remove(excluded);
        return node;
    }
}
