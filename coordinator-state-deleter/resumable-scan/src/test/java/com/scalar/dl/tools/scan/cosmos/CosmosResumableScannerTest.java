package com.scalar.dl.tools.scan.cosmos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.models.FeedRange;
import com.azure.cosmos.models.FeedResponse;
import com.azure.cosmos.util.CosmosPagedIterable;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Result;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.DataType;
import com.scalar.db.storage.cosmos.Record;
import com.scalar.dl.tools.scan.ScanResult;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CosmosResumableScannerTest {

  private static final String NAMESPACE = "ns";
  private static final String TABLE = "table";
  private static final String QUALIFIED_TABLE = NAMESPACE + "." + TABLE;

  @SuppressWarnings("unchecked")
  private final Consumer<Result> consumer = mock(Consumer.class);

  private CosmosClient cosmosClient;
  private CosmosDatabase cosmosDatabase;
  private CosmosContainer cosmosContainer;
  private CheckpointManager checkpointManager;
  private DistributedStorageAdmin storageAdmin;

  @BeforeEach
  void setUp() throws Exception {
    cosmosClient = mock(CosmosClient.class);
    cosmosDatabase = mock(CosmosDatabase.class);
    cosmosContainer = mock(CosmosContainer.class);
    checkpointManager = mock(CheckpointManager.class);
    storageAdmin = mock(DistributedStorageAdmin.class);

    when(cosmosClient.getDatabase(NAMESPACE)).thenReturn(cosmosDatabase);
    when(cosmosDatabase.getContainer(TABLE)).thenReturn(cosmosContainer);

    // Default: return valid table metadata with a partition key
    TableMetadata metadata =
        TableMetadata.newBuilder().addColumn("id", DataType.TEXT).addPartitionKey("id").build();
    when(storageAdmin.getTableMetadata(NAMESPACE, TABLE)).thenReturn(metadata);
  }

  private CosmosResumableScanner createScanner() {
    return new CosmosResumableScanner(cosmosClient, checkpointManager, storageAdmin);
  }

  private FeedRange createMockFeedRange(String label) {
    FeedRange feedRange = mock(FeedRange.class);
    when(feedRange.toString()).thenReturn("{\"Range\":{\"min\":\"" + label + "\"}}");
    return feedRange;
  }

  private void setupSingleFeedRange(int recordCount) {
    FeedRange feedRange = createMockFeedRange("full");
    List<FeedRange> feedRanges = Collections.singletonList(feedRange);

    List<Record> records = CosmosMockHelper.createMockRecords(recordCount);
    FeedResponse<Record> page = CosmosMockHelper.createMockPage(records, null);
    CosmosPagedIterable<Record> iterable =
        CosmosMockHelper.createMockPagedIterable(Collections.singletonList(page));

    when(cosmosContainer.queryItems(anyString(), any(), eq(Record.class))).thenReturn(iterable);
    when(cosmosContainer.getFeedRanges()).thenReturn(feedRanges);
  }

  private void setupNoCheckpoint() {
    when(checkpointManager.loadFeedRanges(QUALIFIED_TABLE)).thenReturn(null);
    when(checkpointManager.loadContinuationToken(anyString(), anyString())).thenReturn(null);
  }

  @Test
  void scan_noPersistedFeedRanges_shouldDiscoverAndPersistFeedRanges() {
    // Arrange
    setupSingleFeedRange(1);
    setupNoCheckpoint();

    // Act
    try (CosmosResumableScanner scanner = createScanner()) {
      scanner.scan(NAMESPACE, TABLE, consumer);
    }

    // Assert
    verify(cosmosContainer).getFeedRanges();
    verify(checkpointManager).persistFeedRanges(eq(QUALIFIED_TABLE), anyString());
  }

  @Test
  void scan_persistedFeedRangesExist_shouldLoadFromCheckpoint() {
    // Arrange
    FeedRange feedRange = FeedRange.forFullRange();
    String persistedJson = "[\"" + FeedRangeSerializer.toJson(feedRange) + "\"]";
    when(checkpointManager.loadFeedRanges(QUALIFIED_TABLE)).thenReturn(persistedJson);
    when(checkpointManager.loadContinuationToken(anyString(), anyString())).thenReturn(null);

    List<Record> records = CosmosMockHelper.createMockRecords(1);
    FeedResponse<Record> page = CosmosMockHelper.createMockPage(records, null);
    CosmosPagedIterable<Record> iterable =
        CosmosMockHelper.createMockPagedIterable(Collections.singletonList(page));
    when(cosmosContainer.queryItems(anyString(), any(), eq(Record.class))).thenReturn(iterable);

    // Act
    try (CosmosResumableScanner scanner = createScanner()) {
      scanner.scan(NAMESPACE, TABLE, consumer);
    }

    // Assert
    verify(cosmosContainer, never()).getFeedRanges();
    verify(checkpointManager, never()).persistFeedRanges(anyString(), anyString());
    verify(checkpointManager).loadFeedRanges(QUALIFIED_TABLE);
  }

  @Test
  void scan_singleFeedRange_shouldReturnTotalScanned() {
    // Arrange
    setupSingleFeedRange(5);
    setupNoCheckpoint();

    // Act & Assert
    try (CosmosResumableScanner scanner = createScanner()) {
      ScanResult result = scanner.scan(NAMESPACE, TABLE, consumer);
      assertThat(result.getTotalScanned()).isEqualTo(5);
    }
  }

  @Test
  void scan_multipleFeedRanges_shouldSumCounts() {
    // Arrange
    FeedRange range1 = createMockFeedRange("r1");
    FeedRange range2 = createMockFeedRange("r2");
    FeedRange range3 = createMockFeedRange("r3");
    List<FeedRange> feedRanges = Arrays.asList(range1, range2, range3);

    // Each range scans 2 records => total 6
    List<Record> records = CosmosMockHelper.createMockRecords(2);
    FeedResponse<Record> page = CosmosMockHelper.createMockPage(records, null);
    CosmosPagedIterable<Record> iterable =
        CosmosMockHelper.createMockPagedIterable(Collections.singletonList(page));

    when(cosmosContainer.queryItems(anyString(), any(), eq(Record.class))).thenReturn(iterable);
    when(cosmosContainer.getFeedRanges()).thenReturn(feedRanges);
    setupNoCheckpoint();

    // Act & Assert
    try (CosmosResumableScanner scanner = createScanner()) {
      ScanResult result = scanner.scan(NAMESPACE, TABLE, consumer);
      assertThat(result.getTotalScanned()).isEqualTo(6);
    }
  }

  @Test
  void scan_continuationTokenExists_shouldResumeFromToken() {
    // Arrange
    FeedRange feedRange = createMockFeedRange("full");
    String feedRangeId = FeedRangeSerializer.toId(feedRange);
    List<FeedRange> feedRanges = Collections.singletonList(feedRange);

    when(cosmosContainer.getFeedRanges()).thenReturn(feedRanges);
    when(checkpointManager.loadFeedRanges(QUALIFIED_TABLE)).thenReturn(null);
    when(checkpointManager.loadContinuationToken(QUALIFIED_TABLE, feedRangeId))
        .thenReturn("resume-token");

    List<Record> records = CosmosMockHelper.createMockRecords(1);
    FeedResponse<Record> page = CosmosMockHelper.createMockPage(records, null);
    List<FeedResponse<Record>> resumePages = Collections.singletonList(page);
    CosmosPagedIterable<Record> iterable =
        CosmosMockHelper.createMockPagedIterableWithResume(Collections.emptyList(), resumePages);
    when(cosmosContainer.queryItems(anyString(), any(), eq(Record.class))).thenReturn(iterable);

    // Act
    try (CosmosResumableScanner scanner = createScanner()) {
      ScanResult result = scanner.scan(NAMESPACE, TABLE, consumer);

      // Assert
      assertThat(result.getTotalScanned()).isEqualTo(1);
    }
    verify(iterable).iterableByPage("resume-token");
  }

  @Test
  void scan_workerThrowsException_shouldPropagateException() {
    // Arrange
    setupSingleFeedRange(1);
    setupNoCheckpoint();
    doThrow(new RuntimeException("worker error")).when(consumer).accept(any(Result.class));

    // Act & Assert
    try (CosmosResumableScanner scanner = createScanner()) {
      assertThatThrownBy(() -> scanner.scan(NAMESPACE, TABLE, consumer))
          .isInstanceOf(RuntimeException.class)
          .hasMessage("worker error");
    }
  }

  @Test
  void scan_tableMetadataNotFound_shouldThrowIllegalStateException() throws Exception {
    // Arrange
    setupSingleFeedRange(1);
    setupNoCheckpoint();
    // Override storageAdmin to return null metadata
    when(storageAdmin.getTableMetadata(NAMESPACE, TABLE)).thenReturn(null);

    // Act & Assert
    try (CosmosResumableScanner scanner = createScanner()) {
      assertThatThrownBy(() -> scanner.scan(NAMESPACE, TABLE, consumer))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void scan_calledMultipleTimes_shouldNotThrowException() {
    // Arrange
    String table2 = "table2";
    String qualifiedTable2 = NAMESPACE + "." + table2;
    CosmosContainer container2 = mock(CosmosContainer.class);
    when(cosmosDatabase.getContainer(table2)).thenReturn(container2);

    TableMetadata metadata2 =
        TableMetadata.newBuilder().addColumn("id", DataType.TEXT).addPartitionKey("id").build();
    try {
      when(storageAdmin.getTableMetadata(NAMESPACE, table2)).thenReturn(metadata2);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    FeedRange feedRange = createMockFeedRange("full");
    List<FeedRange> feedRanges = Collections.singletonList(feedRange);

    FeedResponse<Record> emptyPage = CosmosMockHelper.createMockPage(Collections.emptyList(), null);
    CosmosPagedIterable<Record> iterable =
        CosmosMockHelper.createMockPagedIterable(Collections.singletonList(emptyPage));

    when(cosmosContainer.queryItems(anyString(), any(), eq(Record.class))).thenReturn(iterable);
    when(cosmosContainer.getFeedRanges()).thenReturn(feedRanges);
    when(container2.queryItems(anyString(), any(), eq(Record.class))).thenReturn(iterable);
    when(container2.getFeedRanges()).thenReturn(feedRanges);
    when(checkpointManager.loadFeedRanges(QUALIFIED_TABLE)).thenReturn(null);
    when(checkpointManager.loadFeedRanges(qualifiedTable2)).thenReturn(null);
    when(checkpointManager.loadContinuationToken(anyString(), anyString())).thenReturn(null);

    // Act & Assert
    try (CosmosResumableScanner scanner = createScanner()) {
      assertThatCode(
              () -> {
                scanner.scan(NAMESPACE, TABLE, consumer);
                scanner.scan(NAMESPACE, table2, consumer);
              })
          .doesNotThrowAnyException();
    }
  }

  @Test
  void scan_doScanSucceeds_shouldClearAllCheckpoints() {
    // Arrange
    setupSingleFeedRange(1);
    setupNoCheckpoint();

    // Act
    try (CosmosResumableScanner scanner = createScanner()) {
      scanner.scan(NAMESPACE, TABLE, consumer);
    }

    // Assert
    verify(checkpointManager).clearCheckpointFor(QUALIFIED_TABLE);
  }

  @Test
  void scan_doScanFails_shouldNotClearCheckpoints() {
    // Arrange
    setupSingleFeedRange(1);
    setupNoCheckpoint();
    doThrow(new RuntimeException("worker error")).when(consumer).accept(any(Result.class));

    // Act
    try (CosmosResumableScanner scanner = createScanner()) {
      assertThatThrownBy(() -> scanner.scan(NAMESPACE, TABLE, consumer))
          .isInstanceOf(RuntimeException.class);
    }

    // Assert
    verify(checkpointManager, never()).clearCheckpointFor(anyString());
  }

  @Test
  void scan_withCustomMaxWorkerThreads_shouldLimitThreadCount() {
    // Arrange
    FeedRange range1 = createMockFeedRange("r1");
    FeedRange range2 = createMockFeedRange("r2");
    FeedRange range3 = createMockFeedRange("r3");
    List<FeedRange> feedRanges = Arrays.asList(range1, range2, range3);
    int maxWorkerThreads = 1;

    List<Record> records = CosmosMockHelper.createMockRecords(2);
    FeedResponse<Record> page = CosmosMockHelper.createMockPage(records, null);
    CosmosPagedIterable<Record> iterable =
        CosmosMockHelper.createMockPagedIterable(Collections.singletonList(page));

    when(cosmosContainer.queryItems(anyString(), any(), eq(Record.class))).thenReturn(iterable);
    when(cosmosContainer.getFeedRanges()).thenReturn(feedRanges);
    setupNoCheckpoint();

    Set<String> threadNames = ConcurrentHashMap.newKeySet();

    // Act
    try (CosmosResumableScanner scanner =
        new CosmosResumableScanner(
            cosmosClient, checkpointManager, storageAdmin, maxWorkerThreads)) {
      scanner.scan(NAMESPACE, TABLE, r -> threadNames.add(Thread.currentThread().getName()));
    }

    // Assert
    assertThat(threadNames).hasSize(maxWorkerThreads);
  }

  @Test
  void close_shouldCloseClientAndStorageAdmin() {
    // Arrange
    setupSingleFeedRange(0);
    setupNoCheckpoint();
    CosmosResumableScanner scanner = createScanner();
    scanner.scan(NAMESPACE, TABLE, consumer);

    // Act
    scanner.close();

    // Assert
    verify(cosmosClient).close();
    verify(storageAdmin).close();
  }

  @Test
  void close_withoutPriorScan_shouldNotThrowException() {
    // Arrange
    CosmosResumableScanner scanner = createScanner();

    // Act & Assert
    assertThatCode(scanner::close).doesNotThrowAnyException();
  }
}
