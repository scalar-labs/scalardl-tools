package com.scalar.dl.tools.scan.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Put;
import com.scalar.db.api.PutBuilder;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.io.Column;
import com.scalar.db.io.DataType;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.Key;
import com.scalar.db.io.TextColumn;
import com.scalar.db.service.StorageFactory;
import com.scalar.dl.tools.scan.RecordHandler;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Integration tests for {@link JdbcResumableScanner} that require a real JDBC database.
 *
 * <p>Run: {@code ./gradlew :resumable-scan:jdbcIntegrationTest -Dscalardb.jdbc.url=...
 * -Dscalardb.jdbc.username=... -Dscalardb.jdbc.password=...}
 */
class JdbcResumableScannerIntegrationTest {

  private static final String NAMESPACE = "it_jdbc_scan";
  private static final String TABLE = "test_table";
  private static final String PARTITION_KEY = "pk";
  private static final String CLUSTERING_KEY_1 = "ck1";
  private static final String CLUSTERING_KEY_2 = "ck2";
  private static final String VALUE_COLUMN = "val";
  private static final String LAST_KEY_FILE = "last_key.json";

  private static final int PARTITION_COUNT = 50;
  private static final int CLUSTERING_KEY_1_COUNT = 3;
  private static final int CLUSTERING_KEY_2_COUNT = 4;
  private static final int TEST_RECORD_COUNT =
      PARTITION_COUNT * CLUSTERING_KEY_1_COUNT * CLUSTERING_KEY_2_COUNT;

  /** Not a divisor of the partition size (12), so that pages end in the middle of partitions. */
  private static final int PAGE_SIZE = 7;

  private static DatabaseConfig databaseConfig;
  private static DistributedStorageAdmin admin;
  private static DistributedStorage storage;

  @BeforeAll
  static void setUpAll() throws Exception {
    Properties props = JdbcEnv.getProperties();
    props.setProperty(JdbcResumableScannerConfig.PROP_SCAN_PAGE_SIZE, String.valueOf(PAGE_SIZE));

    databaseConfig = new DatabaseConfig(props);
    StorageFactory storageFactory = StorageFactory.create(databaseConfig.getProperties());
    admin = storageFactory.getStorageAdmin();
    storage = storageFactory.getStorage();

    admin.createNamespace(NAMESPACE, true);
    admin.createTable(NAMESPACE, TABLE, createStandardMetadata(), true);
    insertTestRecords(TABLE);
  }

  @AfterAll
  static void tearDownAll() throws Exception {
    try {
      for (String table : admin.getNamespaceTableNames(NAMESPACE)) {
        admin.dropTable(NAMESPACE, table);
      }
      admin.dropNamespace(NAMESPACE);
    } finally {
      admin.close();
      storage.close();
    }
  }

  /** pk ASC (partition key), ck1 DESC and ck2 ASC (clustering keys). */
  private static TableMetadata createStandardMetadata() {
    return TableMetadata.newBuilder()
        .addColumn(PARTITION_KEY, DataType.TEXT)
        .addColumn(CLUSTERING_KEY_1, DataType.INT)
        .addColumn(CLUSTERING_KEY_2, DataType.TEXT)
        .addColumn(VALUE_COLUMN, DataType.TEXT)
        .addPartitionKey(PARTITION_KEY)
        .addClusteringKey(CLUSTERING_KEY_1, Scan.Ordering.Order.DESC)
        .addClusteringKey(CLUSTERING_KEY_2, Scan.Ordering.Order.ASC)
        .build();
  }

  private static void insertTestRecords(String table) throws Exception {
    for (int p = 0; p < PARTITION_COUNT; p++) {
      List<Put> puts = new ArrayList<>();
      for (int c1 = 0; c1 < CLUSTERING_KEY_1_COUNT; c1++) {
        for (int c2 = 0; c2 < CLUSTERING_KEY_2_COUNT; c2++) {
          puts.add(
              Put.newBuilder()
                  .namespace(NAMESPACE)
                  .table(table)
                  .partitionKey(Key.ofText(PARTITION_KEY, String.format("partition-%03d", p)))
                  .clusteringKey(
                      Key.newBuilder()
                          .addInt(CLUSTERING_KEY_1, c1)
                          .addText(CLUSTERING_KEY_2, "c" + c2)
                          .build())
                  .textValue(VALUE_COLUMN, "value")
                  .build());
        }
      }
      storage.mutate(puts);
    }
  }

  private static String recordId(Result result) {
    return result.getText(PARTITION_KEY)
        + ":"
        + result.getInt(CLUSTERING_KEY_1)
        + ":"
        + result.getText(CLUSTERING_KEY_2);
  }

  /** Expected scan order: pk ASC, ck1 DESC, ck2 ASC. */
  private static List<String> expectedRecordIdsInOrder() {
    List<String> ids = new ArrayList<>();
    for (int p = 0; p < PARTITION_COUNT; p++) {
      for (int c1 = CLUSTERING_KEY_1_COUNT - 1; c1 >= 0; c1--) {
        for (int c2 = 0; c2 < CLUSTERING_KEY_2_COUNT; c2++) {
          ids.add(String.format("partition-%03d", p) + ":" + c1 + ":c" + c2);
        }
      }
    }
    return ids;
  }

  /**
   * Returns a handler that collects record IDs into {@code recordIds} and throws a {@link
   * RuntimeException} after being invoked {@code threshold} times, simulating an interruption.
   */
  private static RecordHandler interruptingHandlerAfter(
      List<String> recordIds, int threshold, Function<Result, String> recordId) {
    AtomicLong count = new AtomicLong();
    return r -> {
      if (count.incrementAndGet() > threshold) {
        throw new RuntimeException("Simulated interruption");
      }
      recordIds.add(recordId.apply(r));
    };
  }

  /**
   * Scans the table, interrupting the scan at each of the given thresholds and then resuming it to
   * completion, and returns the IDs of the delivered records in delivery order.
   */
  private static List<String> scanWithInterruptions(
      String table, Function<Result, String> recordId, Path checkpointDir, int... thresholds)
      throws Exception {
    List<String> recordIds = new ArrayList<>();
    Path tableCheckpointDir = checkpointDir.resolve(NAMESPACE + "." + table);
    for (int threshold : thresholds) {
      try (JdbcResumableScanner scanner = new JdbcResumableScanner(databaseConfig, checkpointDir)) {
        assertThatThrownBy(
                () ->
                    scanner.scan(
                        NAMESPACE, table, interruptingHandlerAfter(recordIds, threshold, recordId)))
            .hasMessage("Simulated interruption");
      }
      assertThat(tableCheckpointDir.resolve(LAST_KEY_FILE)).exists();
    }
    try (JdbcResumableScanner scanner = new JdbcResumableScanner(databaseConfig, checkpointDir)) {
      scanner.scan(NAMESPACE, table, r -> recordIds.add(recordId.apply(r)));
    }
    // Successful scan clears checkpoint files
    assertThat(tableCheckpointDir).doesNotExist();
    return recordIds;
  }

  /**
   * Asserts that all records are delivered in primary key order, and that only records in a page
   * whose handling was interrupted are delivered again.
   */
  private static void assertDeliveredInOrder(
      List<String> deliveredRecordIds, List<String> expectedRecordIds, int interruptions) {
    Set<String> uniqueRecordIds = new LinkedHashSet<>(deliveredRecordIds);
    assertThat(new ArrayList<>(uniqueRecordIds)).isEqualTo(expectedRecordIds);
    assertThat(deliveredRecordIds.size() - uniqueRecordIds.size())
        .isLessThanOrEqualTo(interruptions * (PAGE_SIZE - 1));
  }

  /** A table and its primary keys listed in the expected scan order. */
  private static final class TableShape {
    private final String table;
    private final TableMetadata metadata;
    private final List<List<Column<?>>> keysInScanOrder;

    private TableShape(
        String table, TableMetadata metadata, List<List<Column<?>>> keysInScanOrder) {
      this.table = table;
      this.metadata = metadata;
      this.keysInScanOrder = keysInScanOrder;
    }

    @Override
    public String toString() {
      return table;
    }
  }

  static Stream<TableShape> tableShapes() {
    return Stream.of(
        singleColumnKeyShape(), multiColumnPartitionKeyShape(), descendingClusteringKeysShape());
  }

  /** A single partition key column and no clustering key, like the coordinator state table. */
  private static TableShape singleColumnKeyShape() {
    List<List<Column<?>>> keys = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      keys.add(Collections.singletonList(TextColumn.of("id", String.format("id-%03d", i))));
    }
    TableMetadata metadata =
        TableMetadata.newBuilder()
            .addColumn("id", DataType.TEXT)
            .addColumn(VALUE_COLUMN, DataType.TEXT)
            .addPartitionKey("id")
            .build();
    return new TableShape("single_column_key_table", metadata, keys);
  }

  /** pk1 ASC and pk2 ASC (partition keys), ck DESC (clustering key). */
  private static TableShape multiColumnPartitionKeyShape() {
    List<List<Column<?>>> keys = new ArrayList<>();
    for (String pk1 : new String[] {"a", "b", "c"}) {
      // Includes negative and multi-digit values, which are ordered numerically
      for (int pk2 : new int[] {-1, 0, 2, 10}) {
        for (int ck = 4; ck >= 0; ck--) {
          keys.add(
              Arrays.asList(
                  TextColumn.of("pk1", pk1), IntColumn.of("pk2", pk2), IntColumn.of("ck", ck)));
        }
      }
    }
    TableMetadata metadata =
        TableMetadata.newBuilder()
            .addColumn("pk1", DataType.TEXT)
            .addColumn("pk2", DataType.INT)
            .addColumn("ck", DataType.INT)
            .addColumn(VALUE_COLUMN, DataType.TEXT)
            .addPartitionKey("pk1")
            .addPartitionKey("pk2")
            .addClusteringKey("ck", Scan.Ordering.Order.DESC)
            .build();
    return new TableShape("multi_column_partition_key_table", metadata, keys);
  }

  /** pk ASC (partition key), ck1 DESC and ck2 DESC (clustering keys). */
  private static TableShape descendingClusteringKeysShape() {
    List<List<Column<?>>> keys = new ArrayList<>();
    for (int p = 0; p < 5; p++) {
      for (int ck1 = 2; ck1 >= 0; ck1--) {
        for (int ck2 = 3; ck2 >= 0; ck2--) {
          keys.add(
              Arrays.asList(
                  TextColumn.of("pk", "partition-" + p),
                  IntColumn.of("ck1", ck1),
                  TextColumn.of("ck2", "c" + ck2)));
        }
      }
    }
    TableMetadata metadata =
        TableMetadata.newBuilder()
            .addColumn("pk", DataType.TEXT)
            .addColumn("ck1", DataType.INT)
            .addColumn("ck2", DataType.TEXT)
            .addColumn(VALUE_COLUMN, DataType.TEXT)
            .addPartitionKey("pk")
            .addClusteringKey("ck1", Scan.Ordering.Order.DESC)
            .addClusteringKey("ck2", Scan.Ordering.Order.DESC)
            .build();
    return new TableShape("descending_clustering_keys_table", metadata, keys);
  }

  private static void insertRecords(TableShape shape) throws Exception {
    Set<String> partitionKeyNames = shape.metadata.getPartitionKeyNames();
    for (List<Column<?>> key : shape.keysInScanOrder) {
      Key.Builder partitionKey = Key.newBuilder();
      Key.Builder clusteringKey = Key.newBuilder();
      for (Column<?> column : key) {
        (partitionKeyNames.contains(column.getName()) ? partitionKey : clusteringKey).add(column);
      }
      PutBuilder.Buildable put =
          Put.newBuilder()
              .namespace(NAMESPACE)
              .table(shape.table)
              .partitionKey(partitionKey.build())
              .textValue(VALUE_COLUMN, "value");
      if (clusteringKey.size() > 0) {
        put = put.clusteringKey(clusteringKey.build());
      }
      storage.put(put.build());
    }
  }

  private static String recordId(List<Column<?>> key) {
    return key.stream()
        .map(c -> String.valueOf(c.getValueAsObject()))
        .collect(Collectors.joining(":"));
  }

  private static Function<Result, String> recordIdOf(TableMetadata metadata) {
    List<String> primaryKeyColumnNames = JdbcResumableScanner.primaryKeyColumnNames(metadata);
    return r ->
        primaryKeyColumnNames.stream()
            .map(name -> String.valueOf(r.getColumns().get(name).getValueAsObject()))
            .collect(Collectors.joining(":"));
  }

  @Test
  void scan_whenNotInterrupted_shouldScanAllRecordsInPrimaryKeyOrder(@TempDir Path checkpointDir)
      throws Exception {
    // Arrange
    List<String> scannedRecordIds = new ArrayList<>();

    try (JdbcResumableScanner scanner = new JdbcResumableScanner(databaseConfig, checkpointDir)) {
      // Act
      ScanResult result = scanner.scan(NAMESPACE, TABLE, r -> scannedRecordIds.add(recordId(r)));

      // Assert
      assertThat(result.getTotalScanned()).isEqualTo(TEST_RECORD_COUNT);
    }
    assertThat(scannedRecordIds).isEqualTo(expectedRecordIdsInOrder());
    // Successful scan clears checkpoint files
    assertThat(checkpointDir.resolve(NAMESPACE + "." + TABLE)).doesNotExist();
  }

  @Test
  void scan_emptyTableGiven_shouldReturnZero(@TempDir Path checkpointDir) throws Exception {
    // Arrange
    String emptyTable = "empty_table";
    admin.createTable(NAMESPACE, emptyTable, createStandardMetadata());
    AtomicLong count = new AtomicLong();

    try (JdbcResumableScanner scanner = new JdbcResumableScanner(databaseConfig, checkpointDir)) {
      // Act
      ScanResult result = scanner.scan(NAMESPACE, emptyTable, r -> count.incrementAndGet());

      // Assert
      assertThat(result.getTotalScanned()).isEqualTo(0);
      assertThat(count.get()).isEqualTo(0);
    } finally {
      admin.dropTable(NAMESPACE, emptyTable);
    }
  }

  @Test
  void scan_whenInterruptedAndResumedMultipleTimes_shouldCoverAllRecordsInPrimaryKeyOrder(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange
    int[] interruptionThresholds = {TEST_RECORD_COUNT / 3, 5, TEST_RECORD_COUNT / 4};

    // Act
    List<String> scannedRecordIds =
        scanWithInterruptions(
            TABLE,
            JdbcResumableScannerIntegrationTest::recordId,
            checkpointDir,
            interruptionThresholds);

    // Assert
    assertDeliveredInOrder(
        scannedRecordIds, expectedRecordIdsInOrder(), interruptionThresholds.length);
  }

  @ParameterizedTest
  @MethodSource("tableShapes")
  void scan_variousPrimaryKeyShapesGiven_shouldCoverAllRecordsInPrimaryKeyOrderAcrossResumes(
      TableShape shape, @TempDir Path checkpointDir) throws Exception {
    // Arrange
    admin.createTable(NAMESPACE, shape.table, shape.metadata, true);
    try {
      insertRecords(shape);
      int recordCount = shape.keysInScanOrder.size();
      int[] interruptionThresholds = {recordCount / 3, 5, recordCount / 4};

      // Act
      List<String> scannedRecordIds =
          scanWithInterruptions(
              shape.table, recordIdOf(shape.metadata), checkpointDir, interruptionThresholds);

      // Assert
      List<String> expectedRecordIds =
          shape.keysInScanOrder.stream()
              .map(JdbcResumableScannerIntegrationTest::recordId)
              .collect(Collectors.toList());
      assertDeliveredInOrder(scannedRecordIds, expectedRecordIds, interruptionThresholds.length);
    } finally {
      admin.dropTable(NAMESPACE, shape.table);
    }
  }

  @Test
  void scan_whenHandlerDeletesScannedRecords_shouldScanAllRecordsOnce(@TempDir Path checkpointDir)
      throws Exception {
    // Arrange
    String deletionTable = "deletion_table";
    admin.createTable(NAMESPACE, deletionTable, createStandardMetadata());
    insertTestRecords(deletionTable);
    Set<String> scannedRecordIds = new HashSet<>();
    RecordHandler deletingHandler =
        r -> {
          assertThat(scannedRecordIds.add(recordId(r))).isTrue();
          storage.delete(
              Delete.newBuilder()
                  .namespace(NAMESPACE)
                  .table(deletionTable)
                  .partitionKey(Key.ofText(PARTITION_KEY, r.getText(PARTITION_KEY)))
                  .clusteringKey(
                      Key.newBuilder()
                          .addInt(CLUSTERING_KEY_1, r.getInt(CLUSTERING_KEY_1))
                          .addText(CLUSTERING_KEY_2, r.getText(CLUSTERING_KEY_2))
                          .build())
                  .build());
        };

    try (JdbcResumableScanner scanner = new JdbcResumableScanner(databaseConfig, checkpointDir)) {
      // Act
      ScanResult result = scanner.scan(NAMESPACE, deletionTable, deletingHandler);

      // Assert
      assertThat(result.getTotalScanned()).isEqualTo(TEST_RECORD_COUNT);
      assertThat(scannedRecordIds).hasSize(TEST_RECORD_COUNT);
    } finally {
      admin.dropTable(NAMESPACE, deletionTable);
    }
  }

  @Test
  void scan_corruptedCheckpointFileGiven_shouldThrowIllegalStateException(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange
    Path tableCheckpointDir = checkpointDir.resolve(NAMESPACE + "." + TABLE);
    Files.createDirectories(tableCheckpointDir);
    Files.write(
        tableCheckpointDir.resolve(LAST_KEY_FILE),
        "GARBAGE_NOT_A_VALID_KEY".getBytes(StandardCharsets.UTF_8));

    // Act & Assert
    try (JdbcResumableScanner scanner = new JdbcResumableScanner(databaseConfig, checkpointDir)) {
      assertThatThrownBy(() -> scanner.scan(NAMESPACE, TABLE, r -> {}))
          .isInstanceOf(IllegalStateException.class);
    }
  }
}
