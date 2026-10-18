# AI Insights (central collector)

AI Insights evaluates stored telemetry locally, persists sustained incidents, and optionally
asks one configured provider for advisory analysis. It never calls a provider from agent
instrumentation, application request threads, collector ingestion, or UI request threads.
It never executes recommendations or changes severity/lifecycle from model output.

## Deployment and architecture

Supported: central collector on Java 17+, with Cassandra and the existing AngularJS UI.
Embedded/offline deployments do not run detection or vendor workers. Shared contracts,
configuration, detection, evidence, and telemetry adapters in `common2` remain Java 8
compatible; an embedded storage adapter and scheduler would be needed for future support.
No provider SDK or new runtime dependency was introduced.

Integration points inspected:

* `common`: existing aggregate collectors, trace models, and wire models.
* `common2`: aggregate, query, external service, gauge, profile, trace and configuration
  repository contracts. The new `ai` package reuses these contracts. Profiling repositories
  were inspected but profiles are not exported in this slice.
* `central`: `CentralModule` owns scheduler/worker startup and shutdown. `InsightsDao`
  creates a dedicated `ai_insight` Cassandra table. Existing alert incidents encode configured
  protobuf alert conditions and do not have acknowledgement, evidence, or analysis semantics,
  so existing alert notification/lifecycle behavior is unchanged.
* `agent/embedded`: its H2 incident repository is not used by AI Insights.
* JDBC `NplusOneDetector` already tags/forces capture of suspected N+1 transactions;
  `NplusOneJsonService` searches those attributes. AI Insights reads the same stored
  `n-plus-one-detected=true` traces. Enable JDBC `detectNplusOneQueries` for this signal.
* `UiModule` registers `AiInsightsJsonService`; the existing backend permission dispatcher
  and AngularJS routing/polling are reused.

```
stored aggregates / traces / gauges
  -> single central scheduling thread
  -> local rules and sustained evaluation state
  -> Cassandra incident compare-and-set
  -> bounded worker queue
  -> selected provider, bounded retries/budget
  -> validated JSON persisted alongside its original evidence
  -> permission-checked UI polling every 30 seconds
```

Cassandra keys are `(agent, fingerprint)`. Fingerprints include agent, transaction type/name,
issue, and normalized SQL/service identity where applicable. Error-rate findings aggregate
all errors for the transaction; they do not claim individual exception causality. A separate
partition holds non-secret configuration, evaluation leases and hourly request-budget counters.
All mutations use lightweight transactions; revision checks prevent stale writers overwriting
acknowledgements or newer evidence. Agent evaluation leases coordinate nodes; persisted
`evaluatedThrough` additionally prevents repeated scheduling from counting one minute twice.
Analysis jobs use a persisted unique token and lease, rechecked by the worker. Completion
merges into the current incident only when the token matches. Local queues never use
caller-runs rejection. Cluster clocks must be synchronized.

Pending queue claims expire after five minutes. Running claims expire after 30 minutes;
crashed-node jobs become eligible again after that lease. A stale completion cannot overwrite
a newer job. Provider delivery is not exactly-once across a process crash or arbitrary JVM
pause; a remote request may have succeeded before its local result was saved.

## Configuration

Both `enabled` (local detection) and `analysisEnabled` default to false. Open **AI Insights**,
select a leaf agent ID, then **Administrator configuration**. This editor loads/saves the
complete validated JSON configuration. Administrator edit permission is required. Specify a
model available in your vendor account; no model alias is silently selected for you.

Set credentials on **every central node**, outside the repository:

```sh
export OPENAI_API_KEY='...'
# Only needed when selecting Anthropic or explicitly enabling fallback:
export ANTHROPIC_API_KEY='...'
# Optional. Gemini Developer API has a free tier on supported models, with quotas:
export GEMINI_API_KEY='...'
```

API keys are not configuration fields, are never returned to the browser, and are never
included in provider error logs. AI action payloads are omitted from the existing audit body
logging. Keep credentials in your deployment's secret manager/environment injection.

For local-only operation set `enabled: true`, `analysisEnabled: false`. For AI analysis also
set `analysisEnabled: true`, `provider: "openai"`, `"anthropic"` or `"gemini"`, and `model`.
Gemini calls the official Gemini Developer API using `x-goog-api-key`, asks for JSON output,
and validates the same strict incident-analysis contract used by the other providers. Google
offers a no-charge Gemini API tier for eligible models/regions, but it is quota-limited and
requires a Google AI Studio API key; it is not an Antigravity session login. Check Google's
current [billing and free-tier details](https://ai.google.dev/gemini-api/docs/billing) before
enabling this provider.

Provider account-login limitations: OpenAI documents Sign in with ChatGPT plan usage for
open-source/local clients and self-hosted VMs, but a self-hosted VM flow requires completing
OAuth locally and securely transferring credentials; it is not a central server browser-login
flow. We therefore do not accept ChatGPT web/Codex session cookies or credentials here. See
[OpenAI's open-source flow](https://developers.openai.com/siwc/token-sharing-open-source) and
[self-hosted VM guidance](https://developers.openai.com/siwc/token-sharing-open-source/self-hosted-vms).
Anthropic's documented Claude subscription integration can trigger a preconfigured Claude Code
routine, but returns a session reference rather than the model output, so it cannot satisfy this
incident-analysis contract. We do not scrape Claude/Antigravity sessions or reuse private app
tokens. Use official API credentials, or Gemini's available free tier.
The provider connection test is queued, uses synthetic evidence only, consumes the same
request budget, and exercises response validation. Refresh test status to see the result.

| Setting | Default | Meaning |
|---|---:|---|
| intervalSeconds | 30 | Scheduler target; completed minute endpoints are evaluated once |
| recentMinutes | 5 | Recent window |
| baselineMinutes | 60 | Immediately preceding window, excluding recent window |
| ingestionDelaySeconds | 120 | Delay allowance before aligning to a completed minute |
| minimumSamples | 100 | Recent and baseline transaction samples |
| minimumQuerySamples | 10 | Recent and baseline SQL/service executions |
| sustainedEvaluations | 3 | Consecutive distinct anomalous minute evaluations to open/reopen |
| healthyEvaluations | 3 | Consecutive healthy evaluations to resolve |
| cooldownSeconds | 600 | Minimum interval between attempts for an incident, including manual requests |
| errorRate | 0.05 | Fraction of transactions with errors |
| latencyMillis | 1000 | Mean transaction duration absolute threshold |
| queryMillis | 100 | Mean SQL/external-call duration absolute threshold |
| baselineMultiplier | 2 | Relative comparison, in addition to absolute thresholds |
| expectedTraffic | false | Explicit operator evidence that traffic should continue |
| throughputDropRatio | 0.25 | Current transactions/minute below this fraction of baseline |
| cpuRatio / heapRatio | 0.9 / 0.9 | CPU load / used-to-max heap pressure |
| gcMillisPerSecond | 200 | Per-collector GC counter rate threshold |
| threadCount | 500 | Thread count threshold, also requiring baseline increase |
| concurrency / queueCapacity | 2 / 100 | Per-node workers / waiting jobs; restart nodes after changing |
| timeoutSeconds | 20 | Per-request wall deadline plus socket timeouts |
| requestsPerHour | 60 | Cluster-wide fixed-hour cap, including retries, fallback and tests |
| maxOutputTokens | 1500 | Output generation cap per request |
| maxPayloadBytes | 12000 | Serialized request byte limit, including analysis instructions |
| retentionDays | 30 | Incident inactivity TTL; resolved TTL counts from resolution |
| fallbackEnabled / fallbackModel | false / empty | Explicit permission and model for the other vendor |
| excludedFields | [] | Top-level evidence fields to omit before export |

Examples of field exclusions: `agent`, `transaction`, `capturedTraces`, `identity`.
Excluding `id` prevents validated analysis and is rejected by the worker. It does not affect
local monitoring. Configuration changes apply on subsequent scheduling/worker runs; running
requests may complete using the configuration with which they started.

The theoretical default attempt ceiling is 60 requests/hour across the cluster. One sustained
incident is normally eligible approximately six times/hour. Retries and tests use that same
budget. Each request caps output at 1,500 tokens and input at 12,000 UTF-8 bytes (a conservative
input-token upper bound for the byte-level tokenizers used by these APIs; not a token estimate).
There is no billing-price estimator. A fixed-hour boundary can admit two adjacent hourly
budgets in a short period. Use vendor-side spending/rate limits as well.

## Detection and lifecycle semantics

The default recent/baseline windows require at least 80% minute coverage as well as minimum
counts. Missing/old aggregates, cold starts, negative/non-finite measurements and insufficient
traffic are **insufficient data**. They reset sustained/healthy counters; they never resolve
an incident. Zero baselines can trigger an absolute upward anomaly only when enough samples
exist. Throughput drops require `expectedTraffic`, positive baseline traffic and observed
recent aggregate coverage. Total telemetry disappearance is not classified as zero traffic.

Latency values are weighted means from total duration/count, not percentiles. No percentile
is fabricated or averaged. This slice uses native minute aggregates, not higher rollups;
if minute data has expired, it reports insufficient data. Late data arriving after an endpoint
was evaluated does not increment that endpoint again. The delay allowance is configurable,
and rollup/ingestion delays and evaluation workload can push detection beyond scheduling targets.

Scope is leaf agents, overall transaction types, and the top 20 transaction names by traffic
across the combined window. Query/service collectors retain the top 25 by duration. GC considers
up to five collectors. A leaf evaluation stops starting new transaction work after two minutes;
repository reads have 30-second waits. Large installations should measure this overhead before
expanding scope. There is a 1,000-document per-agent cap including candidate detector state;
new fingerprints are suppressed at capacity, existing findings continue to update. The UI
reports the cap when reached. This is an intentionally bounded first slice, not exhaustive
low-traffic transaction coverage.

SQL literals/comments/quoted identifiers are normalized and the result is hashed. The
normalized query digest is used for correlation; full/truncated query text is not sent to a
provider. Long queries with equal captured prefixes can share a fingerprint. N+1 counts are
captured-trace counts capped at 100, not all database executions. Disabling the JDBC detector
makes absent N+1 evidence insufficient rather than healthy.

External service durations use collected count/timing statistics. JVM rules use known JMX
CPU load, heap used/max, thread count and GC collection-time gauges. Gauge averages are weighted
by stored gauge weight; negative counter deltas invalidate the window to handle resets.
Heap/CPU rules require the absolute pressure threshold and a non-decreasing baseline; thread/GC
rules also require the configured multiplier. Unavailable gauges remain insufficient data.
Generic utilization never establishes a memory leak, deadlock or exhausted pool.

Lifecycle states are open, acknowledged and resolved. First seen is the first sustained
trigger; last seen and occurrence count advance on each subsequent anomalous evaluation.
Reopening preserves first seen/count and returns to open after sustained recurrence. Resolution
requires healthy evaluations; no-traffic disappearance does not imply recovery. Candidate
state is persisted before opening, allowing sustained detection to survive node changes.
Expired history starts a new lifecycle when the fingerprint recurs.

## Evidence, privacy and analysis

Default export uses a strict construction allowlist: numeric measurements, timestamps,
counts, fixed local rule names and SHA-256 identities. Agent/transaction strings, raw SQL,
error messages, stack text, headers, cookies, authorization tokens, URL query values, bodies,
bind values, and request attributes never enter vendor payloads. This suppresses sensitive
fields rather than trying to detect every secret using a regex. Hashes are stable pseudonyms,
not proof of anonymization against guessing known identifiers.

Up to three representative captured N+1/error trace headers contribute duration/time, opaque trace
identities, and opaque exception/stack-frame identities where available. This is deliberately
less descriptive than raw stack traces. The UI links to existing trace, query, JVM and N+1
screens for human investigation. It does not export profiles or request content. Each evidence
bundle has a stable ID scoped to agent, transaction, rule, identity and endpoint. Completed
analysis retains the exact exported evidence snapshot even when local evidence advances.

All telemetry is treated as data. Separate system/developer instructions demand advisory JSON;
there are no tools. Validation enforces exactly the documented fields, bounded strings/lists,
causes with evidence references or `hypothesis: true`, and known evidence IDs only. It rejects
malformed/trailing/duplicate JSON, refusals, incomplete provider responses, and oversized output.
Structural validation cannot prove a model's claim is true: recommendations require human review.
AngularJS text bindings render output; no raw model HTML is inserted.

OpenAI uses `POST https://api.openai.com/v1/responses` with `store:false`, instructions, input,
and `max_output_tokens`. Anthropic uses `POST https://api.anthropic.com/v1/messages` with
`anthropic-version: 2023-06-01`, a system message, user evidence and `max_tokens`. Gemini uses
`POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent` with
`x-goog-api-key`, a separate system instruction, and JSON response mode.
Official references consulted:
[OpenAI text generation](https://developers.openai.com/api/docs/guides/text),
[OpenAI Responses reference](https://developers.openai.com/api/reference/resources/responses/methods/create),
[Anthropic Messages](https://platform.claude.com/docs/en/api/messages/create).
No SDK dependency is required. Destinations are fixed HTTPS URLs; redirects are disabled.

Workers retry network failures, HTTP 408/429 and 5xx at most twice with exponential backoff,
jitter and a bounded numeric Retry-After delay. Three failed analyses open a node-local circuit
for one minute. Payloads, credentials and vendor error bodies are not logged. Provider failure
leaves the incident intact with failed/unavailable status. A full queue leaves an unavailable
finding eligible for a later scheduled attempt. Cross-provider fallback is off by default.

## Permissions and manual verification

Reads require all of `agent:transaction:overview`, `agent:transaction:queries`,
`agent:transaction:traces`, `agent:error:overview` and `agent:jvm:gauges` for the requested
leaf agent. Actions additionally require `agent:config:edit:alerts`. Configuration and connection
tests require `admin:edit`. An incident ID cannot be used to bypass the agent partition.
AI POST routes require `X-Glowroot-AI: 1`; no cross-origin access is enabled, so a foreign web
page cannot supply this non-simple header without a successful preflight. The frontend sends
it automatically. As with other deployment security, do not configure a proxy to allow
untrusted cross-origin credentialed access.

1. Start central with Cassandra and an instrumented leaf agent. Allow baseline telemetry to
   accumulate; confirm AI Insights shows available versus insufficient sources.
2. Enable local detection only. Generate sustained error/latency or slow SQL load. Confirm an
   incident opens after distinct eligible endpoints, then acknowledge it. Repeat evaluations
   and confirm it updates rather than duplicates.
3. Restore healthy traffic for the configured healthy evaluations; confirm resolution.
   Reintroduce sustained load; confirm reopening with the same fingerprint.
4. Enable a configured provider/model and credentials; run the synthetic connection test.
   Request reanalysis after cooldown. Inspect advisory JSON and its original evidence ID.
5. Remove credentials or block vendor access. Local findings must continue; analysis reports
   unavailable/failed. Lower the request cap and confirm budget failure without loss of incidents.
6. Use an account limited to another agent; both read and action access must fail. Try a POST
   without the custom header; it must return 403. Try telemetry containing instructions or
   secrets; inspect a mocked provider's request to confirm only allowlisted evidence leaves.
7. Run two central nodes against one Cassandra cluster and generate the same signal; confirm
   one fingerprint, distinct-minute occurrence counts, and one active job claim. Restart the
   worker owner and verify recovery after claim expiry.

## Verification commands

JDK 21 was selected for local Maven execution (modules retain their declared source/target).
Focused tests use mocks and a loopback HTTP stub; no paid vendor requests are made.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -pl central,agent/embedded -am test \
  -Dtest=InsightsTest,InsightsTelemetryTest,InsightsServiceTest,ProviderTest,AiInsightsJsonServiceTest,CommonHandlerTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dglowroot.ui.skip=true -q
cd ui
npm install --ignore-scripts --no-package-lock --legacy-peer-deps --fetch-retries=0
node node_modules/bower/bin/bower install
node node_modules/grunt-cli/bin/grunt jshint build
```

The Cassandra integration test `InsightsDaoIT` requires Docker, matching the existing central
test infrastructure. It was compiled but not executed locally because Docker is unavailable.
Live provider interoperability, a running central UI, and real multi-node failover require the
manual checks above. The complete Grunt frontend build passed (lint, templates, Sass, CSS/JS minification and assets).
The final Maven run passed 25 tests: 16 AI-focused tests and 9 existing request-handler tests.
