package com.scalar.dl.tools.scan.cosmos;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.models.FeedRange;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Result;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.service.StorageFactory;
import com.scalar.db.storage.cosmos.CosmosConfig;
import com.scalar.db.storage.cosmos.CosmosUtils;
import com.scalar.db.storage.cosmos.ResultInterpreter;
import com.scalar.db.util.ScalarDbUtils;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.annotation.concurrent.NotThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cosmos DB implementation of {@link ResumableScanner}.
 *
 * <p>Encapsulates all Cosmos-specific details: CosmosClient creation, namespace-to-database /
 * table-to-container mapping, FeedRange discovery, per-partition parallel scanning, and
 * raw-document-to-Result conversion.
 */
@NotThreadSafe
public final class CosmosResumableScanner implements ResumableScanner {

  private static final Logger logger = LoggerFactory.getLogger(CosmosResumableScanner.class);
  private static final ObjectMapper mapper = new ObjectMapper();
  private static final int MAX_SCAN_THREADS = 32;

  private final CosmosClient cosmosClient;
  private final CheckpointManager checkpointManager;
  private final DistributedStorageAdmin storageAdmin;
  private ExecutorService scanExecutor;

  public CosmosResumableScanner(DatabaseConfig databaseConfig, Path checkpointDir) {
    cosmosClient = CosmosUtils.buildCosmosClient(new CosmosConfig(databaseConfig));
    this.checkpointManager = new CheckpointManager(checkpointDir);

    try {
      this.storageAdmin = StorageFactory.create(databaseConfig.getProperties()).getStorageAdmin();
    } catch (Exception e) {
      cosmosClient.close();
      throw new RuntimeException("Failed to create DistributedStorageAdmin", e);
    }
  }

  @VisibleForTesting
  CosmosResumableScanner(
      CosmosClient cosmosClient,
      CheckpointManager checkpointManager,
      DistributedStorageAdmin storageAdmin) {
    this.cosmosClient = cosmosClient;
    this.checkpointManager = checkpointManager;
    this.storageAdmin = storageAdmin;
  }

  private static void shutdownExecutorIfExists(ExecutorService executor) {
    if (executor == null) {
      return;
    }
    executor.shutdownNow();
    try {
      // 30 seconds is a conservative upper bound to allow in-flight Cosmos DB HTTP requests to
      // complete or time out after interruption.
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        logger.warn("Scan executor did not terminate within 30 seconds");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public ScanResult scan(String namespace, String tableName, Consumer<Result> recordConsumer) {
    try {
      ScanResult result = doScan(namespace, tableName, recordConsumer);
      String qualifiedTableName = ScalarDbUtils.getFullTableName(namespace, tableName);
      checkpointManager.clearCheckpointFor(qualifiedTableName);
      return result;
    } catch (RuntimeException e) {
      // Re-throw directly to avoid wrapping in the catch-all below
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(
          "Scan failed for " + ScalarDbUtils.getFullTableName(namespace, tableName), e);
    }
  }

  private ScanResult doScan(String namespace, String tableName, Consumer<Result> recordConsumer)
      throws Exception {

    // Resolve Cosmos database and container from namespace and table name
    CosmosDatabase database = cosmosClient.getDatabase(namespace);
    CosmosContainer container = database.getContainer(tableName);

    // Get table metadata for type conversion
    TableMetadata tableMetadata = storageAdmin.getTableMetadata(namespace, tableName);
    if (tableMetadata == null) {
      throw new IllegalStateException(
          "Table metadata not found for " + ScalarDbUtils.getFullTableName(namespace, tableName));
    }
    ResultInterpreter resultInterpreter =
        new ResultInterpreter(Collections.emptyList(), tableMetadata);

    // Discover or load FeedRanges
    String qualifiedTableName = ScalarDbUtils.getFullTableName(namespace, tableName);
    List<FeedRange> feedRanges = loadOrDiscoverFeedRanges(container, qualifiedTableName);

    logger.info("Cosmos DB physical partitions: {}", feedRanges.size());

    if (feedRanges.isEmpty()) {
      logger.info("No FeedRanges found for {}; nothing to scan", qualifiedTableName);
      return new ScanResult(0);
    }

    int threadCount = Math.min(feedRanges.size(), MAX_SCAN_THREADS);
    scanExecutor =
        Executors.newFixedThreadPool(
            threadCount,
            new ThreadFactoryBuilder()
                .setNameFormat("cosmos-scan-" + qualifiedTableName + "-%d")
                .setDaemon(true)
                .build());
    try {
      List<Future<Long>> futures = new ArrayList<>();
      for (FeedRange feedRange : feedRanges) {
        String feedRangeId = FeedRangeSerializer.toId(feedRange);
        String continuationToken =
            checkpointManager.loadContinuationToken(qualifiedTableName, feedRangeId);

        CosmosScanWorker worker =
            new CosmosScanWorker(
                container,
                feedRange,
                feedRangeId,
                qualifiedTableName,
                continuationToken,
                recordConsumer,
                resultInterpreter,
                checkpointManager);

        futures.add(scanExecutor.submit(worker));
      }

      // Wait for all workers, collecting all errors
      long totalScanned = 0;
      Throwable firstFailure = null;
      for (Future<Long> future : futures) {
        try {
          totalScanned += future.get();
        } catch (ExecutionException e) {
          Throwable cause = e.getCause() != null ? e.getCause() : e;
          if (firstFailure == null) {
            firstFailure = cause;
          } else {
            firstFailure.addSuppressed(cause);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          for (Future<Long> remaining : futures) {
            remaining.cancel(true);
          }
          throw e;
        }
      }
      if (firstFailure instanceof RuntimeException) {
        throw (RuntimeException) firstFailure;
      } else if (firstFailure instanceof Exception) {
        throw (Exception) firstFailure;
      } else if (firstFailure != null) {
        throw new RuntimeException(firstFailure);
      }

      logger.info("Scan complete for {}: {} records", qualifiedTableName, totalScanned);
      return new ScanResult(totalScanned);
    } finally {
      shutdownExecutorIfExists(scanExecutor);
      scanExecutor = null;
    }
  }

  private List<FeedRange> loadOrDiscoverFeedRanges(
      CosmosContainer container, String qualifiedTableName) {
    String persistedRanges = checkpointManager.loadFeedRanges(qualifiedTableName);

    if (persistedRanges != null) {
      try {
        List<String> rangeJsonList =
            mapper.readValue(persistedRanges, new TypeReference<List<String>>() {});
        List<FeedRange> feedRanges = new ArrayList<>();
        for (String json : rangeJsonList) {
          feedRanges.add(FeedRangeSerializer.fromJson(json));
        }
        logger.info("Loaded {} persisted FeedRanges for {}", feedRanges.size(), qualifiedTableName);
        return feedRanges;
      } catch (Exception e) {
        throw new RuntimeException("Failed to load persisted FeedRanges", e);
      }
    }

    // Discover FeedRanges
    List<FeedRange> feedRanges = container.getFeedRanges();
    logger.info("Discovered {} FeedRanges for {}", feedRanges.size(), qualifiedTableName);

    // Persist before scanning
    try {
      List<String> rangeJsonList = new ArrayList<>();
      for (FeedRange fr : feedRanges) {
        rangeJsonList.add(FeedRangeSerializer.toJson(fr));
      }
      String json = mapper.writeValueAsString(rangeJsonList);
      checkpointManager.persistFeedRanges(qualifiedTableName, json);
    } catch (Exception e) {
      throw new RuntimeException("Failed to persist FeedRanges", e);
    }

    return feedRanges;
  }

  @Override
  public void close() {
    shutdownExecutorIfExists(scanExecutor);
    try {
      if (storageAdmin != null) {
        storageAdmin.close();
      }
    } finally {
      if (cosmosClient != null) {
        cosmosClient.close();
      }
    }
  }
}
