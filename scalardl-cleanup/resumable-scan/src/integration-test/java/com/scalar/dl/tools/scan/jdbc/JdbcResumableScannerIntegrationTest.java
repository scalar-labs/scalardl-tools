package com.scalar.dl.tools.scan.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Put;
import com.scalar.db.api.Scan;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.io.BlobColumn;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.service.StorageFactory;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import com.scalar.dl.tools.scan.RecordHandler;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the JDBC scanner against a real database: that a full scan delivers every record, that a
 * resumed scan delivers every record at least once, and that the keyset paging holds for composite,
 * descending, and non-text key columns.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcResumableScannerIntegrationTest {

  private static final String NAMESPACE = "jdbc_scan_it";
  private static final String SINGLE_KEY_TABLE = "single_key";
  private static final String COMPOSITE_KEY_TABLE = "composite_key";
  private static final String THREE_COLUMN_KEY_TABLE = "three_column_key";
  private static final String DESC_KEY_TABLE = "desc_key";
  private static final String MIXED_TYPE_KEY_TABLE = "mixed_type_key";
  private static final String EMPTY_TABLE = "empty_table";
  private static final int PAGE_SIZE = 10;

  private Properties properties;
  private DistributedStorage storage;
  private DistributedStorageAdmin admin;

  @TempDir Path checkpointDir;

  private static TableMetadata singleKeyMetadata() {
    return TableMetadata.newBuilder()
        .addColumn("id", DataType.TEXT)
        .addColumn("value", DataType.TEXT)
        .addPartitionKey("id")
        .build();
  }

  private static TableMetadata compositeKeyMetadata(Scan.Ordering.Order order) {
    return TableMetadata.newBuilder()
        .addColumn("id", DataType.TEXT)
        .addColumn("age", DataType.INT)
        .addColumn("value", DataType.TEXT)
        .addPartitionKey("id")
        .addClusteringKey("age", order)
        .build();
  }

  private static TableMetadata threeColumnKeyMetadata() {
    return TableMetadata.newBuilder()
        .addColumn("a", DataType.TEXT)
        .addColumn("b", DataType.INT)
        .addColumn("c", DataType.INT)
        .addColumn("value", DataType.TEXT)
        .addPartitionKey("a")
        .addClusteringKey("b")
        .addClusteringKey("c")
        .build();
  }

  private static TableMetadata mixedTypeKeyMetadata() {
    return TableMetadata.newBuilder()
        .addColumn("payload", DataType.BLOB)
        .addColumn("d", DataType.DATE)
        .addColumn("value", DataType.TEXT)
        .addPartitionKey("payload")
        .addClusteringKey("d")
        .build();
  }

  @BeforeAll
  void setUpAll() throws Exception {
    properties = JdbcEnv.getProperties();
    StorageFactory factory = StorageFactory.create(properties);
    storage = factory.getStorage();
    admin = factory.getStorageAdmin();

    admin.createNamespace(NAMESPACE, true);
    createTable(SINGLE_KEY_TABLE, singleKeyMetadata());
    createTable(COMPOSITE_KEY_TABLE, compositeKeyMetadata(Scan.Ordering.Order.ASC));
    createTable(THREE_COLUMN_KEY_TABLE, threeColumnKeyMetadata());
    createTable(DESC_KEY_TABLE, compositeKeyMetadata(Scan.Ordering.Order.DESC));
    createTable(MIXED_TYPE_KEY_TABLE, mixedTypeKeyMetadata());
    createTable(EMPTY_TABLE, singleKeyMetadata());

    populateSingleKey(SINGLE_KEY_TABLE, 25);
    populateComposite(COMPOSITE_KEY_TABLE, 7, 4);
    populateThreeColumn(THREE_COLUMN_KEY_TABLE, 4, 3, 3);
    populateComposite(DESC_KEY_TABLE, 7, 4);
    populateMixedType(MIXED_TYPE_KEY_TABLE, 12);
  }

  @AfterAll
  void tearDownAll() throws Exception {
    if (admin != null) {
      for (String table :
          new String[] {
            SINGLE_KEY_TABLE,
            COMPOSITE_KEY_TABLE,
            THREE_COLUMN_KEY_TABLE,
            DESC_KEY_TABLE,
            MIXED_TYPE_KEY_TABLE,
            EMPTY_TABLE
          }) {
        admin.dropTable(NAMESPACE, table, true);
      }
      admin.dropNamespace(NAMESPACE, true);
      admin.close();
    }
    if (storage != null) {
      storage.close();
    }
  }

  private void createTable(String table, TableMetadata metadata) throws Exception {
    admin.createTable(NAMESPACE, table, metadata, true);
    admin.truncateTable(NAMESPACE, table);
  }

  private void populateSingleKey(String table, int count) throws Exception {
    for (int i = 0; i < count; i++) {
      storage.put(
          Put.newBuilder()
              .namespace(NAMESPACE)
              .table(table)
              .partitionKey(Key.ofText("id", String.format("id-%04d", i)))
              .textValue("value", "v" + i)
              .build());
    }
  }

  private void populateComposite(String table, int ids, int ages) throws Exception {
    for (int i = 0; i < ids; i++) {
      for (int age = 0; age < ages; age++) {
        storage.put(
            Put.newBuilder()
                .namespace(NAMESPACE)
                .table(table)
                .partitionKey(Key.ofText("id", String.format("id-%04d", i)))
                .clusteringKey(Key.ofInt("age", age))
                .textValue("value", "v" + i + "-" + age)
                .build());
      }
    }
  }

  private void populateThreeColumn(String table, int as, int bs, int cs) throws Exception {
    for (int a = 0; a < as; a++) {
      for (int b = 0; b < bs; b++) {
        for (int c = 0; c < cs; c++) {
          storage.put(
              Put.newBuilder()
                  .namespace(NAMESPACE)
                  .table(table)
                  .partitionKey(Key.ofText("a", String.format("a-%04d", a)))
                  .clusteringKey(Key.newBuilder().addInt("b", b).addInt("c", c).build())
                  .textValue("value", a + "-" + b + "-" + c)
                  .build());
        }
      }
    }
  }

  private void populateMixedType(String table, int count) throws Exception {
    for (int i = 0; i < count; i++) {
      storage.put(
          Put.newBuilder()
              .namespace(NAMESPACE)
              .table(table)
              .partitionKey(
                  Key.newBuilder()
                      .add(
                          BlobColumn.of(
                              "payload",
                              String.format("k-%04d", i).getBytes(StandardCharsets.UTF_8)))
                      .build())
              .clusteringKey(Key.ofDate("d", LocalDate.of(2026, 1, 1).plusDays(i)))
              .textValue("value", "v" + i)
              .build());
    }
  }

  private ResumableScanner newScanner(Path dir) {
    Properties scanProperties = new Properties();
    scanProperties.putAll(properties);
    scanProperties.setProperty(
        JdbcResumableScannerConfig.PROP_PAGE_SIZE, String.valueOf(PAGE_SIZE));
    return new JdbcResumableScanner(new DatabaseConfig(scanProperties), dir);
  }

  /** Scans the table and returns the delivered keys, asserting each was delivered exactly once. */
  private List<String> scanKeys(String table, List<String> keyColumns) throws Exception {
    List<String> keys = Collections.synchronizedList(new ArrayList<>());
    try (ResumableScanner scanner = newScanner(checkpointDir.resolve(table))) {
      ScanResult result =
          scanner.scan(NAMESPACE, table, r -> keys.add(keyOf(r.getColumns(), keyColumns)));
      assertThat(result.getTotalScanned()).isEqualTo(keys.size());
    }
    return keys;
  }

  private static String keyOf(
      Map<String, com.scalar.db.io.Column<?>> columns, List<String> keyColumns) {
    StringBuilder builder = new StringBuilder();
    for (String name : keyColumns) {
      builder.append(columns.get(name).toString()).append('|');
    }
    return builder.toString();
  }

  @Test
  void scan_singleKeyTableGiven_shouldDeliverEveryRecordOnce() throws Exception {
    // Act
    List<String> keys = scanKeys(SINGLE_KEY_TABLE, Collections.singletonList("id"));

    // Assert
    assertThat(keys).hasSize(25);
    assertThat(new HashSet<>(keys)).hasSize(25);
  }

  @Test
  void scan_compositeKeyTableGiven_shouldDeliverEveryRecordOnce() throws Exception {
    // Act
    List<String> keys = scanKeys(COMPOSITE_KEY_TABLE, java.util.Arrays.asList("id", "age"));

    // Assert
    // 7 partitions x 4 clustering values, with pages of 10 crossing partition boundaries.
    assertThat(keys).hasSize(28);
    assertThat(new HashSet<>(keys)).hasSize(28);
  }

  @Test
  void scan_threeColumnKeyTableGiven_shouldDeliverEveryRecordOnce() throws Exception {
    // Act
    // A page boundary here can fall between records whose first two key columns are equal, which
    // is the case a resume condition that is strict too early would skip.
    List<String> keys = scanKeys(THREE_COLUMN_KEY_TABLE, java.util.Arrays.asList("a", "b", "c"));

    // Assert
    assertThat(keys).hasSize(36);
    assertThat(new HashSet<>(keys)).hasSize(36);
  }

  @Test
  void scan_descendingClusteringOrderGiven_shouldDeliverEveryRecordOnce() throws Exception {
    // Act
    List<String> keys = scanKeys(DESC_KEY_TABLE, java.util.Arrays.asList("id", "age"));

    // Assert
    assertThat(keys).hasSize(28);
    assertThat(new HashSet<>(keys)).hasSize(28);
  }

  @Test
  void scan_blobAndDateKeysGiven_shouldDeliverEveryRecordOnce() throws Exception {
    // Act
    List<String> keys = scanKeys(MIXED_TYPE_KEY_TABLE, java.util.Arrays.asList("payload", "d"));

    // Assert
    assertThat(keys).hasSize(12);
    assertThat(new HashSet<>(keys)).hasSize(12);
  }

  @Test
  void scan_emptyTableGiven_shouldScanNothing() throws Exception {
    // Act
    List<String> keys = scanKeys(EMPTY_TABLE, Collections.singletonList("id"));

    // Assert
    assertThat(keys).isEmpty();
  }

  @Test
  void scan_afterFailureGiven_shouldResumeAndDeliverEveryRecordAtLeastOnce() throws Exception {
    // Arrange
    // Fail partway through, then re-run against the same checkpoint directory.
    Path dir = checkpointDir.resolve("resume");
    Map<String, Integer> counts = new ConcurrentHashMap<>();
    AtomicInteger delivered = new AtomicInteger();
    int failAfter = PAGE_SIZE * 2 + 3;
    RecordHandler countingHandler =
        r -> {
          counts.merge(r.getColumns().get("id").toString(), 1, Integer::sum);
          if (delivered.incrementAndGet() > failAfter) {
            throw new IllegalStateException("injected failure");
          }
        };

    // Act
    try (ResumableScanner scanner = newScanner(dir)) {
      assertThatThrownBy(() -> scanner.scan(NAMESPACE, SINGLE_KEY_TABLE, countingHandler))
          .isInstanceOf(IllegalStateException.class);
    }
    Map<String, Integer> afterFailure = new HashMap<>(counts);
    try (ResumableScanner scanner = newScanner(dir)) {
      scanner.scan(
          NAMESPACE,
          SINGLE_KEY_TABLE,
          r -> counts.merge(r.getColumns().get("id").toString(), 1, Integer::sum));
    }

    // Assert
    assertThat(counts).hasSize(25);
    assertThat(counts.values()).allMatch(count -> count >= 1);
    // Only the page that was in flight when the failure hit may be re-delivered.
    long redelivered = counts.values().stream().filter(count -> count > 1).count();
    assertThat(redelivered).isLessThanOrEqualTo(PAGE_SIZE);
    assertThat(afterFailure).isNotEmpty();
  }

  @Test
  void scan_handlerDeletingScannedRecordsGiven_shouldDeliverEveryRecordOnce() throws Exception {
    // Arrange
    // The cleanup tools delete the records they scan; keyset paging must not skip the records that
    // follow a deleted one.
    String table = "delete_during_scan";
    createTable(table, singleKeyMetadata());
    populateSingleKey(table, 25);
    Set<String> seen = Collections.synchronizedSet(new HashSet<>());

    // Act
    try (ResumableScanner scanner = newScanner(checkpointDir.resolve(table))) {
      scanner.scan(
          NAMESPACE,
          table,
          r -> {
            String id = r.getColumns().get("id").getTextValue();
            assertThat(seen.add(id)).isTrue();
            storage.delete(
                com.scalar.db.api.Delete.newBuilder()
                    .namespace(NAMESPACE)
                    .table(table)
                    .partitionKey(Key.ofText("id", id))
                    .build());
          });
    }

    // Assert
    assertThat(seen).hasSize(25);
    admin.dropTable(NAMESPACE, table, true);
  }

  @Test
  void scan_corruptedCheckpointGiven_shouldFailInsteadOfSkippingRecords() throws Exception {
    // Arrange
    Path dir = checkpointDir.resolve("corrupted");
    java.nio.file.Files.createDirectories(dir.resolve(NAMESPACE + "." + SINGLE_KEY_TABLE));
    java.nio.file.Files.write(
        dir.resolve(NAMESPACE + "." + SINGLE_KEY_TABLE).resolve("keyset.token"),
        "{not json".getBytes(StandardCharsets.UTF_8));

    // Act & Assert
    try (ResumableScanner scanner = newScanner(dir)) {
      assertThatThrownBy(() -> scanner.scan(NAMESPACE, SINGLE_KEY_TABLE, r -> {}))
          .isInstanceOf(ScalarDlCleanupException.class);
    }
  }
}
