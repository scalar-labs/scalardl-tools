package com.scalar.dl.tools.scan.jdbc;

import com.google.common.annotations.VisibleForTesting;
import com.scalar.db.api.ConditionBuilder;
import com.scalar.db.api.ConditionSetBuilder;
import com.scalar.db.api.ConditionalExpression;
import com.scalar.db.api.ConditionalExpression.Operator;
import com.scalar.db.api.DatabaseOperationAttributes;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.ScanBuilder;
import com.scalar.db.api.Scanner;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.io.Column;
import com.scalar.db.service.StorageFactory;
import com.scalar.db.util.ScalarDbUtils;
import com.scalar.dl.tools.scan.RecordHandler;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC implementation of {@link ResumableScanner}, built on ScalarDB's storage API.
 *
 * <p>The table is read in primary key order with keyset pagination: each page is a separate
 * cross-partition scan with {@code LIMIT}.
 *
 * <p>Pages are used instead of a single scanner over the whole table to keep each transaction
 * short: ScalarDB keeps a transaction open while a scanner is open, and a long-lived transaction on
 * a production database keeps old row versions from being reclaimed.
 *
 * <p>The scan order is: partition key columns in ascending order, followed by clustering key
 * columns in their clustering order. After each handled page, the primary key of its last record
 * (the last key L) is checkpointed, and a resumed scan continues right after it.
 *
 * <p>"After L" is decomposed into disjoint ranges, called levels, that use only AND conditions, so
 * that each query can be served by an index range scan, assuming an index whose key order matches
 * the scan order. (A single query with the OR of the levels is not served that way on some RDBMSs,
 * such as PostgreSQL.) Level i fixes the first i key columns to L's values and requires key column
 * i to come after L's value. For example, with primary key (pk, ck1 DESC, ck2) and L = ('p1', 5,
 * 'a'):
 *
 * <pre>
 *   level 2: pk = 'p1' AND ck1 = 5 AND ck2 > 'a'   (narrowest)
 *   level 1: pk = 'p1' AND ck1 &lt; 5              (ck1 is DESC, so "after" means "&lt;")
 *   level 0: pk > 'p1'                             (widest)
 * </pre>
 *
 * <p>Records must be handled in the scan order, because the checkpoint L means that every record up
 * to L has been handled. Every record in a narrower level comes before every record in a wider one,
 * so the scan reads the levels from the narrowest one outwards.
 *
 * <p>Each query reads one page of a level. After a page is handled, L moves to its last record and
 * the levels are rebuilt from the new L, so the scan starts again from the narrowest level. An
 * empty page, or a page with fewer records than the page size, means that the level has no more
 * records, so the scan moves to the next wider level. (After a page with fewer records, the levels
 * from the new L that are as narrow as that level or narrower are within it, so they have no more
 * records either.) When the widest level has no more records, no record is left after L, and the
 * scan is complete.
 *
 * <p>The first scan without a checkpoint has no condition (level -1).
 *
 * <p>The handler is invoked sequentially in the scan order.
 */
@NotThreadSafe
public final class JdbcResumableScanner implements ResumableScanner {

  private static final Logger logger = LoggerFactory.getLogger(JdbcResumableScanner.class);

  /**
   * Level used before any checkpoint exists: the query has no condition and starts from the
   * beginning of the table.
   */
  private static final int NO_CONDITION_LEVEL = -1;

  private final DistributedStorage storage;
  private final DistributedStorageAdmin storageAdmin;
  private final CheckpointManager checkpointManager;
  private final int pageSize;

  public JdbcResumableScanner(DatabaseConfig databaseConfig, Path checkpointDir) {
    JdbcResumableScannerConfig config = new JdbcResumableScannerConfig(databaseConfig);
    StorageFactory storageFactory = StorageFactory.create(databaseConfig.getProperties());
    this.storage = storageFactory.getStorage();
    try {
      this.storageAdmin = storageFactory.getStorageAdmin();
    } catch (Exception e) {
      storage.close();
      throw new RuntimeException("Failed to create DistributedStorageAdmin", e);
    }
    this.checkpointManager = new CheckpointManager(checkpointDir);
    this.pageSize = config.getPageSize();
  }

  @VisibleForTesting
  JdbcResumableScanner(
      DistributedStorage storage,
      DistributedStorageAdmin storageAdmin,
      CheckpointManager checkpointManager,
      int pageSize) {
    this.storage = storage;
    this.storageAdmin = storageAdmin;
    this.checkpointManager = checkpointManager;
    this.pageSize = pageSize;
  }

  /** Returns the primary key column names: partition key columns, then clustering key columns. */
  static List<String> primaryKeyColumnNames(TableMetadata metadata) {
    List<String> names = new ArrayList<>(metadata.getPartitionKeyNames());
    names.addAll(metadata.getClusteringKeyNames());
    return names;
  }

  @Override
  public ScanResult scan(String namespace, String tableName, RecordHandler recordHandler)
      throws Exception {
    Objects.requireNonNull(namespace, "namespace must not be null");
    Objects.requireNonNull(tableName, "tableName must not be null");
    Objects.requireNonNull(recordHandler, "recordHandler must not be null");

    String qualifiedTableName = ScalarDbUtils.getFullTableName(namespace, tableName);
    checkpointManager.initCheckpointFor(qualifiedTableName);
    ScanResult result = doScan(namespace, tableName, qualifiedTableName, recordHandler);
    checkpointManager.clearCheckpointFor(qualifiedTableName);
    return result;
  }

  private ScanResult doScan(
      String namespace, String tableName, String qualifiedTableName, RecordHandler recordHandler)
      throws Exception {
    TableMetadata tableMetadata = storageAdmin.getTableMetadata(namespace, tableName);
    if (tableMetadata == null) {
      throw new IllegalStateException("Table metadata not found for " + qualifiedTableName);
    }
    List<String> primaryKeyColumnNames = primaryKeyColumnNames(tableMetadata);
    List<Scan.Ordering> orderings = buildOrderings(tableMetadata);
    int narrowestLevel = primaryKeyColumnNames.size() - 1;

    List<Column<?>> lastKey = loadLastKey(qualifiedTableName, tableMetadata);
    int level = lastKey == null ? NO_CONDITION_LEVEL : narrowestLevel;

    long scanned = 0;
    while (true) {
      if (Thread.interrupted()) {
        throw new InterruptedException("Scan interrupted for " + qualifiedTableName);
      }

      List<Result> page = fetchPage(namespace, tableName, tableMetadata, orderings, lastKey, level);
      if (page.isEmpty()) {
        if (level <= 0) {
          // Nothing after the last key even in the widest range (or the table is empty)
          break;
        }
        // Nothing after the last key in this range. Widen the range.
        level--;
        continue;
      }

      for (Result record : page) {
        recordHandler.handle(record);
        scanned++;
      }
      lastKey = extractKey(page.get(page.size() - 1), primaryKeyColumnNames);
      checkpointManager.persistLastKey(qualifiedTableName, PrimaryKeySerializer.toJson(lastKey));
      if (page.size() < pageSize) {
        // The page did not fill up, so this range has no more records. The narrower ranges after
        // the new last key are within this range, so they have no more records either.
        if (level <= 0) {
          // Nothing left even in the widest range (or in the whole table)
          break;
        }
        level--;
      } else {
        // Look for the records right after the new last key, from the narrowest range
        level = narrowestLevel;
      }
    }

    logger.info("Scan complete for {}: {} records", qualifiedTableName, scanned);
    return new ScanResult(scanned);
  }

  @Nullable
  private List<Column<?>> loadLastKey(String qualifiedTableName, TableMetadata tableMetadata) {
    String json = checkpointManager.loadLastKey(qualifiedTableName);
    if (json == null) {
      logger.info("Starting scan for {} from the beginning", qualifiedTableName);
      return null;
    }
    List<Column<?>> lastKey = PrimaryKeySerializer.fromJson(json, tableMetadata);
    logger.info("Resuming scan for {} from the checkpoint", qualifiedTableName);
    return lastKey;
  }

  private static List<Scan.Ordering> buildOrderings(TableMetadata metadata) {
    List<Scan.Ordering> orderings = new ArrayList<>();
    for (String name : metadata.getPartitionKeyNames()) {
      orderings.add(Scan.Ordering.asc(name));
    }
    for (String name : metadata.getClusteringKeyNames()) {
      orderings.add(
          metadata.getClusteringOrder(name) == Scan.Ordering.Order.DESC
              ? Scan.Ordering.desc(name)
              : Scan.Ordering.asc(name));
    }
    return orderings;
  }

  private List<Result> fetchPage(
      String namespace,
      String tableName,
      TableMetadata metadata,
      List<Scan.Ordering> orderings,
      @Nullable List<Column<?>> lastKey,
      int level)
      throws Exception {
    Map<String, String> attributes = new HashMap<>();
    DatabaseOperationAttributes.setCrossPartitionScanEnabled(attributes, true);
    DatabaseOperationAttributes.setCrossPartitionScanFilteringEnabled(attributes, true);
    DatabaseOperationAttributes.setCrossPartitionScanOrderingEnabled(attributes, true);

    ScanBuilder.BuildableScanAll builder =
        Scan.newBuilder().namespace(namespace).table(tableName).all();
    Scan scan;
    if (level == NO_CONDITION_LEVEL) {
      scan = builder.orderings(orderings).attributes(attributes).limit(pageSize).build();
    } else {
      Set<ConditionalExpression> conditions =
          buildConditions(metadata, Objects.requireNonNull(lastKey), level);
      scan =
          builder
              .where(ConditionSetBuilder.andConditionSet(conditions).build())
              .orderings(orderings)
              .attributes(attributes)
              .limit(pageSize)
              .build();
    }

    // Read the whole page and close the scanner before handling records, so that no connection
    // or transaction is held open while the handler runs.
    try (Scanner scanner = storage.scan(scan)) {
      return scanner.all();
    }
  }

  private static Set<ConditionalExpression> buildConditions(
      TableMetadata metadata, List<Column<?>> lastKey, int level) {
    int partitionKeyCount = metadata.getPartitionKeyNames().size();
    Set<ConditionalExpression> conditions = new LinkedHashSet<>();
    for (int i = 0; i < level; i++) {
      conditions.add(ConditionBuilder.buildConditionalExpression(lastKey.get(i), Operator.EQ));
    }
    Column<?> boundary = lastKey.get(level);
    boolean descending =
        level >= partitionKeyCount
            && metadata.getClusteringOrder(boundary.getName()) == Scan.Ordering.Order.DESC;
    conditions.add(
        ConditionBuilder.buildConditionalExpression(
            boundary, descending ? Operator.LT : Operator.GT));
    return conditions;
  }

  private static List<Column<?>> extractKey(Result record, List<String> primaryKeyColumnNames) {
    Map<String, Column<?>> columns = record.getColumns();
    List<Column<?>> key = new ArrayList<>(primaryKeyColumnNames.size());
    for (String name : primaryKeyColumnNames) {
      Column<?> column = columns.get(name);
      if (column == null) {
        throw new IllegalStateException("Primary key column not found in scanned record: " + name);
      }
      key.add(column);
    }
    return Collections.unmodifiableList(key);
  }

  @Override
  public void close() {
    try {
      storageAdmin.close();
    } finally {
      storage.close();
    }
  }
}
