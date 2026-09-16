# N+1 Query Detection — Setup & Usage Guide

Glowroot includes built-in detection for **N+1 query patterns** and **duplicate query calls** within API requests.
This feature works with both the **embedded agent** (standalone JAR) and **Glowroot Central**.

---

## Table of Contents

- [Overview](#overview)
- [How N+1 Detection Works](#how-n1-detection-works)
- [Setup — Embedded Agent (Standalone JAR)](#setup--embedded-agent-standalone-jar)
- [Setup — Glowroot Central](#setup--glowroot-central)
- [Configuration Options](#configuration-options)
- [Using the N+1 Dashboard](#using-the-n1-dashboard)
- [Setting Up Alerts](#setting-up-alerts)
- [Understanding Trace Attributes](#understanding-trace-attributes)
- [FAQ & Troubleshooting](#faq--troubleshooting)

---

## Overview

An **N+1 query problem** occurs when an application executes one query to retrieve a list of records,
then executes N additional queries — one for each record — to fetch related data. This is one of the
most common performance anti-patterns in database-backed applications.

Example of an N+1 pattern:
```sql
-- 1 query to get all orders
SELECT * FROM orders WHERE user_id = 123;

-- N queries, one per order, to get order items
SELECT * FROM order_items WHERE order_id = 1;
SELECT * FROM order_items WHERE order_id = 2;
SELECT * FROM order_items WHERE order_id = 3;
-- ... repeated N times
```

Glowroot's N+1 detection automatically identifies these patterns in your JDBC calls and tags the
affected transactions with attributes that you can analyze via the dashboard and alerting system.

---

## How N+1 Detection Works

1. **Query Recording**: Every SQL statement executed via JDBC (`Statement.execute*`, `PreparedStatement.execute*`)
   is recorded by the JDBC plugin within the scope of the current transaction.

2. **Query Normalization**: SQL parameters are stripped from queries to group identical query structures.
   For example, `SELECT * FROM items WHERE id = 1` and `SELECT * FROM items WHERE id = 2` are treated
   as the same normalized query.

3. **Boundary Analysis**: When a JDBC `Connection.close()` or `Connection.commit()` is called, the detector
   analyzes all recorded queries for the current transaction:
   - If any normalized query was executed more than the **N+1 threshold** (default: 5), it is flagged.
   - If any exact (non-normalized) query was executed more than the **duplicate threshold** (default: 3), it is flagged.

4. **Attribute Tagging**: Flagged transactions are tagged with custom trace attributes (e.g.,
   `n-plus-one-detected=true`) that appear in trace headers and can be filtered in the dashboard.

---

## Setup — Embedded Agent (Standalone JAR)

### Step 1: Add the Glowroot Agent

Add the agent to your application's JVM args:

```bash
java -javaagent:path/to/glowroot.jar -jar your-application.jar
```

### Step 2: Access the Glowroot UI

Navigate to:

```
http://localhost:4000
```

### Step 3: Configure N+1 Detection

1. Go to **Configuration** → **Plugins** → **JDBC Plugin**
2. You will see these N+1 related options:
   - **N+1 query detection** (checkbox) — Enabled by default
   - **N+1 threshold** — Minimum repeated executions of the same normalized query to flag (default: 5)
   - **Duplicate query threshold** — Minimum repeated executions of the same exact query to flag (default: 3)
3. Adjust thresholds as needed and click **Save changes**

### Step 4: View the Dashboard

Click the **"N+1 Queries"** tab in the top navigation bar to see the dashboard with:
- Total N+1 detections count
- Affected transaction types count
- Timeline chart of occurrences
- Table of top offenders (click any row to drill into traces)

---

## Setup — Glowroot Central

### Step 1: Deploy Glowroot Central

Follow the [Glowroot Central installation guide](https://github.com/glowroot/glowroot/wiki/Central-Collector-Installation)
to set up the central collector with Cassandra.

### Step 2: Connect Agents

Configure each agent to report to central:

```properties
# In glowroot/glowroot.properties
collector.address=http://central-host:8181
```

### Step 3: Access the Central UI

Navigate to your central collector URL (e.g., `http://central-host:4000`).

### Step 4: Configure N+1 Detection

1. Select the agent from the dropdown in the navigation bar
2. Go to **Configuration** → **Plugins** → **JDBC Plugin**
3. Enable and configure the N+1 detection thresholds (same options as embedded)

### Step 5: Use the N+1 Dashboard

1. Click **"N+1 Queries"** in the top navigation bar
2. Select the agent/rollup from the dropdown
3. Choose a transaction type and time range
4. Review the summary cards, timeline, and offender table

---

## Configuration Options

These options are found in the JDBC plugin configuration:

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| **N+1 query detection** | Boolean | `true` | Enable or disable N+1 query pattern detection |
| **N+1 threshold** | Number | `5` | Minimum number of executions of the same normalized query within a single transaction to flag as an N+1 pattern |
| **Duplicate query threshold** | Number | `3` | Minimum number of executions of the same exact query (including parameters) within a single transaction to flag as a duplicate |

### Recommended Thresholds

| Application Type | N+1 Threshold | Duplicate Threshold | Notes |
|-----------------|---------------|---------------------|-------|
| **Strict** | 3 | 2 | Catch all potential N+1 patterns early |
| **Balanced** (default) | 5 | 3 | Good balance between sensitivity and noise |
| **Relaxed** | 10 | 5 | Use in high-query applications to reduce false positives |

---

## Using the N+1 Dashboard

### Summary Cards

At the top of the dashboard you will see:
- **Total N+1 Detections**: The number of transactions containing N+1 query patterns in the selected time range
- **Affected Transaction Types**: The count of distinct transactions impacted

### Timeline Chart

The timeline chart shows the frequency of N+1 detections over time. Use the controls to:
- **Zoom out** — See a wider time range
- **Refresh** — Reload data
- **Change time range** — Use the time range selector in the top-right corner

### Top Offenders Table

The table lists transactions ranked by detection count:

| Column | Description |
|--------|-------------|
| **Transaction Name** | The name of the affected transaction (e.g., `GET /api/orders`) |
| **Detections Count** | Number of times N+1 patterns were detected |
| **Avg Duration (ms)** | Average response time of affected transactions |
| **Last Detected** | Timestamp of the most recent detection |

**Click any row** to navigate to the trace search filtered by `n-plus-one-detected=true` for that transaction,
where you can inspect individual traces and see the actual query patterns.

---

## Setting Up Alerts

Glowroot supports built-in alerting for N+1 detections. You can be notified via **email**, **PagerDuty**,
or **Slack** when N+1 patterns are detected.

### Step 1: Navigate to Alert Configuration

1. Go to **Configuration** → **Alerts**
2. Click **+ Add**

### Step 2: Configure the Alert Condition

1. Under **Metric**, select one of:
   - **transaction: N+1 query count** — Alerts when the number of N+1 detections exceeds a threshold
   - **transaction: duplicate query count** — Alerts when the number of duplicate query detections exceeds a threshold
2. Set the **Transaction type** (e.g., "Web")
3. Optionally set a **Transaction name** to scope the alert to a specific endpoint
4. Set the **Threshold** — The minimum count to trigger an alert (e.g., alert when count > 5)
5. Set the **Time period** — The rolling window to evaluate (e.g., last 5 minutes)

### Step 3: Configure Notifications

#### Email

1. Under **Notification**, select **Email**
2. Enter one or more email addresses
3. Make sure SMTP is configured under **Administration** → **SMTP**

#### PagerDuty

1. Under **Notification**, select **PagerDuty**
2. Enter your PagerDuty integration key

#### Slack

1. Under **Notification**, select **Slack**
2. Select the webhook and channel
3. Make sure Slack webhooks are configured under **Administration** → **Integrations**

### Step 4: Save

Click **Save** to activate the alert. Glowroot will continuously evaluate the condition and send
notifications when the threshold is breached.

### Example Alert Configurations

**Alert on any N+1 detection (strict)**:
- Metric: `transaction: N+1 query count`
- Transaction type: `Web`
- Threshold: `> 0`
- Time period: `5 minutes`

**Alert on frequent N+1 patterns (production)**:
- Metric: `transaction: N+1 query count`
- Transaction type: `Web`
- Threshold: `> 10`
- Time period: `15 minutes`

**Alert on duplicate queries in a specific endpoint**:
- Metric: `transaction: duplicate query count`
- Transaction type: `Web`
- Transaction name: `/api/orders`
- Threshold: `> 5`
- Time period: `10 minutes`

---

## Understanding Trace Attributes

When N+1 patterns are detected, the following attributes are added to the trace:

| Attribute | Description | Example Value |
|-----------|-------------|---------------|
| `n-plus-one-detected` | Whether an N+1 pattern was detected | `true` |
| `n-plus-one-count` | Number of distinct N+1 query patterns found | `2` |
| `n-plus-one-queries` | The normalized queries that triggered detection | `SELECT * FROM items WHERE order_id = ?` |
| `n-plus-one-max-repeat` | The maximum number of times a single query was repeated | `47` |
| `duplicate-query-detected` | Whether duplicate exact queries were detected | `true` |
| `duplicate-query-count` | Number of distinct duplicate query patterns | `3` |
| `duplicate-queries` | The queries that triggered duplicate detection | `SELECT * FROM items WHERE order_id = ?` |

You can filter traces by these attributes in the **Traces** tab:
1. Go to **Transactions** → **Traces**
2. Under filter options, set:
   - **Attribute name**: `n-plus-one-detected`
   - **Comparator**: `equals`
   - **Attribute value**: `true`
3. Click **Search** to find all traces with N+1 patterns

---

## FAQ & Troubleshooting

### Q: I don't see the N+1 Queries tab in the navigation bar

**A**: Make sure you are using a version of Glowroot that includes the N+1 detection plugin.
If you're on the embedded agent, the tab should appear automatically. In Central mode,
select an agent first from the dropdown.

### Q: N+1 patterns are not being detected even though I have repeated queries

**A**: Check the following:
1. Ensure the JDBC plugin's "N+1 query detection" checkbox is **enabled** in Configuration → Plugins → JDBC Plugin
2. Verify the thresholds — the default N+1 threshold is 5, meaning a query must be executed
   5+ times in a single transaction to be flagged. Lower the threshold if needed.
3. Ensure your application uses standard JDBC (`java.sql.Connection`, `java.sql.Statement`,
   `java.sql.PreparedStatement`). ORM frameworks like Hibernate and JPA work through JDBC
   and are supported.

### Q: Can I detect N+1 patterns in batch jobs or background tasks?

**A**: Yes. N+1 detection works on any transaction type, not just HTTP requests. Configure
your transaction type in the alert and dashboard filters.

### Q: How does detection affect performance?

**A**: The overhead is minimal. Query strings are stored in a thread-local map during the
transaction and analyzed once at the connection boundary (`close()` or `commit()`). The
normalization uses a lightweight regex replacement. No additional database queries are
made by the detection logic.

### Q: Can I disable detection for specific transactions?

**A**: Currently, detection is enabled/disabled globally per agent through the JDBC plugin
configuration. You can use threshold tuning to reduce false positives for high-query transactions.
