package com.scalar.dl.tools.scan.jdbc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.Scan.Ordering;
import com.scalar.db.api.Scanner;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.io.Column;
import com.scalar.db.io.DataType;
import com.scalar.db.service.StorageFactory;
import com.scalar.db.storage.jdbc.JdbcConfig;
import com.scalar.db.util.ScalarDbUtils;
import com.scalar.dl.tools.common.ScalarDlCleanupError;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import com.scalar.dl.tools.scan.RecordHandler;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ScanResult;
import com.scalar.dl.tools.scan.checkpoint.CheckpointManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC implementation of {@link ResumableScanner}.
 *
 * <p>Pages through the table with a cross-partition scan ordered by the primary key, resuming from
 * the key of the last record whose page was fully processed. Each page is fetched in full and its
 * scanner closed before any record is handed to the handler, so a handler that deletes from the
 * table it is scanning never interleaves with an open cursor.
 *
 * <p>The scan uses its own storage instance because a cross-partition scan with filtering and
 * ordering requires those options to be enabled, which the tool sets rather than requiring every
 * operator to configure.
 */
@NotThreadSafe
public final class JdbcResumableScanner implements ResumableScanner {

  /** The primary-key types the checkpoint codec and the condition builder can handle. */
  private static final Set<DataType> SUPPORTED_KEY_TYPES =
      EnumSet.of(
          DataType.BOOLEAN,
          DataType.INT,
          DataType.BIGINT,
          DataType.FLOAT,
          DataType.DOUBLE,
          DataType.TEXT,
          DataType.BLOB,
          DataType.DATE,
          DataType.TIME,
          DataType.TIMESTAMP,
          DataType.TIMESTAMPTZ);

  private static final Logger logger = LoggerFactory.getLogger(JdbcResumableScanner.class);
  private static final String CHECKPOINT_TOKEN_ID = "keyset";
  private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

  /**
   * The connection pool ceiling for the scan's own storage. The scan runs on one thread and holds
   * one page query at a time, so it needs a single connection; the rest is headroom. Without this
   * the scan would inherit ScalarDB's defaults (up to 200 connections for the storage and 25 each
   * for its admin and metadata pools) and double the connections the tool needs, on top of the pool
   * the caller already holds for its own reads and writes.
   */
  private static final int SCAN_CONNECTION_POOL_MAX_TOTAL = 4;

  private final DistributedStorage storage;
  private final DistributedStorageAdmin storageAdmin;
  private final CheckpointManager checkpointManager;
  private final int pageSize;
  private final int maxHandlerThreads;
  private ExecutorService handlerExecutor;

  public JdbcResumableScanner(DatabaseConfig databaseConfig, Path checkpointDir) {
    JdbcResumableScannerConfig config = new JdbcResumableScannerConfig(databaseConfig);
    this.pageSize = config.getPageSize();
    this.maxHandlerThreads = config.getMaxHandlerThreads();
    this.checkpointManager = new CheckpointManager(checkpointDir);

    StorageFactory factory =
        StorageFactory.create(buildScanProperties(databaseConfig.getProperties()));
    this.storage = factory.getStorage();
    try {
      this.storageAdmin = factory.getStorageAdmin();
    } catch (RuntimeException e) {
      storage.close();
      throw e;
    }
  }

  @VisibleForTesting
  JdbcResumableScanner(
      DistributedStorage storage,
      DistributedStorageAdmin storageAdmin,
      CheckpointManager checkpointManager,
      int pageSize,
      int maxHandlerThreads) {
    this.storage = storage;
    this.storageAdmin = storageAdmin;
    this.checkpointManager = checkpointManager;
    this.pageSize = pageSize;
    this.maxHandlerThreads = maxHandlerThreads;
  }

  /**
   * Returns a copy of the given properties with cross-partition scan, filtering, and ordering
   * enabled, which the keyset paging scan requires.
   */
  private static Properties buildScanProperties(Properties original) {
    Properties properties = new Properties();
    properties.putAll(original);
    enableScanOption(properties, DatabaseConfig.CROSS_PARTITION_SCAN);
    enableScanOption(properties, DatabaseConfig.CROSS_PARTITION_SCAN_FILTERING);
    enableScanOption(properties, DatabaseConfig.CROSS_PARTITION_SCAN_ORDERING);
    capConnectionPool(
        properties,
        JdbcConfig.CONNECTION_POOL_MAX_TOTAL,
        JdbcConfig.DEFAULT_CONNECTION_POOL_MAX_TOTAL);
    capConnectionPool(
        properties,
        JdbcConfig.TABLE_METADATA_CONNECTION_POOL_MAX_TOTAL,
        JdbcConfig.DEFAULT_TABLE_METADATA_CONNECTION_POOL_MAX_TOTAL);
    capConnectionPool(
        properties,
        JdbcConfig.ADMIN_CONNECTION_POOL_MAX_TOTAL,
        JdbcConfig.DEFAULT_ADMIN_CONNECTION_POOL_MAX_TOTAL);
    return properties;
  }

  /**
   * Lowers a connection pool ceiling to what the scan actually uses. A caller that already
   * configured a smaller pool keeps it; this only ever reduces.
   */
  private static void capConnectionPool(Properties properties, String key, int defaultValue) {
    int configured = defaultValue;
    String value = properties.getProperty(key);
    if (value != null) {
      try {
        configured = Integer.parseInt(value.trim());
      } catch (NumberFormatException e) {
        // Leave an unparsable value alone; ScalarDB reports it when it builds the config.
        return;
      }
    }
    properties.setProperty(
        key, String.valueOf(Math.min(configured, SCAN_CONNECTION_POOL_MAX_TOTAL)));
  }

  private static void enableScanOption(Properties properties, String key) {
    String configured = properties.getProperty(key);
    if (configured != null && !Boolean.parseBoolean(configured)) {
      logger.info(
          "Enabling '{}' for the scan, overriding the configured value {}", key, configured);
    }
    properties.setProperty(key, "true");
  }

  /**
   * Throws if this scanner cannot resumably scan a table with the given metadata.
   *
   * @param qualifiedTableName the full table name, used in the error message
   * @param metadata the metadata of the table to scan
   * @throws ScalarDlCleanupException if a primary-key column has an unsupported type
   */
  public static void validateScannable(String qualifiedTableName, TableMetadata metadata) {
    for (String name : KeysetConditionBuilder.primaryKeyColumnNames(metadata)) {
      DataType type = metadata.getColumnDataType(name);
      if (!SUPPORTED_KEY_TYPES.contains(type)) {
        throw new ScalarDlCleanupException(
            ScalarDlCleanupError.UNSUPPORTED_PRIMARY_KEY_TYPE, qualifiedTableName, name, type);
      }
    }
  }

  @Override
  public ScanResult scan(String namespace, String tableName, RecordHandler recordHandler)
      throws Exception {
    Objects.requireNonNull(namespace, "namespace must not be null");
    Objects.requireNonNull(tableName, "tableName must not be null");
    Objects.requireNonNull(recordHandler, "recordHandler must not be null");

    String qualifiedTableName = ScalarDbUtils.getFullTableName(namespace, tableName);
    TableMetadata metadata = storageAdmin.getTableMetadata(namespace, tableName);
    if (metadata == null) {
      throw new IllegalStateException("Table metadata not found for " + qualifiedTableName);
    }
    validateScannable(qualifiedTableName, metadata);

    checkpointManager.initCheckpointFor(qualifiedTableName);
    ScanResult result = doScan(namespace, tableName, qualifiedTableName, metadata, recordHandler);
    checkpointManager.clearCheckpointFor(qualifiedTableName);
    return result;
  }

  private ScanResult doScan(
      String namespace,
      String tableName,
      String qualifiedTableName,
      TableMetadata metadata,
      RecordHandler recordHandler)
      throws Exception {
    List<String> keyNames = KeysetConditionBuilder.primaryKeyColumnNames(metadata);
    List<Ordering> orderings = KeysetConditionBuilder.orderings(metadata);
    List<Column<?>> lastKey = loadCheckpoint(qualifiedTableName, keyNames, metadata);
    if (lastKey != null) {
      logger.info(
          "Resuming the scan of {} from the persisted keyset checkpoint", qualifiedTableName);
    }

    handlerExecutor =
        Executors.newFixedThreadPool(
            maxHandlerThreads,
            new ThreadFactoryBuilder()
                .setNameFormat("jdbc-scan-" + qualifiedTableName + "-%d")
                .setDaemon(true)
                .build());
    try {
      long totalScanned = 0;
      boolean checkpointed = false;
      while (true) {
        if (Thread.interrupted()) {
          throw new InterruptedException("Scan interrupted for " + qualifiedTableName);
        }
        List<Result> page = fetchPage(namespace, tableName, metadata, orderings, lastKey);
        if (page.isEmpty()) {
          break;
        }

        deliverPage(page, recordHandler);
        totalScanned += page.size();

        lastKey = extractKey(page.get(page.size() - 1), keyNames, qualifiedTableName);
        checkpointManager.persistToken(
            qualifiedTableName, CHECKPOINT_TOKEN_ID, KeysetCheckpointCodec.encode(lastKey));
        if (!checkpointed) {
          // Until the first page is checkpointed there is no scan state, and an interrupted run
          // starts this table over. Logged once, so the point it becomes resumable is visible.
          logger.info("Saved the first scan checkpoint for {}", qualifiedTableName);
          checkpointed = true;
        }

        if (page.size() < pageSize) {
          break;
        }
      }
      logger.info("Scan complete for {}: {} records", qualifiedTableName, totalScanned);
      return new ScanResult(totalScanned);
    } finally {
      shutdownExecutorIfExists(handlerExecutor);
      handlerExecutor = null;
    }
  }

  /** Loads and validates the persisted keyset checkpoint, or returns null when there is none. */
  @Nullable
  private List<Column<?>> loadCheckpoint(
      String qualifiedTableName, List<String> keyNames, TableMetadata metadata) {
    String token = checkpointManager.loadToken(qualifiedTableName, CHECKPOINT_TOKEN_ID);
    if (token == null) {
      return null;
    }
    List<DataType> keyTypes = new ArrayList<>();
    for (String name : keyNames) {
      keyTypes.add(metadata.getColumnDataType(name));
    }
    try {
      return KeysetCheckpointCodec.decode(token, keyNames, keyTypes);
    } catch (CheckpointMismatchException e) {
      throw new ScalarDlCleanupException(
          ScalarDlCleanupError.CHECKPOINT_MISMATCH, e, qualifiedTableName, e.getMessage());
    }
  }

  /**
   * Fetches one page. The page is read in full and the scanner closed before returning, so no
   * cursor is open while the handler mutates the table.
   */
  private List<Result> fetchPage(
      String namespace,
      String tableName,
      TableMetadata metadata,
      List<Ordering> orderings,
      @Nullable List<Column<?>> lastKey)
      throws Exception {
    Scan scan;
    if (lastKey == null) {
      scan =
          Scan.newBuilder()
              .namespace(namespace)
              .table(tableName)
              .all()
              .orderings(orderings)
              .limit(pageSize)
              .build();
    } else {
      scan =
          Scan.newBuilder()
              .namespace(namespace)
              .table(tableName)
              .all()
              .whereOr(KeysetConditionBuilder.resumeCondition(metadata, lastKey))
              .orderings(orderings)
              .limit(pageSize)
              .build();
    }
    try (Scanner scanner = storage.scan(scan)) {
      return scanner.all();
    }
  }

  /**
   * Delivers every record of the page to the handler through the worker pool and waits for all of
   * them. Once a record fails, no further record of the page is started, and the first failure in
   * record order propagates with the others attached as suppressed. The caller must not checkpoint
   * a page that did not deliver completely.
   */
  private void deliverPage(List<Result> page, RecordHandler recordHandler) throws Exception {
    AtomicBoolean failed = new AtomicBoolean();
    List<Future<?>> futures = new ArrayList<>(page.size());
    for (Result record : page) {
      if (failed.get()) {
        break;
      }
      futures.add(
          handlerExecutor.submit(
              () -> {
                if (failed.get()) {
                  return null;
                }
                try {
                  recordHandler.handle(record);
                } catch (Throwable t) {
                  failed.set(true);
                  throw t;
                }
                return null;
              }));
    }

    Throwable firstFailure = null;
    for (Future<?> future : futures) {
      try {
        future.get();
      } catch (ExecutionException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        if (firstFailure == null) {
          firstFailure = cause;
        } else if (firstFailure != cause) {
          firstFailure.addSuppressed(cause);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        for (Future<?> remaining : futures) {
          remaining.cancel(true);
        }
        throw e;
      }
    }
    if (firstFailure instanceof Error) {
      throw (Error) firstFailure;
    } else if (firstFailure != null) {
      assert firstFailure instanceof Exception;
      throw (Exception) firstFailure;
    }
  }

  /** Extracts the primary-key columns of a record, in primary-key order. */
  private static List<Column<?>> extractKey(
      Result record, List<String> keyNames, String qualifiedTableName) {
    List<Column<?>> key = new ArrayList<>(keyNames.size());
    for (String name : keyNames) {
      Column<?> column = record.getColumns().get(name);
      if (column == null || column.hasNullValue()) {
        throw new IllegalStateException(
            "The record has no value for the primary-key column '"
                + name
                + "' of "
                + qualifiedTableName);
      }
      key.add(column);
    }
    return key;
  }

  private static void shutdownExecutorIfExists(@Nullable ExecutorService executor) {
    if (executor == null) {
      return;
    }
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        logger.warn(
            "Handler executor did not terminate within {} seconds", SHUTDOWN_TIMEOUT_SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    shutdownExecutorIfExists(handlerExecutor);
    try {
      if (storage != null) {
        storage.close();
      }
    } finally {
      if (storageAdmin != null) {
        storageAdmin.close();
      }
    }
  }
}
