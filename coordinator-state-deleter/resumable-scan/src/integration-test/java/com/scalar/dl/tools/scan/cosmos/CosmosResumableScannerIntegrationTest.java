package com.scalar.dl.tools.scan.cosmos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.azure.cosmos.models.ThroughputProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Put;
import com.scalar.db.api.Result;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.service.StorageFactory;
import com.scalar.db.storage.cosmos.CosmosConfig;
import com.scalar.db.storage.cosmos.CosmosUtils;
import com.scalar.dl.tools.scan.RecordHandler;
import com.scalar.dl.tools.scan.ScanResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for {@link CosmosResumableScanner} that require a real Cosmos DB instance.
 *
 * <p>Run: {@code ./gradlew :resumable-scan:cosmosIntegrationTest -Dscalardb.cosmos.uri=...
 * -Dscalardb.cosmos.password=...}
 */
class CosmosResumableScannerIntegrationTest {

  private static final Logger logger =
      LoggerFactory.getLogger(CosmosResumableScannerIntegrationTest.class);

  private static final String NAMESPACE = "it_cosmos_scan";
  private static final String TABLE = "test_table";
  private static final String PARTITION_KEY = "pk";
  private static final String CLUSTERING_KEY = "ck";
  private static final String VALUE_COLUMN = "val";
  private static final String FEED_RANGES_FILE = "feed_ranges.json";

  /**
   * Number of records for the shared test table. Each logical partition must have more than the
   * default Cosmos DB page size (100 items) to guarantee that every physical partition produces
   * multiple pages and writes at least one continuation token (.token file). With {@link
   * #TEST_LOGICAL_PARTITION_COUNT} = 5, this gives 120 records per logical partition.
   */
  private static final int TEST_RECORD_COUNT = 600;

  private static final int TEST_LOGICAL_PARTITION_COUNT = 5;

  /**
   * Throughput high enough to force at least 2 physical partitions (~10,000 RU/s per partition).
   */
  private static final int HIGH_THROUGHPUT_RU = 12_000;

  private static final int HIGHER_THROUGHPUT_RU = 22_000;

  private static DatabaseConfig databaseConfig;
  private static DistributedStorageAdmin admin;
  private static DistributedStorage storage;

  @BeforeAll
  static void setUpAll() throws Exception {
    Properties props = CosmosEnv.getProperties();

    databaseConfig = new DatabaseConfig(props);
    StorageFactory storageFactory = StorageFactory.create(databaseConfig.getProperties());
    admin = storageFactory.getStorageAdmin();
    storage = storageFactory.getStorage();

    // Create test namespace and table
    admin.createNamespace(NAMESPACE);
    admin.createTable(NAMESPACE, TABLE, createStandardMetadata());
    recreateContainerWithDedicatedThroughput();

    // Insert test data
    for (int i = 0; i < TEST_RECORD_COUNT; i++) {
      Put put =
          Put.newBuilder()
              .namespace(NAMESPACE)
              .table(TABLE)
              .partitionKey(
                  Key.ofText(PARTITION_KEY, "partition-" + (i % TEST_LOGICAL_PARTITION_COUNT)))
              .clusteringKey(Key.ofInt(CLUSTERING_KEY, i))
              .textValue(VALUE_COLUMN, "value-" + i)
              .build();
      storage.put(put);
    }
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

  private static TableMetadata createStandardMetadata() {
    return TableMetadata.newBuilder()
        .addColumn(PARTITION_KEY, DataType.TEXT)
        .addColumn(CLUSTERING_KEY, DataType.INT)
        .addColumn(VALUE_COLUMN, DataType.TEXT)
        .addPartitionKey(PARTITION_KEY)
        .addClusteringKey(CLUSTERING_KEY)
        .build();
  }

  private static String recordId(Result result) {
    String pk = result.getText(PARTITION_KEY);
    int ck = result.getInt(CLUSTERING_KEY);
    return pk + ":" + ck;
  }

  /**
   * Waits for a partition split by polling the container's FeedRange count.
   *
   * @param container the Cosmos DB container to monitor
   * @param previousCount the FeedRange count before the expected split
   * @param timeoutSeconds maximum time to wait for the split
   * @return the new FeedRange count, or the last observed count if the timeout is reached
   */
  @SuppressWarnings("BusyWait")
  private static int waitForPartitionSplit(
      CosmosContainer container, int previousCount, long timeoutSeconds)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutSeconds * 1_000L;
    int count;
    do {
      count = container.getFeedRanges().size();
      if (count > previousCount) {
        return count;
      }
      logger.info("Waiting for physical partition split (current FeedRanges: {})...", count);
      Thread.sleep(10_000); // Sleep 10 seconds before retrying
    } while (System.currentTimeMillis() < deadline);
    return count;
  }

  /**
   * Recreates the given container with dedicated throughput (container-level) that exceeds the
   * per-partition threshold (~10,000 RU/s), ensuring Cosmos DB allocates multiple physical
   * partitions.
   */
  private static void recreateContainerWithDedicatedThroughput() throws Exception {
    try (CosmosClient cosmosClient =
        CosmosUtils.buildCosmosClient(new CosmosConfig(databaseConfig))) {
      CosmosDatabase database = cosmosClient.getDatabase(NAMESPACE);
      CosmosContainer container = database.getContainer(TABLE);

      // Save container properties before deletion
      CosmosContainerProperties containerProps = container.read().getProperties();

      // Delete shared-throughput container and recreate with dedicated throughput
      container.delete();
      database.createContainer(
          containerProps, ThroughputProperties.createManualThroughput(HIGH_THROUGHPUT_RU));
      logger.info("Recreated container with dedicated throughput of {} RU/s", HIGH_THROUGHPUT_RU);

      // Wait for Cosmos DB to allocate multiple physical partitions
      container = database.getContainer(TABLE);
      int feedRangeCount = waitForPartitionSplit(container, 1, 120);
      assertThat(feedRangeCount).isGreaterThanOrEqualTo(2);
      logger.info("Confirmed {} physical partitions for table {}", feedRangeCount, TABLE);
    }

    // Re-register stored procedures via ScalarDB's repairTable
    admin.repairTable(NAMESPACE, TABLE, createStandardMetadata(), Collections.emptyMap());
  }

  /**
   * Returns a consumer, simulating a scan interruption.
   *
   * @param recordIds the set to collect scanned record IDs into
   * @param threshold the number of records after which to simulate an interruption
   * @return a RecordHandler that collects record IDs into {@code recordIds} and throws a {@link
   *     RuntimeException} after being invoked {@code threshold} times
   */
  private static RecordHandler interruptingHandlerAfter(Set<String> recordIds, int threshold) {
    AtomicLong count = new AtomicLong();
    return r -> {
      recordIds.add(recordId(r));
      if (count.incrementAndGet() >= threshold) {
        throw new RuntimeException("Simulated interruption");
      }
    };
  }

  @Test
  void scan_withoutInterruption_shouldScanAllRecords(@TempDir Path checkpointDir) {
    // Arrange
    Set<String> threadNames = ConcurrentHashMap.newKeySet();
    AtomicLong count = new AtomicLong();

    try (CosmosResumableScanner scanner =
        new CosmosResumableScanner(databaseConfig, checkpointDir)) {
      // Act
      ScanResult result =
          scanner.scan(
              NAMESPACE,
              TABLE,
              r -> {
                threadNames.add(Thread.currentThread().getName());
                count.incrementAndGet();
              });

      // Assert
      assertThat(result.getTotalScanned()).isEqualTo(TEST_RECORD_COUNT);
      assertThat(count.get()).isEqualTo(TEST_RECORD_COUNT);
    }

    // Multiple physical partitions are scanned in parallel
    assertThat(threadNames.size()).isGreaterThanOrEqualTo(2);
    // Successful scan clears checkpoint files
    assertThat(checkpointDir.resolve(NAMESPACE + "." + TABLE)).doesNotExist();
  }

  @Test
  void scan_againstEmptyTable_shouldReturnZero(@TempDir Path checkpointDir) throws Exception {
    // Arrange
    String emptyTable = "empty_table";
    admin.createTable(
        NAMESPACE,
        emptyTable,
        TableMetadata.newBuilder()
            .addColumn(PARTITION_KEY, DataType.TEXT)
            .addColumn(CLUSTERING_KEY, DataType.INT)
            .addPartitionKey(PARTITION_KEY)
            .addClusteringKey(CLUSTERING_KEY)
            .build());

    AtomicLong count = new AtomicLong();

    try (CosmosResumableScanner scanner =
        new CosmosResumableScanner(databaseConfig, checkpointDir)) {
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
  void scan_interruptedAndResumed_shouldCoverAllRecords(@TempDir Path checkpointDir)
      throws IOException {
    // Arrange 1
    // First scan interrupted halfway
    Set<String> firstScannedRecordIds = ConcurrentHashMap.newKeySet();

    try (CosmosResumableScanner scanner =
        new CosmosResumableScanner(databaseConfig, checkpointDir)) {
      try {
        // Act 1
        // First scan
        scanner.scan(
            NAMESPACE,
            TABLE,
            interruptingHandlerAfter(firstScannedRecordIds, TEST_RECORD_COUNT / 2));
      } catch (Exception e) {
        // Ignore expected interruption
      }
    }

    // Assert 1
    // Checkpoint files exist after interruption
    Path tableCheckpointDir = checkpointDir.resolve(NAMESPACE + "." + TABLE);
    assertThat(tableCheckpointDir).isDirectory();
    assertThat(tableCheckpointDir.resolve(FEED_RANGES_FILE)).exists();
    // At least one .token file must exist for the resume to actually use checkpoints
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(tableCheckpointDir, "*.token")) {
      assertThat(stream.iterator().hasNext()).isTrue();
    }

    // Arrange 2
    // Second scan to resume from checkpoint
    Set<String> secondScannedRecordIds = ConcurrentHashMap.newKeySet();
    try (CosmosResumableScanner scanner =
        new CosmosResumableScanner(databaseConfig, checkpointDir)) {
      // Act 2
      // Second scan
      scanner.scan(NAMESPACE, TABLE, r -> secondScannedRecordIds.add(recordId(r)));
    }

    // Assert 2
    // Union of both runs covers all records
    Set<String> allScannedRecordIds = ConcurrentHashMap.newKeySet();
    allScannedRecordIds.addAll(firstScannedRecordIds);
    allScannedRecordIds.addAll(secondScannedRecordIds);
    assertThat(allScannedRecordIds).hasSize(TEST_RECORD_COUNT);
    // The resumed scan skips already-checkpointed pages, so it processes fewer records than the
    // full count.
    assertThat(secondScannedRecordIds.size()).isLessThan(TEST_RECORD_COUNT);
    // Successful scan clears checkpoint files
    assertThat(tableCheckpointDir).doesNotExist();
  }

  @Test
  void scan_whenLosingCheckpointedData_shouldRescanUncheckpointedPagesAndCompleteSuccessfully(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange
    // First scan interrupted after scanning 3/4 of the records, ensuring multiple checkpoint files
    // are created.
    Set<String> firstScannedRecordIds = ConcurrentHashMap.newKeySet();

    try (CosmosResumableScanner scanner =
        new CosmosResumableScanner(databaseConfig, checkpointDir)) {
      try {
        scanner.scan(
            NAMESPACE,
            TABLE,
            interruptingHandlerAfter(firstScannedRecordIds, TEST_RECORD_COUNT * 3 / 4));
      } catch (Exception e) {
        // Ignore expected interruption
      }
    }

    // At least 2 .token files must exist: one is to delete (simulating loss) and at least one more
    // to ensure the resume logic still has checkpoint data to work with
    String qualifiedTable = NAMESPACE + "." + TABLE;
    Path tableCheckpointDir = checkpointDir.resolve(qualifiedTable);
    int tokenFileCount = 0;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(tableCheckpointDir, "*.token")) {
      for (Path ignored : stream) {
        tokenFileCount++;
      }
    }
    assertThat(tokenFileCount).isGreaterThanOrEqualTo(2);

    // Delete the first .token file to simulate a scenario where some checkpointed pages are lost
    boolean deleted = false;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(tableCheckpointDir, "*.token")) {
      for (Path tokenFile : stream) {
        if (!deleted) {
          Files.delete(tokenFile);
          deleted = true;
        }
      }
    }
    assertThat(deleted).isTrue();

    // Act
    // Resume scan
    Set<String> secondScannedRecordIds = ConcurrentHashMap.newKeySet();
    try (CosmosResumableScanner scanner =
        new CosmosResumableScanner(databaseConfig, checkpointDir)) {
      scanner.scan(NAMESPACE, TABLE, r -> secondScannedRecordIds.add(recordId(r)));
    }

    // Assert
    // Union of both runs covers all records
    Set<String> allScannedRecordIds = ConcurrentHashMap.newKeySet();
    allScannedRecordIds.addAll(firstScannedRecordIds);
    allScannedRecordIds.addAll(secondScannedRecordIds);
    assertThat(allScannedRecordIds).hasSize(TEST_RECORD_COUNT);
  }

  @Test
  void scan_withCorruptedTokenFile_shouldFailWithException(@TempDir Path checkpointDir)
      throws Exception {
    // Arrange
    // Interrupt a scan to leave checkpoint files
    Set<String> scannedRecordIds = ConcurrentHashMap.newKeySet();

    try (CosmosResumableScanner scanner =
        new CosmosResumableScanner(databaseConfig, checkpointDir)) {
      try {
        scanner.scan(
            NAMESPACE, TABLE, interruptingHandlerAfter(scannedRecordIds, TEST_RECORD_COUNT / 2));
      } catch (Exception e) {
        // Ignore expected interruption
      }
    }

    // At least one .token file must exist for the test to be meaningful
    String qualifiedTable = NAMESPACE + "." + TABLE;
    Path tableCheckpointDir = checkpointDir.resolve(qualifiedTable);
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(tableCheckpointDir, "*.token")) {
      assertThat(stream.iterator().hasNext()).isTrue();
    }

    // Create a corrupted .token file for the first FeedRange
    Path feedRangesPath = tableCheckpointDir.resolve(FEED_RANGES_FILE);
    ObjectMapper mapper = new ObjectMapper();
    List<String> rangeJsonList =
        mapper.readValue(Files.readAllBytes(feedRangesPath), new TypeReference<List<String>>() {});
    assertThat(rangeJsonList).isNotEmpty();

    String rangeId = FeedRangeSerializer.toId(FeedRangeSerializer.fromJson(rangeJsonList.get(0)));
    Path tokenFile = tableCheckpointDir.resolve(rangeId + ".token");
    Files.write(tokenFile, "GARBAGE_NOT_A_VALID_TOKEN".getBytes(StandardCharsets.UTF_8));

    // Act & Assert
    assertThatThrownBy(
            () -> {
              try (CosmosResumableScanner scanner =
                  new CosmosResumableScanner(databaseConfig, checkpointDir)) {
                scanner.scan(NAMESPACE, TABLE, r -> {});
              }
            })
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void scan_resumedAfterPartitionSplit_shouldCoverAllRecordsUsingStaleFeedRanges(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange
    Set<String> firstScannedRecordIds = ConcurrentHashMap.newKeySet();
    int initialFeedRangeCount;
    int newFeedRangeCount;

    try (CosmosClient cosmosClient =
        CosmosUtils.buildCosmosClient(new CosmosConfig(databaseConfig))) {
      CosmosContainer container = cosmosClient.getDatabase(NAMESPACE).getContainer(TABLE);
      initialFeedRangeCount = container.getFeedRanges().size();

      // First scan interrupted halfway
      try (CosmosResumableScanner scanner =
          new CosmosResumableScanner(databaseConfig, checkpointDir)) {
        try {
          scanner.scan(
              NAMESPACE,
              TABLE,
              interruptingHandlerAfter(firstScannedRecordIds, TEST_RECORD_COUNT / 2));
        } catch (Exception e) {
          // Ignore expected interruption
        }
      }

      // Trigger partition split by increasing throughput. Cosmos DB performs splits
      // asynchronously, and it can take some minutes in practice, so we use a long timeout (10m).
      container.replaceThroughput(
          ThroughputProperties.createManualThroughput(HIGHER_THROUGHPUT_RU));
      newFeedRangeCount = waitForPartitionSplit(container, initialFeedRangeCount, 600);
      assertThat(newFeedRangeCount)
          .as("Partition split did not occur within the timeout")
          .isGreaterThan(initialFeedRangeCount);
    }

    // Act
    // Resume scan using stale (pre-split) feed ranges from checkpoint
    Set<String> secondScannedRecordIds = ConcurrentHashMap.newKeySet();
    try (CosmosResumableScanner scanner =
        new CosmosResumableScanner(databaseConfig, checkpointDir)) {
      scanner.scan(NAMESPACE, TABLE, r -> secondScannedRecordIds.add(recordId(r)));
    }

    // Assert
    // Union of both runs covers all records
    Set<String> allScannedRecordIds = ConcurrentHashMap.newKeySet();
    allScannedRecordIds.addAll(firstScannedRecordIds);
    allScannedRecordIds.addAll(secondScannedRecordIds);
    assertThat(allScannedRecordIds).hasSize(TEST_RECORD_COUNT);
    // The resumed scan skips already-checkpointed pages, so it processes fewer records than the
    // full count.
    assertThat(secondScannedRecordIds.size()).isLessThan(TEST_RECORD_COUNT);
  }
}
