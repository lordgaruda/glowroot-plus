/*
 * Copyright 2018-2023 the original author or authors.
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
package org.glowroot.central.repo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.central.util.MoreExecutors2;
import org.glowroot.common2.config.ImmutableCentralStorageConfig;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ImmutableCassandraDbStats;
import org.glowroot.common2.repo.ImmutableCassandraNodeStats;
import org.glowroot.common2.repo.ImmutableCassandraTableStats;
import org.glowroot.common2.repo.ImmutableTracePruneStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.util.CassandraWriteMetrics;
import org.glowroot.central.util.Session;
import org.glowroot.common.Constants;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.config.CentralStorageConfig;
import org.glowroot.common2.repo.RepoAdmin;

import static java.util.concurrent.TimeUnit.HOURS;

public class RepoAdminImpl implements RepoAdmin {

    private static final Logger logger = LoggerFactory.getLogger(RepoAdminImpl.class);

    private static final List<String> TRACE_TABLE_NAMES = ImmutableList.of(
            "trace_tt_slow_count",
            "trace_tt_slow_count_partial",
            "trace_tn_slow_count",
            "trace_tn_slow_count_partial",
            "trace_tt_slow_point",
            "trace_tt_slow_point_partial",
            "trace_tn_slow_point",
            "trace_tn_slow_point_partial",
            "trace_tt_error_count",
            "trace_tn_error_count",
            "trace_tt_error_point",
            "trace_tn_error_point",
            "trace_tt_error_message",
            "trace_tn_error_message",
            "trace_header",
            "trace_entry",
            "trace_shared_query_text",
            "trace_main_thread_profile",
            "trace_aux_thread_profile",
            "trace_header_v2",
            "trace_entry_v2",
            "trace_query_v2",
            "trace_shared_query_text_v2",
            "trace_main_thread_profile_v2",
            "trace_aux_thread_profile_v2",
            "trace_attribute_name"
    );

    private final Session session;
    private final ActiveAgentDao activeAgentDao;
    private final ConfigRepositoryImpl configRepository;
    private final CassandraWriteMetrics cassandraWriteMetrics;
    private final Clock clock;

    private final ExecutorService pruneExecutor =
            MoreExecutors2.newSingleThreadExecutor("Glowroot-Trace-Pruner-%d");
    private final AtomicBoolean cancelPruningRequested = new AtomicBoolean(false);

    private volatile TracePruneStatus pruneStatus = ImmutableTracePruneStatus.builder()
            .running(false)
            .action("None")
            .days(0)
            .deletedCount(0)
            .errorCount(0)
            .currentStep("Idle")
            .build();

    public RepoAdminImpl(Session session, ActiveAgentDao activeAgentDao,
            ConfigRepositoryImpl configRepository, CassandraWriteMetrics cassandraWriteMetrics,
            Clock clock) {
        this.session = session;
        this.activeAgentDao = activeAgentDao;
        this.configRepository = configRepository;
        this.cassandraWriteMetrics = cassandraWriteMetrics;
        this.clock = clock;
    }

    @Override
    public void runHealthCheck() throws Exception {
        // Cheap Cassandra liveness probe (fails when ControlConnection / all nodes are down).
        session.read("select release_version from system.local where key = 'local'",
                CassandraProfile.web);
        long now = clock.currentTimeMillis();
        // Must wait: previously the CompletionStage was ignored, so /health always returned 200
        // even when Cassandra was unreachable (see github.com/glowroot/glowroot/issues/766).
        activeAgentDao.readActiveTopLevelAgentRollups(now - HOURS.toMillis(4), now, CassandraProfile.web)
                .toCompletableFuture().get();
    }

    @Override
    public void defragH2Data() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void compactH2Data() {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getH2DataFileSize() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<H2Table> analyzeH2DiskSpace() {
        throw new UnsupportedOperationException();
    }

    @Override
    public TraceCounts analyzeTraceCounts() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllData() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void resizeIfNeeded() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int updateCassandraTwcsWindowSizes() throws Exception {
        CentralStorageConfig storageConfig = configRepository.getCentralStorageConfig().toCompletableFuture().join();
        List<String> tableNames = new ArrayList<>();
        for (TableMetadata table : session.getTables()) {
            Map<String, String> compaction = (Map<String, String>) table.getOptions()
                    .getOrDefault(CqlIdentifier.fromInternal("compaction"), Collections.emptyMap());
            String compactionClass = compaction.get("class");
            if (compactionClass == null || !compactionClass
                    .equals("org.apache.cassandra.db.compaction.TimeWindowCompactionStrategy")) {
                continue;
            }
            String actualWindowUnit =
                    compaction.get("compaction_window_unit");
            String actualWindowSize =
                    compaction.get("compaction_window_size");
            int expirationHours = getExpirationHoursForTable(table.getName().asInternal(), storageConfig);
            if (expirationHours == -1) {
                // warning already logged above inside getExpirationHoursForTable()
                continue;
            }
            int windowSizeHours = Session.getCompactionWindowSizeHours(expirationHours);
            if (!"HOURS".equals(actualWindowUnit)
                    || !Integer.toString(windowSizeHours).equals(actualWindowSize)) {
                tableNames.add(table.getName().asInternal());
            }
        }
        int updatedTableCount = 0;
        for (String tableName : tableNames) {
            int expirationHours =
                    RepoAdminImpl.getExpirationHoursForTable(tableName, storageConfig);
            if (expirationHours == -1) {
                // warning already logged above inside getExpirationHoursForTable()
                continue;
            }
            session.updateTableTwcsProperties(tableName, expirationHours);
            updatedTableCount++;
        }
        return updatedTableCount;
    }

    @Override
    public List<CassandraWriteTotals> getCassandraWriteTotalsPerTable(int limit) {
        return cassandraWriteMetrics.getCassandraDataWrittenPerTable(limit);
    }

    @Override
    public List<CassandraWriteTotals> getCassandraWriteTotalsPerAgentRollup(String tableName,
            int limit) {
        return cassandraWriteMetrics.getCassandraDataWrittenPerAgentRollup(tableName, limit);
    }

    @Override
    public List<CassandraWriteTotals> getCassandraWriteTotalsPerTransactionType(
            String tableName, String agentRollupId, int limit) {
        return cassandraWriteMetrics.getCassandraDataWrittenPerTransactionType(tableName,
                agentRollupId, limit);
    }

    @Override
    public List<CassandraWriteTotals> getCassandraWriteTotalsPerTransactionName(
            String tableName, String agentRollupId, String transactionType, int limit) {
        return cassandraWriteMetrics.getCassandraDataWrittenPerTransactionName(tableName,
                agentRollupId, transactionType, limit);
    }

    static int getExpirationHoursForTable(String tableName,
            CentralStorageConfig storageConfig) {
        if (tableName.startsWith("trace_")) {
            return storageConfig.traceExpirationHours();
        } else if (tableName.startsWith("gauge_value_rollup_")) {
            int rollupLevel = Integer.parseInt(tableName.substring(tableName.lastIndexOf('_') + 1));
            if (rollupLevel == 0) {
                return storageConfig.rollupExpirationHours().get(rollupLevel);
            } else {
                return storageConfig.rollupExpirationHours().get(rollupLevel - 1);
            }
        } else if (tableName.startsWith("aggregate_tt_query_")
                || tableName.startsWith("aggregate_tn_query_")
                || tableName.startsWith("aggregate_tt_service_call_")
                || tableName.startsWith("aggregate_tn_service_call_")) {
            int rollupLevel = Integer.parseInt(tableName.substring(tableName.lastIndexOf('_') + 1));
            return storageConfig.queryAndServiceCallRollupExpirationHours().get(rollupLevel);
        } else if (tableName.startsWith("aggregate_tt_main_thread_profile_")
                || tableName.startsWith("aggregate_tn_main_thread_profile_")
                || tableName.startsWith("aggregate_tt_aux_thread_profile_")
                || tableName.startsWith("aggregate_tn_aux_thread_profile_")) {
            int rollupLevel = Integer.parseInt(tableName.substring(tableName.lastIndexOf('_') + 1));
            return storageConfig.profileRollupExpirationHours().get(rollupLevel);
        } else if (tableName.startsWith("aggregate_")
                || tableName.startsWith("synthetic_result_")
                || tableName.startsWith("active_agent_")) {
            int rollupLevel = Integer.parseInt(tableName.substring(tableName.lastIndexOf('_') + 1));
            return storageConfig.rollupExpirationHours().get(rollupLevel);
        } else if (tableName.equals("gauge_name") || tableName.equals("synthetic_monitor_id")) {
            return getMaxRollupExpirationHours(storageConfig);
        } else if (tableName.equals("heartbeat")) {
            return HeartbeatDao.EXPIRATION_HOURS;
        } else if (tableName.equals("resolved_incident")) {
            return Constants.RESOLVED_INCIDENT_EXPIRATION_HOURS;
        } else {
            logger.warn("unexpected table: {}", tableName);
            return -1;
        }
    }

    private static int getMaxRollupExpirationHours(CentralStorageConfig storageConfig) {
        int maxRollupExpirationHours = 0;
        for (int expirationHours : storageConfig.rollupExpirationHours()) {
            if (expirationHours == 0) {
                // zero value expiration/TTL means never expire
                return 0;
            }
            maxRollupExpirationHours = Math.max(maxRollupExpirationHours, expirationHours);
        }
        return maxRollupExpirationHours;
    }

    @Override
    public CassandraDbStats getCassandraDbStats() throws Exception {
        String clusterName = "Unknown";
        String releaseVersion = "Unknown";
        String partitioner = "Unknown";
        try {
            ResultSet rs = session.read(
                    "select cluster_name, release_version, partitioner from system.local where key = 'local'",
                    CassandraProfile.web);
            Row row = rs.one();
            if (row != null) {
                if (!row.isNull("cluster_name")) {
                    clusterName = row.getString("cluster_name");
                }
                if (!row.isNull("release_version")) {
                    releaseVersion = row.getString("release_version");
                }
                if (!row.isNull("partitioner")) {
                    partitioner = row.getString("partitioner");
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to read system.local: {}", e.getMessage());
            clusterName = session.getMetadata().getClusterName().orElse("Unknown");
        }

        // Query size estimates per table
        Map<String, Long> estimatedPartitionsMap = new HashMap<>();
        Map<String, Long> estimatedBytesMap = new HashMap<>();
        try {
            ResultSet rs = session.read(
                    "select table_name, partitions_count, mean_partition_size from system.size_estimates where keyspace_name = '"
                            + session.getKeyspaceName() + "'",
                    CassandraProfile.web);
            for (Row row : rs) {
                String tableName = row.getString("table_name");
                long partitions = row.getLong("partitions_count");
                long meanSize = row.getLong("mean_partition_size");
                long bytes = partitions * meanSize;
                estimatedPartitionsMap.merge(tableName, partitions, Long::sum);
                estimatedBytesMap.merge(tableName, bytes, Long::sum);
            }
        } catch (Exception e) {
            logger.debug("Failed to read system.size_estimates: {}", e.getMessage());
        }

        // Recent write totals per table
        Map<String, CassandraWriteTotals> writesByTable = new HashMap<>();
        try {
            List<CassandraWriteTotals> writeTotals = cassandraWriteMetrics.getCassandraDataWrittenPerTable(100);
            for (CassandraWriteTotals wt : writeTotals) {
                writesByTable.put(wt.display(), wt);
            }
        } catch (Exception e) {
            logger.debug("Failed to get recent write totals: {}", e.getMessage());
        }

        long totalEstimatedBytes = 0;
        long totalEstimatedPartitions = 0;
        List<CassandraTableStats> tableStatsList = new ArrayList<>();
        for (TableMetadata table : session.getTables()) {
            String tableName = table.getName().asInternal();
            String category = categorizeTable(tableName);

            Map<CqlIdentifier, Object> options = table.getOptions();
            Map<String, String> compaction = (Map<String, String>) options.getOrDefault(
                    CqlIdentifier.fromInternal("compaction"), Collections.emptyMap());
            String compactionClass = compaction.get("class");
            String compactionStrategy = "Unknown";
            if (compactionClass != null) {
                int lastDot = compactionClass.lastIndexOf('.');
                compactionStrategy = lastDot != -1 ? compactionClass.substring(lastDot + 1) : compactionClass;
                if (compactionStrategy.endsWith("CompactionStrategy")) {
                    compactionStrategy = compactionStrategy.substring(0,
                            compactionStrategy.length() - "CompactionStrategy".length());
                }
            }

            int defaultTtlSeconds = 0;
            Object ttlObj = options.get(CqlIdentifier.fromInternal("default_time_to_live"));
            if (ttlObj instanceof Number) {
                defaultTtlSeconds = ((Number) ttlObj).intValue();
            }

            long estimatedPartitions = estimatedPartitionsMap.getOrDefault(tableName, 0L);
            long estimatedBytes = estimatedBytesMap.getOrDefault(tableName, 0L);
            totalEstimatedBytes += estimatedBytes;
            totalEstimatedPartitions += estimatedPartitions;

            CassandraWriteTotals writeTotals = writesByTable.get(tableName);
            long recentBytes = writeTotals != null ? writeTotals.bytesWritten() : 0L;
            long recentRows = writeTotals != null ? writeTotals.rowsWritten() : 0L;

            tableStatsList.add(ImmutableCassandraTableStats.builder()
                    .tableName(tableName)
                    .category(category)
                    .compactionStrategy(compactionStrategy)
                    .estimatedBytes(estimatedBytes)
                    .estimatedPartitions(estimatedPartitions)
                    .defaultTtlSeconds(defaultTtlSeconds)
                    .recentBytesWritten(recentBytes)
                    .recentRowsWritten(recentRows)
                    .build());
        }

        // Sort tables by estimatedBytes descending, then tableName
        tableStatsList.sort((a, b) -> {
            int cmp = Long.compare(b.estimatedBytes(), a.estimatedBytes());
            if (cmp != 0) {
                return cmp;
            }
            return a.tableName().compareTo(b.tableName());
        });

        // Query nodes
        List<CassandraNodeStats> nodeStatsList = new ArrayList<>();
        try {
            for (Node node : session.getMetadata().getNodes().values()) {
                String endpoint = node.getEndPoint().toString();
                String status = node.getState().name();
                String dc = node.getDatacenter() != null ? node.getDatacenter() : "Unknown";
                String rack = node.getRack() != null ? node.getRack() : "Unknown";
                String version = node.getCassandraVersion() != null
                        ? node.getCassandraVersion().toString()
                        : releaseVersion;
                nodeStatsList.add(ImmutableCassandraNodeStats.builder()
                        .endpoint(endpoint)
                        .status(status)
                        .datacenter(dc)
                        .rack(rack)
                        .releaseVersion(version)
                        .build());
            }
        } catch (Exception e) {
            logger.warn("Failed to get node metadata: {}", e.getMessage());
        }

        return ImmutableCassandraDbStats.builder()
                .clusterName(clusterName)
                .keyspaceName(session.getKeyspaceName())
                .releaseVersion(releaseVersion)
                .partitioner(partitioner)
                .totalEstimatedBytes(totalEstimatedBytes)
                .totalEstimatedPartitions(totalEstimatedPartitions)
                .nodes(nodeStatsList)
                .tables(tableStatsList)
                .build();
    }

    @Override
    public int truncateAllTraces() throws Exception {
        if (pruneStatus.running()) {
            throw new IllegalStateException("A trace prune or truncate operation is already in progress");
        }
        cancelPruningRequested.set(false);
        long startTime = clock.currentTimeMillis();
        pruneStatus = ImmutableTracePruneStatus.builder()
                .running(true)
                .action("Truncating all trace tables")
                .days(0)
                .deletedCount(0)
                .errorCount(0)
                .currentStep("Preparing tables for truncate...")
                .startTime(startTime)
                .build();

        try {
            Collection<TableMetadata> tables = session.getTables();
            List<String> traceTablesToTruncate = new ArrayList<>();
            for (TableMetadata table : tables) {
                String name = table.getName().asInternal();
                if (name.startsWith("trace_")) {
                    traceTablesToTruncate.add(name);
                }
            }
            for (String knownName : TRACE_TABLE_NAMES) {
                if (!traceTablesToTruncate.contains(knownName) && session.getTable(knownName) != null) {
                    traceTablesToTruncate.add(knownName);
                }
            }

            int truncatedCount = 0;
            for (String tableName : traceTablesToTruncate) {
                pruneStatus = ImmutableTracePruneStatus.builder()
                        .copyFrom(pruneStatus)
                        .currentStep("Truncating " + tableName + " (" + (truncatedCount + 1) + " of "
                                + traceTablesToTruncate.size() + ")...")
                        .build();
                logger.info("Truncating Cassandra trace table: {}", tableName);
                session.updateSchemaWithRetry("truncate table " + tableName);
                truncatedCount++;
            }

            pruneStatus = ImmutableTracePruneStatus.builder()
                    .running(false)
                    .action("Truncating all trace tables")
                    .days(0)
                    .deletedCount(truncatedCount)
                    .errorCount(0)
                    .currentStep("Successfully truncated " + truncatedCount + " trace tables")
                    .startTime(startTime)
                    .completedTime(clock.currentTimeMillis())
                    .build();
            return truncatedCount;
        } catch (Exception e) {
            logger.error("Error during trace truncate", e);
            pruneStatus = ImmutableTracePruneStatus.builder()
                    .copyFrom(pruneStatus)
                    .running(false)
                    .currentStep("Truncate failed: " + e.getMessage())
                    .lastError(e.getMessage())
                    .completedTime(clock.currentTimeMillis())
                    .build();
            throw e;
        }
    }

    @Override
    public void pruneTracesOlderThan(int days, boolean updateRetentionPolicy) {
        if (pruneStatus.running()) {
            throw new IllegalStateException("A trace prune or truncate operation is already in progress");
        }
        cancelPruningRequested.set(false);
        pruneExecutor.execute(() -> {
            long startTime = clock.currentTimeMillis();
            pruneStatus = ImmutableTracePruneStatus.builder()
                    .running(true)
                    .action("Pruning traces older than " + days + " days")
                    .days(days)
                    .deletedCount(0)
                    .errorCount(0)
                    .currentStep("Initializing prune...")
                    .startTime(startTime)
                    .build();

            try {
                if (updateRetentionPolicy) {
                    pruneStatus = ImmutableTracePruneStatus.builder()
                            .copyFrom(pruneStatus)
                            .currentStep("Updating storage retention policy to " + days + " days...")
                            .build();
                    CentralStorageConfig centralStorageConfig =
                            configRepository.getCentralStorageConfig().toCompletableFuture().join();
                    CentralStorageConfig updatedConfig = ImmutableCentralStorageConfig.builder()
                            .copyFrom(centralStorageConfig)
                            .traceExpirationHours(days * 24)
                            .build();
                    configRepository.updateCentralStorageConfig(updatedConfig, centralStorageConfig.version())
                            .toCompletableFuture().join();
                    updateCassandraTwcsWindowSizes();
                }

                long cutoffTime = clock.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
                pruneTracesInternal(cutoffTime);

                boolean cancelled = cancelPruningRequested.get();
                pruneStatus = ImmutableTracePruneStatus.builder()
                        .copyFrom(pruneStatus)
                        .running(false)
                        .currentStep(cancelled ? "Prune cancelled by user" : "Prune completed successfully")
                        .completedTime(clock.currentTimeMillis())
                        .build();
            } catch (Exception e) {
                logger.error("Error during trace prune", e);
                pruneStatus = ImmutableTracePruneStatus.builder()
                        .copyFrom(pruneStatus)
                        .running(false)
                        .currentStep("Prune failed: " + e.getMessage())
                        .lastError(e.getMessage())
                        .completedTime(clock.currentTimeMillis())
                        .build();
            }
        });
    }

    @Override
    public TracePruneStatus getTracePruneStatus() {
        return pruneStatus;
    }

    @Override
    public void cancelTracePruning() {
        cancelPruningRequested.set(true);
    }

    private void pruneTracesInternal(long cutoffTime) {
        Instant cutoffInstant = Instant.ofEpochMilli(cutoffTime);
        Set<PartitionKey> slowPartitions = new HashSet<>();
        Set<PartitionKey> errorPartitions = new HashSet<>();

        try {
            pruneStatus = ImmutableTracePruneStatus.builder()
                    .copyFrom(pruneStatus)
                    .currentStep("Discovering slow point partitions...")
                    .build();
            ResultSet rs = session.read("select distinct agent_rollup, transaction_type from trace_tt_slow_point",
                    CassandraProfile.slow);
            for (Row row : rs) {
                slowPartitions.add(new PartitionKey(row.getString("agent_rollup"), row.getString("transaction_type")));
            }
        } catch (Exception e) {
            logger.warn("Could not read distinct partitions from trace_tt_slow_point: {}", e.getMessage());
        }

        try {
            pruneStatus = ImmutableTracePruneStatus.builder()
                    .copyFrom(pruneStatus)
                    .currentStep("Discovering error point partitions...")
                    .build();
            ResultSet rs = session.read("select distinct agent_rollup, transaction_type from trace_tt_error_point",
                    CassandraProfile.slow);
            for (Row row : rs) {
                errorPartitions.add(new PartitionKey(row.getString("agent_rollup"), row.getString("transaction_type")));
            }
        } catch (Exception e) {
            logger.warn("Could not read distinct partitions from trace_tt_error_point: {}", e.getMessage());
        }

        try {
            ResultSet rs = session.read("select agent_rollup, transaction_type from transaction_type where one = 1",
                    CassandraProfile.slow);
            for (Row row : rs) {
                slowPartitions.add(new PartitionKey(row.getString("agent_rollup"), row.getString("transaction_type")));
            }
        } catch (Exception e) {
            logger.debug("Could not read from transaction_type: {}", e.getMessage());
        }

        long deletedCount = 0;
        long errorCount = 0;

        PreparedStatement deleteHeaderV2 = prepareIfExists("delete from trace_header_v2 where agent_id = ? and trace_id = ?");
        PreparedStatement deleteEntryV2 = prepareIfExists("delete from trace_entry_v2 where agent_id = ? and trace_id = ?");
        PreparedStatement deleteQueryV2 = prepareIfExists("delete from trace_query_v2 where agent_id = ? and trace_id = ?");
        PreparedStatement deleteSharedQueryTextV2 = prepareIfExists("delete from trace_shared_query_text_v2 where agent_id = ? and trace_id = ?");
        PreparedStatement deleteMainThreadProfileV2 = prepareIfExists("delete from trace_main_thread_profile_v2 where agent_id = ? and trace_id = ?");
        PreparedStatement deleteAuxThreadProfileV2 = prepareIfExists("delete from trace_aux_thread_profile_v2 where agent_id = ? and trace_id = ?");
        PreparedStatement deleteHeaderV1 = prepareIfExists("delete from trace_header where agent_id = ? and trace_id = ?");
        PreparedStatement deleteEntryV1 = prepareIfExists("delete from trace_entry where agent_id = ? and trace_id = ?");
        PreparedStatement deleteSharedQueryTextV1 = prepareIfExists("delete from trace_shared_query_text where agent_id = ? and trace_id = ?");
        PreparedStatement deleteMainThreadProfileV1 = prepareIfExists("delete from trace_main_thread_profile where agent_id = ? and trace_id = ?");
        PreparedStatement deleteAuxThreadProfileV1 = prepareIfExists("delete from trace_aux_thread_profile where agent_id = ? and trace_id = ?");

        PreparedStatement readSlowPoints = session.prepare(
                "select capture_time, agent_id, trace_id from trace_tt_slow_point where agent_rollup = ? and transaction_type = ? and capture_time <= ?");
        PreparedStatement deleteSlowPoint = prepareIfExists(
                "delete from trace_tt_slow_point where agent_rollup = ? and transaction_type = ? and capture_time = ? and agent_id = ? and trace_id = ?");
        PreparedStatement deleteSlowCount = prepareIfExists(
                "delete from trace_tt_slow_count where agent_rollup = ? and transaction_type = ? and capture_time = ? and agent_id = ? and trace_id = ?");

        int partitionIdx = 0;
        for (PartitionKey pk : slowPartitions) {
            if (cancelPruningRequested.get()) {
                return;
            }
            partitionIdx++;
            pruneStatus = ImmutableTracePruneStatus.builder()
                    .copyFrom(pruneStatus)
                    .deletedCount(deletedCount)
                    .errorCount(errorCount)
                    .currentStep("Pruning slow traces in " + pk.agentRollup + " / " + pk.transactionType
                            + " (" + partitionIdx + " of " + slowPartitions.size() + ")...")
                    .build();

            try {
                BoundStatement bound = readSlowPoints.bind(pk.agentRollup, pk.transactionType, cutoffInstant)
                        .setExecutionProfileName(CassandraProfile.slow.name());
                ResultSet rs = session.read(bound, CassandraProfile.slow);
                for (Row row : rs) {
                    if (cancelPruningRequested.get()) {
                        return;
                    }
                    Instant captureTime = row.getInstant("capture_time");
                    String agentId = row.getString("agent_id");
                    String traceId = row.getString("trace_id");

                    try {
                        deleteTraceComponents(agentId, traceId, deleteHeaderV2, deleteEntryV2, deleteQueryV2,
                                deleteSharedQueryTextV2, deleteMainThreadProfileV2, deleteAuxThreadProfileV2,
                                deleteHeaderV1, deleteEntryV1, deleteSharedQueryTextV1,
                                deleteMainThreadProfileV1, deleteAuxThreadProfileV1);

                        if (deleteSlowPoint != null) {
                            session.write(deleteSlowPoint.bind(pk.agentRollup, pk.transactionType, captureTime, agentId, traceId),
                                    CassandraProfile.slow);
                        }
                        if (deleteSlowCount != null) {
                            session.write(deleteSlowCount.bind(pk.agentRollup, pk.transactionType, captureTime, agentId, traceId),
                                    CassandraProfile.slow);
                        }
                        deletedCount++;
                        if (deletedCount % 100 == 0) {
                            pruneStatus = ImmutableTracePruneStatus.builder()
                                    .copyFrom(pruneStatus)
                                    .deletedCount(deletedCount)
                                    .errorCount(errorCount)
                                    .build();
                        }
                    } catch (Exception e) {
                        errorCount++;
                    }
                }
            } catch (Exception e) {
                logger.warn("Error scanning slow traces for {} / {}: {}", pk.agentRollup, pk.transactionType, e.getMessage());
                errorCount++;
            }
        }

        PreparedStatement readErrorPoints = session.prepare(
                "select capture_time, agent_id, trace_id from trace_tt_error_point where agent_rollup = ? and transaction_type = ? and capture_time <= ?");
        PreparedStatement deleteErrorPoint = prepareIfExists(
                "delete from trace_tt_error_point where agent_rollup = ? and transaction_type = ? and capture_time = ? and agent_id = ? and trace_id = ?");
        PreparedStatement deleteErrorCount = prepareIfExists(
                "delete from trace_tt_error_count where agent_rollup = ? and transaction_type = ? and capture_time = ? and agent_id = ? and trace_id = ?");

        partitionIdx = 0;
        for (PartitionKey pk : errorPartitions) {
            if (cancelPruningRequested.get()) {
                return;
            }
            partitionIdx++;
            pruneStatus = ImmutableTracePruneStatus.builder()
                    .copyFrom(pruneStatus)
                    .deletedCount(deletedCount)
                    .errorCount(errorCount)
                    .currentStep("Pruning error traces in " + pk.agentRollup + " / " + pk.transactionType
                            + " (" + partitionIdx + " of " + errorPartitions.size() + ")...")
                    .build();

            try {
                BoundStatement bound = readErrorPoints.bind(pk.agentRollup, pk.transactionType, cutoffInstant)
                        .setExecutionProfileName(CassandraProfile.slow.name());
                ResultSet rs = session.read(bound, CassandraProfile.slow);
                for (Row row : rs) {
                    if (cancelPruningRequested.get()) {
                        return;
                    }
                    Instant captureTime = row.getInstant("capture_time");
                    String agentId = row.getString("agent_id");
                    String traceId = row.getString("trace_id");

                    try {
                        deleteTraceComponents(agentId, traceId, deleteHeaderV2, deleteEntryV2, deleteQueryV2,
                                deleteSharedQueryTextV2, deleteMainThreadProfileV2, deleteAuxThreadProfileV2,
                                deleteHeaderV1, deleteEntryV1, deleteSharedQueryTextV1,
                                deleteMainThreadProfileV1, deleteAuxThreadProfileV1);

                        if (deleteErrorPoint != null) {
                            session.write(deleteErrorPoint.bind(pk.agentRollup, pk.transactionType, captureTime, agentId, traceId),
                                    CassandraProfile.slow);
                        }
                        if (deleteErrorCount != null) {
                            session.write(deleteErrorCount.bind(pk.agentRollup, pk.transactionType, captureTime, agentId, traceId),
                                    CassandraProfile.slow);
                        }
                        deletedCount++;
                        if (deletedCount % 100 == 0) {
                            pruneStatus = ImmutableTracePruneStatus.builder()
                                    .copyFrom(pruneStatus)
                                    .deletedCount(deletedCount)
                                    .errorCount(errorCount)
                                    .build();
                        }
                    } catch (Exception e) {
                        errorCount++;
                    }
                }
            } catch (Exception e) {
                logger.warn("Error scanning error traces for {} / {}: {}", pk.agentRollup, pk.transactionType, e.getMessage());
                errorCount++;
            }
        }

        pruneStatus = ImmutableTracePruneStatus.builder()
                .copyFrom(pruneStatus)
                .deletedCount(deletedCount)
                .errorCount(errorCount)
                .build();
    }

    private @Nullable PreparedStatement prepareIfExists(String query) {
        String tableName = getTableNameFromDeleteQuery(query);
        if (tableName != null && session.getTable(tableName) == null) {
            return null;
        }
        try {
            return session.prepare(query);
        } catch (Exception e) {
            logger.debug("Could not prepare statement '{}': {}", query, e.getMessage());
            return null;
        }
    }

    private static @Nullable String getTableNameFromDeleteQuery(String query) {
        String prefix = "delete from ";
        if (query.startsWith(prefix)) {
            String sub = query.substring(prefix.length()).trim();
            int spaceIdx = sub.indexOf(' ');
            return spaceIdx != -1 ? sub.substring(0, spaceIdx) : sub;
        }
        return null;
    }

    private void deleteTraceComponents(String agentId, String traceId,
            @Nullable PreparedStatement deleteHeaderV2,
            @Nullable PreparedStatement deleteEntryV2,
            @Nullable PreparedStatement deleteQueryV2,
            @Nullable PreparedStatement deleteSharedQueryTextV2,
            @Nullable PreparedStatement deleteMainThreadProfileV2,
            @Nullable PreparedStatement deleteAuxThreadProfileV2,
            @Nullable PreparedStatement deleteHeaderV1,
            @Nullable PreparedStatement deleteEntryV1,
            @Nullable PreparedStatement deleteSharedQueryTextV1,
            @Nullable PreparedStatement deleteMainThreadProfileV1,
            @Nullable PreparedStatement deleteAuxThreadProfileV1) throws Exception {
        if (deleteHeaderV2 != null) {
            session.write(deleteHeaderV2.bind(agentId, traceId), CassandraProfile.slow);
        }
        if (deleteEntryV2 != null) {
            session.write(deleteEntryV2.bind(agentId, traceId), CassandraProfile.slow);
        }
        if (deleteQueryV2 != null) {
            session.write(deleteQueryV2.bind(agentId, traceId), CassandraProfile.slow);
        }
        if (deleteSharedQueryTextV2 != null) {
            session.write(deleteSharedQueryTextV2.bind(agentId, traceId), CassandraProfile.slow);
        }
        if (deleteMainThreadProfileV2 != null) {
            session.write(deleteMainThreadProfileV2.bind(agentId, traceId), CassandraProfile.slow);
        }
        if (deleteAuxThreadProfileV2 != null) {
            session.write(deleteAuxThreadProfileV2.bind(agentId, traceId), CassandraProfile.slow);
        }
        if (deleteHeaderV1 != null) {
            session.write(deleteHeaderV1.bind(agentId, traceId), CassandraProfile.slow);
        }
        if (deleteEntryV1 != null) {
            session.write(deleteEntryV1.bind(agentId, traceId), CassandraProfile.slow);
        }
        if (deleteSharedQueryTextV1 != null) {
            session.write(deleteSharedQueryTextV1.bind(agentId, traceId), CassandraProfile.slow);
        }
        if (deleteMainThreadProfileV1 != null) {
            session.write(deleteMainThreadProfileV1.bind(agentId, traceId), CassandraProfile.slow);
        }
        if (deleteAuxThreadProfileV1 != null) {
            session.write(deleteAuxThreadProfileV1.bind(agentId, traceId), CassandraProfile.slow);
        }
    }

    private static String categorizeTable(String tableName) {
        if (tableName.startsWith("trace_")) {
            return "Trace";
        } else if (tableName.startsWith("aggregate_")) {
            return "Aggregate";
        } else if (tableName.startsWith("gauge_")) {
            return "Gauge";
        } else if (tableName.startsWith("synthetic_")) {
            return "Synthetic";
        } else if (tableName.startsWith("active_agent")) {
            return "Agent";
        } else if (tableName.equals("heartbeat") || tableName.equals("resolved_incident")
                || tableName.startsWith("agent_config") || tableName.equals("central_config")) {
            return "System / Config";
        } else {
            return "Other";
        }
    }

    private static class PartitionKey {
        private final String agentRollup;
        private final String transactionType;

        private PartitionKey(String agentRollup, String transactionType) {
            this.agentRollup = agentRollup;
            this.transactionType = transactionType;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PartitionKey that = (PartitionKey) o;
            return java.util.Objects.equals(agentRollup, that.agentRollup) &&
                    java.util.Objects.equals(transactionType, that.transactionType);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(agentRollup, transactionType);
        }
    }
}
