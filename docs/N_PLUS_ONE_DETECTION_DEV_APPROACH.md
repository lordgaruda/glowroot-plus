# N+1 Query & Duplicate Query Detection — Developer Approach

## Overview

This document describes the design decisions, implementation approach, and all source file changes made to add **N+1 query detection** and **duplicate query detection** to the Glowroot JDBC plugin. It covers backend instrumentation, frontend rendering, and build packaging.

---

## Problem Statement

In typical JPA/Hibernate applications, N+1 query problems and inadvertently repeated identical queries are major performance killers but are difficult to trace because they appear in traces as hundreds of individual short queries. Glowroot did not previously highlight or aggregate these patterns at the trace or transaction level.

### Goals
- Automatically detect when the same SQL pattern (different parameters) is executed many times within a single transaction → **N+1 Detection**
- Automatically detect when the exact same SQL with the exact same parameter values is executed repeatedly → **Duplicate Query Detection**
- Expose results as searchable trace attributes (`n-plus-one-queries`, `duplicate-queries`)
- Render them in the UI with a clean, expandable SQL viewer

---

## Key Design Decisions

### 1. Two Separate Tracking Maps (Agent Side)

The detector maintains **two distinct maps** per transaction:

| Map | Key | Purpose |
|-----|-----|---------|
| `normalizedQueryCounts` | Normalized SQL (literals → `?`) | Groups executions of the same SQL pattern with different bind values → N+1 Detection |
| `exactQueryCounts` | Full SQL + serialized bind parameters | Only matches exact repetitions of same query with same values → Duplicate Detection |

**Why this matters**: A query like `SELECT * FROM employee WHERE id = ?` run 2000 times (with different IDs each time) should appear in **n-plus-one-queries** only. The same query run 3 times with `id = 1` should appear in **duplicate-queries** only.

### 2. Bind Parameter Forwarding

The JDBC plugin already captures bind parameters via `PreparedStatementMirror`. We extended `NplusOneDetector.recordQuery()` to accept the bind parameter list alongside the SQL text so exact query keys can be constructed.

### 3. Thread-Local Per-Transaction State

State is stored using the Glowroot `ThreadContext.putPluginData()` / `getPluginData()` mechanism, which is automatically scoped to the current transaction and garbage-collected after.

### 4. Thresholds

Thresholds are configurable via Glowroot plugin properties:

| Property | Default | Description |
|----------|---------|-------------|
| `nplusOneThreshold` | 5 | Minimum normalized executions to flag as N+1 |
| `duplicateQueryThreshold` | 3 | Minimum exact duplicate executions to flag as duplicate |

### 5. Frontend — Expand/Collapse SQL Viewer

The trace attributes section uses Handlebars templates. Both `n-plus-one-queries` and `duplicate-queries` are now rendered with the same expand/collapse SQL viewer widget:
- **Collapsed**: Shows a whitespace-normalized, truncated preview (via `shortenQuery` helper)
- **Expanded**: Shows the full, formatted SQL with a clipboard copy button

---

## Files Changed

### Backend (Java Agent - JDBC Plugin)

---

#### 1. [`NplusOneDetector.java`](file:///home/dev/workspace/glowroot/agent/plugins/jdbc-plugin/src/main/java/org/glowroot/agent/plugin/jdbc/NplusOneDetector.java)

**What changed**: Complete rewrite of the detector state and tracking logic.

**Key additions**:
```java
// Two-map state class
private static class NplusOneState {
    final Map<String, Integer> normalizedQueryCounts = new HashMap<>();
    final Map<String, Integer> exactQueryCounts = new HashMap<>();
    final Map<String, String> normalizedRawQueryTexts = new HashMap<>();
}
```

**`recordQuery` overloaded** to accept `@Nullable BindParameterList parameters`:
```java
static void recordQuery(ThreadContext context, ConfigService configService,
    @Nullable String queryText, @Nullable BindParameterList parameters) {
    // ... normalize query for N+1 tracking
    // ... build exact key for duplicate tracking
}
```

**`getExactQuery` helper** builds the exact key by appending parameter values:
```java
private static String getExactQuery(String queryText, BindParameterList parameters) {
    // Appends " [param1, param2, ...]" to the query text
}
```

**`analyzeAndReport` updated** to use separate maps and populate distinct attributes:
- N+1: iterates `normalizedQueryCounts` → sets `n-plus-one-queries`
- Duplicate: iterates `exactQueryCounts` → sets `duplicate-queries`

**`normalizeQuery` method** uses regex to strip all literals:
```java
static String normalizeQuery(String sql) {
    return PARAM_PATTERN.matcher(sql).replaceAll("?");
}
```
Matched patterns: single-quoted strings, double-quoted strings, numeric literals, `?` placeholders.

---

#### 2. [`StatementAspect.java`](file:///home/dev/workspace/glowroot/agent/plugins/jdbc-plugin/src/main/java/org/glowroot/agent/plugin/jdbc/StatementAspect.java)

**What changed**: Updated `recordQuery` call sites to pass bind parameters.

**Import added**:
```java
import org.glowroot.agent.plugin.jdbc.message.BindParameterList;
```

**For plain `Statement` executions** (no bind params):
```java
NplusOneDetector.recordQuery(context, configService, sql, null);
```

**For `PreparedStatement` executions** (with bind params):
```java
NplusOneDetector.recordQuery(context, configService, queryText, mirror.getParameters());
```

---

### Frontend (UI / Handlebars Templates)

---

#### 3. [`handlebars-rendering.js`](file:///home/dev/workspace/glowroot/ui/app/scripts/handlebars-rendering.js)

**What changed**: The `shortenQuery` Handlebars helper now collapses consecutive whitespace before truncating.

**Problem**: SQL queries from Hibernate are formatted with multiple spaces and newlines. The attribute value container uses `white-space: pre-wrap`, which causes large layout gaps when displaying collapsed preview text.

**Fix applied**:
```javascript
Handlebars.registerHelper('shortenQuery', function (value) {
    // ... parse query text and countStr
    queryText = queryText.replace(/\s+/g, ' ').trim();  // ← normalize whitespace
    // ... truncate to limit
    return queryText + countStr;
});
```

---

#### 4. [`trace.hbs`](file:///home/dev/workspace/glowroot/ui/app/hbs/trace.hbs)

**What changed**: Added `duplicate-queries` to the attributes rendering block, with the same expand/collapse formatting as `n-plus-one-queries`.

**Before**:
```handlebars
{{#ifEq key "n-plus-one-queries"}}
  ... expander widget ...
{{else}}
  <div class="gt-trace-attr-value">{{value}}</div>
{{/ifEq}}
```

**After**:
```handlebars
{{#ifEq key "n-plus-one-queries"}}
  ... expander widget ...
{{else}}{{#ifEq key "duplicate-queries"}}
  ... same expander widget ...
{{else}}
  <div class="gt-trace-attr-value">{{value}}</div>
{{/ifEq}}{{/ifEq}}
```

Both `n-plus-one-queries` and `duplicate-queries` use:
- `gt-unexpanded-content` — collapsed view via `{{shortenQuery value}}`
- `gt-expanded-content` — expanded full SQL with clipboard button
- `gt-expanded-nplusone-query` — pre-wrap formatted SQL class

---

### Transaction Attributes Set

After a transaction completes, the following attributes can be found in the Trace detail view:

| Attribute | Type | Description |
|-----------|------|-------------|
| `n-plus-one-detected` | `"true"` | At least one N+1 pattern detected |
| `n-plus-one-count` | number | Count of distinct normalized N+1 patterns |
| `n-plus-one-max-repeat` | number | Max times any single normalized query ran |
| `n-plus-one-queries` | multi-value | Raw query text + execution count, e.g. `SELECT ... WHERE id = ? [x2000]` |
| `duplicate-query-detected` | `"true"` | At least one exact duplicate detected |
| `duplicate-query-count` | number | Count of distinct exact duplicates |
| `duplicate-queries` | multi-value | Exact query + parameter values + count, e.g. `SELECT ... WHERE id = ? [1] [x5]` |

---

## Build & Packaging

The entire project is built using Maven:
```bash
mvn clean install -DskipTests
```

After the build, the agent distribution ZIP must be extracted to make the new `glowroot.jar` available:
```bash
unzip -o agent/dist/target/glowroot-agent-0.14.8-beta.3-dist.zip \
    -d agent/dist/target/
```

The central collector (`glowroot-central-0.14.8-beta.3-dist.zip`) is also updated in `central/target/`.

---

## Deployment Instructions

### Local Embedded Agent
Restart the monitored application with the updated agent:
```bash
java -jar \
  -javaagent:/home/dev/workspace/glowroot/agent/dist/target/glowroot/glowroot.jar \
  nplus1-demo-0.0.1-SNAPSHOT.jar
```
Then hard-refresh the Glowroot UI at `http://localhost:4000` with `Ctrl + Shift + R`.

### Glowroot Central
Replace the central collector ZIP on the server with the newly built:
```
central/target/glowroot-central-0.14.8-beta.3-dist.zip
```
Restart the central collector service after deployment.

---

## How to Verify

1. Trigger a transaction that issues the same SQL query 2000+ times with **different IDs** (e.g., Hibernate lazy loading in a loop).
   - ✅ Should see `n-plus-one-detected: true` and `n-plus-one-queries` in trace attributes.

2. Trigger a transaction that issues the exact same SQL with the **same parameters** 3+ times.
   - ✅ Should see `duplicate-query-detected: true` and `duplicate-queries` in trace attributes.

3. Click the query text in the attributes panel.
   - ✅ Should expand into a full, formatted SQL with clipboard icon.

---

## Filtering Traces by N+1 / Duplicate Queries

In the **Transactions → Traces** view, use the attribute filter:
- **Name**: `n-plus-one-detected` → **Value**: `true`
- **Name**: `duplicate-query-detected` → **Value**: `true`

This allows targeting and analyzing only affected transactions across the application.
