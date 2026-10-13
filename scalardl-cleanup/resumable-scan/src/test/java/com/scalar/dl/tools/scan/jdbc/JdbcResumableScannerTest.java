package com.scalar.dl.tools.scan.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.Scan.Ordering;
import com.scalar.db.api.Scanner;
import com.scalar.db.api.Selection.Conjunction;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.Column;
import com.scalar.db.io.DataType;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.TextColumn;
import com.scalar.dl.tools.common.ScalarDlCleanupException;
import com.scalar.dl.tools.scan.RecordHandler;
import com.scalar.dl.tools.scan.ScanResult;
import com.scalar.dl.tools.scan.checkpoint.CheckpointManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JdbcResumableScannerTest {

  private static final String NAMESPACE = "ns";
  private static final String TABLE = "tbl";
  private static final String QUALIFIED_TABLE = "ns.tbl";
  private static final int PAGE_SIZE = 3;

  private DistributedStorage storage;
  private DistributedStorageAdmin storageAdmin;
  private CheckpointManager checkpointManager;
  private JdbcResumableScanner scanner;

  private static TableMetadata compositeKeyMetadata() {
    return TableMetadata.newBuilder()
        .addColumn("id", DataType.TEXT)
        .addColumn("age", DataType.INT)
        .addColumn("value", DataType.TEXT)
        .addPartitionKey("id")
        .addClusteringKey("age")
        .build();
  }

  /** Builds a mock Result whose primary-key columns are id and age. */
  private static Result record(String id, int age) {
    Result result = mock(Result.class);
    Map<String, Column<?>> columns = new LinkedHashMap<>();
    columns.put("id", TextColumn.of("id", id));
    columns.put("age", IntColumn.of("age", age));
    when(result.getColumns()).thenReturn(columns);
    return result;
  }

  private static List<Result> page(String idPrefix, int size) {
    List<Result> records = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      records.add(record(idPrefix + "-" + i, i));
    }
    return records;
  }

  /** Makes storage.scan return the given pages in order, one per call. */
  private void givenPages(List<List<Result>> pages) throws Exception {
    AtomicInteger index = new AtomicInteger();
    when(storage.scan(any(Scan.class)))
        .thenAnswer(
            invocation -> {
              int i = index.getAndIncrement();
              List<Result> records = i < pages.size() ? pages.get(i) : Collections.emptyList();
              Scanner pageScanner = mock(Scanner.class);
              when(pageScanner.all()).thenReturn(records);
              return pageScanner;
            });
  }

  @BeforeEach
  void setUp() throws Exception {
    storage = mock(DistributedStorage.class);
    storageAdmin = mock(DistributedStorageAdmin.class);
    checkpointManager = mock(CheckpointManager.class);
    when(storageAdmin.getTableMetadata(NAMESPACE, TABLE)).thenReturn(compositeKeyMetadata());
    when(checkpointManager.loadToken(anyString(), anyString())).thenReturn(null);
    scanner = new JdbcResumableScanner(storage, storageAdmin, checkpointManager, PAGE_SIZE, 4);
  }

  @Test
  void scan_emptyTableGiven_shouldCompleteWithoutCheckpointing() throws Exception {
    // Arrange
    givenPages(Collections.singletonList(Collections.emptyList()));

    // Act
    ScanResult result = scanner.scan(NAMESPACE, TABLE, r -> {});

    // Assert
    assertThat(result.getTotalScanned()).isZero();
    verify(checkpointManager, never()).persistToken(anyString(), anyString(), anyString());
    verify(checkpointManager).clearCheckpointFor(QUALIFIED_TABLE);
  }

  @Test
  void scan_partialLastPageGiven_shouldDeliverEveryRecordAndStop() throws Exception {
    // Arrange
    givenPages(java.util.Arrays.asList(page("a", PAGE_SIZE), page("b", 1)));
    List<Result> handled = new CopyOnWriteArrayList<>();

    // Act
    ScanResult result = scanner.scan(NAMESPACE, TABLE, handled::add);

    // Assert
    assertThat(result.getTotalScanned()).isEqualTo(PAGE_SIZE + 1);
    assertThat(handled).hasSize(PAGE_SIZE + 1);
    // Two full pages were checkpointed; the short page ends the scan.
    verify(checkpointManager, org.mockito.Mockito.times(2))
        .persistToken(eq(QUALIFIED_TABLE), anyString(), anyString());
  }

  @Test
  void scan_tableSizeExactlyMultipleOfPageSize_shouldStopOnTheEmptyPage() throws Exception {
    // Arrange
    // A full page is followed by an empty one; the empty page must not overwrite the checkpoint.
    givenPages(java.util.Arrays.asList(page("a", PAGE_SIZE), Collections.emptyList()));

    // Act
    ScanResult result = scanner.scan(NAMESPACE, TABLE, r -> {});

    // Assert
    assertThat(result.getTotalScanned()).isEqualTo(PAGE_SIZE);
    verify(checkpointManager, org.mockito.Mockito.times(1))
        .persistToken(eq(QUALIFIED_TABLE), anyString(), anyString());
  }

  @Test
  void scan_noCheckpointGiven_shouldIssueTheFirstPageWithoutAResumeCondition() throws Exception {
    // Arrange
    givenPages(Collections.singletonList(Collections.emptyList()));

    // Act
    scanner.scan(NAMESPACE, TABLE, r -> {});

    // Assert
    ArgumentCaptor<Scan> captor = ArgumentCaptor.forClass(Scan.class);
    verify(storage).scan(captor.capture());
    Scan scan = captor.getValue();
    assertThat(scan.getConjunctions()).isEmpty();
    assertThat(scan.getOrderings()).containsExactly(Ordering.asc("id"), Ordering.asc("age"));
    assertThat(scan.getLimit()).isEqualTo(PAGE_SIZE);
  }

  @Test
  void scan_checkpointGiven_shouldResumeWithTheKeysetCondition() throws Exception {
    // Arrange
    String token =
        KeysetCheckpointCodec.encode(
            java.util.Arrays.asList(TextColumn.of("id", "a-2"), IntColumn.of("age", 5)));
    when(checkpointManager.loadToken(QUALIFIED_TABLE, "keyset")).thenReturn(token);
    givenPages(Collections.singletonList(Collections.emptyList()));

    // Act
    scanner.scan(NAMESPACE, TABLE, r -> {});

    // Assert
    ArgumentCaptor<Scan> captor = ArgumentCaptor.forClass(Scan.class);
    verify(storage).scan(captor.capture());
    // One disjunct per key column, each carrying the leading bound on "id".
    assertThat(captor.getValue().getConjunctions()).hasSize(2);
    for (Conjunction conjunction : captor.getValue().getConjunctions()) {
      assertThat(conjunction.getConditions())
          .anySatisfy(c -> assertThat(c.getColumn().getName()).isEqualTo("id"));
    }
  }

  @Test
  void scan_handlerThrowsGiven_shouldPropagateAndNotCheckpointThePage() throws Exception {
    // Arrange
    givenPages(Collections.singletonList(page("a", PAGE_SIZE)));
    RecordHandler handler =
        r -> {
          throw new IllegalStateException("handler failed");
        };

    // Act & Assert
    assertThatThrownBy(() -> scanner.scan(NAMESPACE, TABLE, handler))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("handler failed");
    verify(checkpointManager, never()).persistToken(anyString(), anyString(), anyString());
    // The failed scan must not clear the checkpoint either; the next run resumes from it.
    verify(checkpointManager, never()).clearCheckpointFor(anyString());
  }

  @Test
  void scan_handlerThrowsOnOneRecordGiven_shouldStillDeliverNoFurtherPages() throws Exception {
    // Arrange
    givenPages(java.util.Arrays.asList(page("a", PAGE_SIZE), page("b", PAGE_SIZE)));
    AtomicInteger delivered = new AtomicInteger();
    RecordHandler handler =
        r -> {
          delivered.incrementAndGet();
          throw new IllegalStateException("boom");
        };

    // Act & Assert
    assertThatThrownBy(() -> scanner.scan(NAMESPACE, TABLE, handler))
        .isInstanceOf(IllegalStateException.class);
    assertThat(delivered.get()).isLessThanOrEqualTo(PAGE_SIZE);
  }

  @Test
  void scan_corruptedCheckpointGiven_shouldThrowScalarDlCleanupException() throws Exception {
    // Arrange
    when(checkpointManager.loadToken(QUALIFIED_TABLE, "keyset")).thenReturn("{not json");

    // Act & Assert
    assertThatThrownBy(() -> scanner.scan(NAMESPACE, TABLE, r -> {}))
        .isInstanceOf(ScalarDlCleanupException.class)
        .hasMessageContaining(QUALIFIED_TABLE);
  }

  @Test
  void scan_checkpointForRenamedColumnGiven_shouldThrowScalarDlCleanupException() throws Exception {
    // Arrange
    String token =
        KeysetCheckpointCodec.encode(
            java.util.Arrays.asList(TextColumn.of("renamed", "a"), IntColumn.of("age", 1)));
    when(checkpointManager.loadToken(QUALIFIED_TABLE, "keyset")).thenReturn(token);

    // Act & Assert
    assertThatThrownBy(() -> scanner.scan(NAMESPACE, TABLE, r -> {}))
        .isInstanceOf(ScalarDlCleanupException.class);
  }

  @Test
  void scan_missingTableMetadataGiven_shouldThrowIllegalStateException() throws Exception {
    // Arrange
    when(storageAdmin.getTableMetadata(NAMESPACE, TABLE)).thenReturn(null);

    // Act & Assert
    assertThatThrownBy(() -> scanner.scan(NAMESPACE, TABLE, r -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(QUALIFIED_TABLE);
  }

  @Test
  void validateScannable_supportedKeyTypesGiven_shouldNotThrow() {
    // Act & Assert
    JdbcResumableScanner.validateScannable(QUALIFIED_TABLE, compositeKeyMetadata());
  }

  @Test
  void scan_descClusteringOrderGiven_shouldOrderThatColumnDescending() throws Exception {
    // Arrange
    TableMetadata metadata =
        TableMetadata.newBuilder()
            .addColumn("id", DataType.TEXT)
            .addColumn("age", DataType.INT)
            .addPartitionKey("id")
            .addClusteringKey("age", Scan.Ordering.Order.DESC)
            .build();
    when(storageAdmin.getTableMetadata(NAMESPACE, TABLE)).thenReturn(metadata);
    givenPages(Collections.singletonList(Collections.emptyList()));

    // Act
    scanner.scan(NAMESPACE, TABLE, r -> {});

    // Assert
    ArgumentCaptor<Scan> captor = ArgumentCaptor.forClass(Scan.class);
    verify(storage).scan(captor.capture());
    assertThat(captor.getValue().getOrderings())
        .containsExactly(Ordering.asc("id"), Ordering.desc("age"));
  }

  @Test
  void scan_multipleRecordsGiven_shouldDeliverEveryRecordExactlyOnce() throws Exception {
    // Arrange
    // The handler pool runs records concurrently, so every record must still be delivered once.
    givenPages(java.util.Arrays.asList(page("a", PAGE_SIZE), page("b", PAGE_SIZE), page("c", 2)));
    Map<Result, Integer> counts = Collections.synchronizedMap(new HashMap<>());

    // Act
    ScanResult result = scanner.scan(NAMESPACE, TABLE, r -> counts.merge(r, 1, Integer::sum));

    // Assert
    assertThat(result.getTotalScanned()).isEqualTo(PAGE_SIZE * 2 + 2);
    assertThat(counts).hasSize(PAGE_SIZE * 2 + 2);
    assertThat(counts.values()).allMatch(count -> count == 1);
  }
}
