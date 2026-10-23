package com.scalar.dl.tools.scan.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.scalar.db.api.ConditionBuilder;
import com.scalar.db.api.ConditionalExpression;
import com.scalar.db.api.DatabaseOperationAttributes;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.ScanAll;
import com.scalar.db.api.Scanner;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.exception.storage.ExecutionException;
import com.scalar.db.io.Column;
import com.scalar.db.io.DataType;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.TextColumn;
import com.scalar.dl.tools.scan.RecordHandler;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

class JdbcResumableScannerTest {

  private static final String NAMESPACE = "ns";
  private static final String TABLE = "table";
  private static final String QUALIFIED_TABLE = NAMESPACE + "." + TABLE;

  /** pk ASC (partition key), ck1 DESC, ck2 ASC (clustering keys). */
  private static final TableMetadata COMPOSITE_KEY_METADATA =
      TableMetadata.newBuilder()
          .addColumn("pk", DataType.TEXT)
          .addColumn("ck1", DataType.INT)
          .addColumn("ck2", DataType.TEXT)
          .addColumn("val", DataType.TEXT)
          .addPartitionKey("pk")
          .addClusteringKey("ck1", Scan.Ordering.Order.DESC)
          .addClusteringKey("ck2", Scan.Ordering.Order.ASC)
          .build();

  private static final TableMetadata SINGLE_KEY_METADATA =
      TableMetadata.newBuilder()
          .addColumn("id", DataType.TEXT)
          .addColumn("val", DataType.TEXT)
          .addPartitionKey("id")
          .build();

  /** pk1 ASC and pk2 ASC (partition keys), ck DESC (clustering key). */
  private static final TableMetadata MULTI_COLUMN_PARTITION_KEY_METADATA =
      TableMetadata.newBuilder()
          .addColumn("pk1", DataType.TEXT)
          .addColumn("pk2", DataType.INT)
          .addColumn("ck", DataType.INT)
          .addColumn("val", DataType.TEXT)
          .addPartitionKey("pk1")
          .addPartitionKey("pk2")
          .addClusteringKey("ck", Scan.Ordering.Order.DESC)
          .build();

  /** pk ASC (partition key), ck1 DESC and ck2 DESC (clustering keys). */
  private static final TableMetadata DESC_CLUSTERING_KEYS_METADATA =
      TableMetadata.newBuilder()
          .addColumn("pk", DataType.TEXT)
          .addColumn("ck1", DataType.INT)
          .addColumn("ck2", DataType.TEXT)
          .addColumn("val", DataType.TEXT)
          .addPartitionKey("pk")
          .addClusteringKey("ck1", Scan.Ordering.Order.DESC)
          .addClusteringKey("ck2", Scan.Ordering.Order.DESC)
          .build();

  private static final List<Scan.Ordering> COMPOSITE_KEY_ORDERINGS =
      Arrays.asList(Scan.Ordering.asc("pk"), Scan.Ordering.desc("ck1"), Scan.Ordering.asc("ck2"));

  @TempDir Path tempDir;

  private DistributedStorage storage;
  private DistributedStorageAdmin storageAdmin;
  private CheckpointManager checkpointManager;
  private Path checkpointDir;
  private final List<Scan> issuedScans = new ArrayList<>();
  private final List<Scanner> issuedScanners = new ArrayList<>();
  private final Deque<List<Result>> pages = new ArrayDeque<>();

  @BeforeEach
  void setUp() throws Exception {
    storage = mock(DistributedStorage.class);
    storageAdmin = mock(DistributedStorageAdmin.class);
    checkpointDir = tempDir.resolve("checkpoint");
    checkpointManager = new CheckpointManager(checkpointDir);

    when(storage.scan(any()))
        .thenAnswer(
            invocation -> {
              issuedScans.add(invocation.getArgument(0));
              Scanner scanner = mock(Scanner.class);
              issuedScanners.add(scanner);
              List<Result> page = pages.poll();
              when(scanner.all()).thenReturn(page == null ? Collections.emptyList() : page);
              return scanner;
            });
  }

  private JdbcResumableScanner createScanner(TableMetadata metadata, int pageSize)
      throws Exception {
    when(storageAdmin.getTableMetadata(NAMESPACE, TABLE)).thenReturn(metadata);
    return new JdbcResumableScanner(storage, storageAdmin, checkpointManager, pageSize);
  }

  @SafeVarargs
  private final void enqueuePages(List<Result>... resultPages) {
    pages.addAll(Arrays.asList(resultPages));
  }

  private static Result compositeRecord(String pk, int ck1, String ck2) {
    Result result = mock(Result.class);
    when(result.getColumns())
        .thenReturn(
            ImmutableMap.of(
                "pk", TextColumn.of("pk", pk),
                "ck1", IntColumn.of("ck1", ck1),
                "ck2", TextColumn.of("ck2", ck2),
                "val", TextColumn.of("val", "v")));
    return result;
  }

  private static Result singleKeyRecord(String id) {
    Result result = mock(Result.class);
    when(result.getColumns())
        .thenReturn(
            ImmutableMap.of("id", TextColumn.of("id", id), "val", TextColumn.of("val", "v")));
    return result;
  }

  private static List<Column<?>> compositeKey(String pk, int ck1, String ck2) {
    return Arrays.asList(
        TextColumn.of("pk", pk), IntColumn.of("ck1", ck1), TextColumn.of("ck2", ck2));
  }

  private static Result record(Column<?>... columns) {
    ImmutableMap.Builder<String, Column<?>> builder = ImmutableMap.builder();
    for (Column<?> column : columns) {
      builder.put(column.getName(), column);
    }
    Result result = mock(Result.class);
    when(result.getColumns()).thenReturn(builder.build());
    return result;
  }

  /** Asserts that the scan reads one page of the table in the given order across partitions. */
  private static void assertPageScan(Scan scan, int pageSize, List<Scan.Ordering> orderings) {
    assertThat(scan).isInstanceOf(ScanAll.class);
    assertThat(scan.forNamespace()).hasValue(NAMESPACE);
    assertThat(scan.forTable()).hasValue(TABLE);
    assertThat(scan.getOrderings()).isEqualTo(orderings);
    assertThat(scan.getLimit()).isEqualTo(pageSize);
    assertThat(DatabaseOperationAttributes.isCrossPartitionScanEnabled((ScanAll) scan, false))
        .isTrue();
    assertThat(
            DatabaseOperationAttributes.isCrossPartitionScanFilteringEnabled((ScanAll) scan, false))
        .isTrue();
    assertThat(
            DatabaseOperationAttributes.isCrossPartitionScanOrderingEnabled((ScanAll) scan, false))
        .isTrue();
  }

  private List<Column<?>> persistedLastKey(TableMetadata metadata) throws Exception {
    String json = new String(Files.readAllBytes(lastKeyFile()), StandardCharsets.UTF_8);
    return PrimaryKeySerializer.fromJson(json, metadata);
  }

  private static Set<ConditionalExpression> conditionsOf(Scan scan) {
    if (scan.getConjunctions().isEmpty()) {
      return Collections.emptySet();
    }
    return Iterables.getOnlyElement(scan.getConjunctions()).getConditions();
  }

  private Path lastKeyFile() {
    return checkpointDir.resolve(QUALIFIED_TABLE).resolve("last_key.json");
  }

  private void writeLastKey(List<Column<?>> key) throws Exception {
    Files.createDirectories(checkpointDir.resolve(QUALIFIED_TABLE));
    Files.write(lastKeyFile(), PrimaryKeySerializer.toJson(key).getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void scan_whenNoCheckpointExists_shouldIssueCrossPartitionScanOrderedByPrimaryKeyWithLimit()
      throws Exception {
    // Arrange
    enqueuePages(Collections.emptyList());

    // Act
    try (JdbcResumableScanner scanner = createScanner(COMPOSITE_KEY_METADATA, 100)) {
      scanner.scan(NAMESPACE, TABLE, r -> {});
    }

    // Assert
    assertThat(issuedScans).hasSize(1);
    assertPageScan(issuedScans.get(0), 100, COMPOSITE_KEY_ORDERINGS);
    assertThat(issuedScans.get(0).getConjunctions()).isEmpty();
  }

  @Test
  void scan_emptyTableGiven_shouldReturnZero() throws Exception {
    // Arrange
    enqueuePages(Collections.emptyList());
    RecordHandler handler = mock(RecordHandler.class);

    // Act
    ScanResult result;
    try (JdbcResumableScanner scanner = createScanner(COMPOSITE_KEY_METADATA, 100)) {
      result = scanner.scan(NAMESPACE, TABLE, handler);
    }

    // Assert
    assertThat(result.getTotalScanned()).isEqualTo(0);
    verify(handler, never()).handle(any());
  }

  @Test
  void scan_multiplePagesGiven_shouldSearchFromNarrowestLevelAndWidenOnEmptyPage()
      throws Exception {
    // Arrange
    Result r1 = compositeRecord("p1", 9, "a");
    Result r2 = compositeRecord("p1", 5, "a");
    Result r3 = compositeRecord("p1", 5, "b");
    Result r4 = compositeRecord("p1", 5, "c");
    Result r5 = compositeRecord("p2", 7, "a");
    Result r6 = compositeRecord("p2", 6, "a");
    enqueuePages(
        Arrays.asList(r1, r2), // no condition
        Arrays.asList(r3, r4), // pk=p1 AND ck1=5 AND ck2>a
        Collections.emptyList(), // pk=p1 AND ck1=5 AND ck2>c
        Collections.emptyList(), // pk=p1 AND ck1<5
        Arrays.asList(r5, r6), // pk>p1
        Collections.emptyList(), // pk=p2 AND ck1=6 AND ck2>a
        Collections.emptyList(), // pk=p2 AND ck1<6
        Collections.emptyList()); // pk>p2
    List<Result> handled = new ArrayList<>();

    // Act
    ScanResult result;
    try (JdbcResumableScanner scanner = createScanner(COMPOSITE_KEY_METADATA, 2)) {
      result = scanner.scan(NAMESPACE, TABLE, handled::add);
    }

    // Assert
    assertThat(result.getTotalScanned()).isEqualTo(6);
    assertThat(handled).containsExactly(r1, r2, r3, r4, r5, r6);
    assertThat(issuedScans).hasSize(8);
    assertThat(conditionsOf(issuedScans.get(0))).isEmpty();
    assertThat(conditionsOf(issuedScans.get(1)))
        .isEqualTo(
            ImmutableSet.of(
                ConditionBuilder.column("pk").isEqualToText("p1"),
                ConditionBuilder.column("ck1").isEqualToInt(5),
                ConditionBuilder.column("ck2").isGreaterThanText("a")));
    // A full page restarts from the narrowest level after the new last key
    assertThat(conditionsOf(issuedScans.get(2)))
        .isEqualTo(
            ImmutableSet.of(
                ConditionBuilder.column("pk").isEqualToText("p1"),
                ConditionBuilder.column("ck1").isEqualToInt(5),
                ConditionBuilder.column("ck2").isGreaterThanText("c")));
    // An empty page widens the range. ck1 is a DESC clustering key, so "after" means "less than"
    assertThat(conditionsOf(issuedScans.get(3)))
        .isEqualTo(
            ImmutableSet.of(
                ConditionBuilder.column("pk").isEqualToText("p1"),
                ConditionBuilder.column("ck1").isLessThanInt(5)));
    assertThat(conditionsOf(issuedScans.get(4)))
        .isEqualTo(ImmutableSet.of(ConditionBuilder.column("pk").isGreaterThanText("p1")));
    // An empty page at level 0 completes the scan
    assertThat(conditionsOf(issuedScans.get(7)))
        .isEqualTo(ImmutableSet.of(ConditionBuilder.column("pk").isGreaterThanText("p2")));
    // Every level reads one page in the scan order
    assertThat(issuedScans).allSatisfy(s -> assertPageScan(s, 2, COMPOSITE_KEY_ORDERINGS));
  }

  @Test
  void scan_whenFullPageReturnedAtWiderLevel_shouldRestartFromNarrowestLevel() throws Exception {
    // Arrange
    enqueuePages(
        Collections.singletonList(compositeRecord("p1", 9, "a")), // no condition
        Collections.emptyList(), // pk=p1 AND ck1=9 AND ck2>a
        Collections.singletonList(compositeRecord("p1", 5, "a")), // pk=p1 AND ck1<9
        Collections.emptyList(), // pk=p1 AND ck1=5 AND ck2>a
        Collections.emptyList(), // pk=p1 AND ck1<5
        Collections.emptyList()); // pk>p1

    // Act
    ScanResult result;
    try (JdbcResumableScanner scanner = createScanner(COMPOSITE_KEY_METADATA, 1)) {
      result = scanner.scan(NAMESPACE, TABLE, r -> {});
    }

    // Assert
    assertThat(result.getTotalScanned()).isEqualTo(2);
    assertThat(issuedScans).hasSize(6);
    assertThat(conditionsOf(issuedScans.get(2)))
        .isEqualTo(
            ImmutableSet.of(
                ConditionBuilder.column("pk").isEqualToText("p1"),
                ConditionBuilder.column("ck1").isLessThanInt(9)));
    assertThat(conditionsOf(issuedScans.get(3)))
        .isEqualTo(
            ImmutableSet.of(
                ConditionBuilder.column("pk").isEqualToText("p1"),
                ConditionBuilder.column("ck1").isEqualToInt(5),
                ConditionBuilder.column("ck2").isGreaterThanText("a")));
    assertThat(conditionsOf(issuedScans.get(4)))
        .isEqualTo(
            ImmutableSet.of(
                ConditionBuilder.column("pk").isEqualToText("p1"),
                ConditionBuilder.column("ck1").isLessThanInt(5)));
    assertThat(conditionsOf(issuedScans.get(5)))
        .isEqualTo(ImmutableSet.of(ConditionBuilder.column("pk").isGreaterThanText("p1")));
  }

  @Test
  void scan_whenPageWithFewerRecordsThanPageSizeReturned_shouldMoveToNextWiderLevelDirectly()
      throws Exception {
    // Arrange
    writeLastKey(compositeKey("p1", 5, "b"));
    Result r1 = compositeRecord("p1", 5, "c");
    Result r2 = compositeRecord("p1", 3, "a");
    Result r3 = compositeRecord("p2", 1, "a");
    Result r4 = compositeRecord("p2", 1, "b");
    Result r5 = compositeRecord("p2", 0, "a");
    enqueuePages(
        Collections.singletonList(r1), // pk=p1 AND ck1=5 AND ck2>b
        Collections.singletonList(r2), // pk=p1 AND ck1<5
        Arrays.asList(r3, r4), // pk>p1
        Collections.emptyList(), // pk=p2 AND ck1=1 AND ck2>b
        Collections.singletonList(r5), // pk=p2 AND ck1<1
        Collections.emptyList()); // pk>p2
    List<Result> handled = new ArrayList<>();

    // Act
    ScanResult result;
    try (JdbcResumableScanner scanner = createScanner(COMPOSITE_KEY_METADATA, 2)) {
      result = scanner.scan(NAMESPACE, TABLE, handled::add);
    }

    // Assert
    assertThat(result.getTotalScanned()).isEqualTo(5);
    assertThat(handled).containsExactly(r1, r2, r3, r4, r5);
    assertThat(issuedScans).hasSize(6);
    // A page with fewer records exhausts the level, so the next wider level follows without
    // reading the narrower levels after the new last key
    assertThat(conditionsOf(issuedScans.get(1)))
        .isEqualTo(
            ImmutableSet.of(
                ConditionBuilder.column("pk").isEqualToText("p1"),
                ConditionBuilder.column("ck1").isLessThanInt(5)));
    assertThat(conditionsOf(issuedScans.get(2)))
        .isEqualTo(ImmutableSet.of(ConditionBuilder.column("pk").isGreaterThanText("p1")));
    // A full page restarts from the narrowest level
    assertThat(conditionsOf(issuedScans.get(3)))
        .isEqualTo(
            ImmutableSet.of(
                ConditionBuilder.column("pk").isEqualToText("p2"),
                ConditionBuilder.column("ck1").isEqualToInt(1),
                ConditionBuilder.column("ck2").isGreaterThanText("b")));
    assertThat(conditionsOf(issuedScans.get(4)))
        .isEqualTo(
            ImmutableSet.of(
                ConditionBuilder.column("pk").isEqualToText("p2"),
                ConditionBuilder.column("ck1").isLessThanInt(1)));
    assertThat(conditionsOf(issuedScans.get(5)))
        .isEqualTo(ImmutableSet.of(ConditionBuilder.column("pk").isGreaterThanText("p2")));
    assertThat(issuedScans).allSatisfy(s -> assertPageScan(s, 2, COMPOSITE_KEY_ORDERINGS));
  }

  @Test
  void scan_whenPageWithFewerRecordsThanPageSizeReturnedAtWidestLevel_shouldComplete()
      throws Exception {
    // Arrange
    writeLastKey(compositeKey("p1", 5, "b"));
    Result r = compositeRecord("p2", 7, "a");
    enqueuePages(
        Collections.emptyList(), // pk=p1 AND ck1=5 AND ck2>b
        Collections.emptyList(), // pk=p1 AND ck1<5
        Collections.singletonList(r)); // pk>p1

    // Act
    ScanResult result;
    try (JdbcResumableScanner scanner = createScanner(COMPOSITE_KEY_METADATA, 2)) {
      result = scanner.scan(NAMESPACE, TABLE, ignored -> {});
    }

    // Assert
    assertThat(result.getTotalScanned()).isEqualTo(1);
    assertThat(issuedScans).hasSize(3);
    assertThat(checkpointDir.resolve(QUALIFIED_TABLE)).doesNotExist();
  }

  @Test
  void scan_whenFirstPageHasFewerRecordsThanPageSize_shouldCompleteWithoutFurtherQuery()
      throws Exception {
    // Arrange
    Result r = compositeRecord("p1", 9, "a");
    enqueuePages(Collections.singletonList(r));
    List<Result> handled = new ArrayList<>();

    // Act
    ScanResult result;
    try (JdbcResumableScanner scanner = createScanner(COMPOSITE_KEY_METADATA, 2)) {
      result = scanner.scan(NAMESPACE, TABLE, handled::add);
    }

    // Assert
    assertThat(result.getTotalScanned()).isEqualTo(1);
    assertThat(handled).containsExactly(r);
    assertThat(issuedScans).hasSize(1);
    assertThat(conditionsOf(issuedScans.get(0))).isEmpty();
  }

  @Test
  void scan_singleColumnPrimaryKeyGiven_shouldUseOnlyGreaterThanCondition() throws Exception {
    // Arrange
    enqueuePages(
        Arrays.asList(singleKeyRecord("a"), singleKeyRecord("b")),
        Arrays.asList(singleKeyRecord("c"), singleKeyRecord("d")),
        Collections.emptyList());

    // Act
    ScanResult result;
    try (JdbcResumableScanner scanner = createScanner(SINGLE_KEY_METADATA, 2)) {
      result = scanner.scan(NAMESPACE, TABLE, r -> {});
    }

    // Assert
    assertThat(result.getTotalScanned()).isEqualTo(4);
    assertThat(issuedScans).hasSize(3);
    assertThat(issuedScans)
        .allSatisfy(s -> assertPageScan(s, 2, Collections.singletonList(Scan.Ordering.asc("id"))));
    assertThat(conditionsOf(issuedScans.get(1)))
        .isEqualTo(ImmutableSet.of(ConditionBuilder.column("id").isGreaterThanText("b")));
    assertThat(conditionsOf(issuedScans.get(2)))
        .isEqualTo(ImmutableSet.of(ConditionBuilder.column("id").isGreaterThanText("d")));
  }

  @Test
  void scan_checkpointGiven_shouldResumeAfterCheckpointedKeyFromNarrowestLevel() throws Exception {
    // Arrange
    writeLastKey(compositeKey("p1", 5, "b"));
    Result r = compositeRecord("p1", 5, "c");
    enqueuePages(
        Collections.singletonList(r),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList());
    List<Result> handled = new ArrayList<>();

    // Act
    ScanResult result;
    try (JdbcResumableScanner scanner = createScanner(COMPOSITE_KEY_METADATA, 1)) {
      result = scanner.scan(NAMESPACE, TABLE, handled::add);
    }

    // Assert
    assertThat(result.getTotalScanned()).isEqualTo(1);
    assertThat(handled).containsExactly(r);
    assertThat(issuedScans).hasSize(4);
    assertThat(conditionsOf(issuedScans.get(0)))
        .isEqualTo(
            ImmutableSet.of(
                ConditionBuilder.column("pk").isEqualToText("p1"),
                ConditionBuilder.column("ck1").isEqualToInt(5),
                ConditionBuilder.column("ck2").isGreaterThanText("b")));
    // The last key advanced to (p1, 5, c)
    assertThat(conditionsOf(issuedScans.get(1)))
        .isEqualTo(
            ImmutableSet.of(
                ConditionBuilder.column("pk").isEqualToText("p1"),
                ConditionBuilder.column("ck1").isEqualToInt(5),
                ConditionBuilder.column("ck2").isGreaterThanText("c")));
    assertThat(conditionsOf(issuedScans.get(2)))
        .isEqualTo(
            ImmutableSet.of(
                ConditionBuilder.column("pk").isEqualToText("p1"),
                ConditionBuilder.column("ck1").isLessThanInt(5)));
    assertThat(conditionsOf(issuedScans.get(3)))
        .isEqualTo(ImmutableSet.of(ConditionBuilder.column("pk").isGreaterThanText("p1")));
    assertThat(issuedScans).allSatisfy(s -> assertPageScan(s, 1, COMPOSITE_KEY_ORDERINGS));
  }

  @Test
  void scan_multiColumnPartitionKeyGiven_shouldBuildLevelsOverAllPrimaryKeyColumns()
      throws Exception {
    // Arrange
    writeLastKey(
        Arrays.asList(TextColumn.of("pk1", "a"), IntColumn.of("pk2", 1), IntColumn.of("ck", 5)));
    List<Scan.Ordering> orderings =
        Arrays.asList(Scan.Ordering.asc("pk1"), Scan.Ordering.asc("pk2"), Scan.Ordering.desc("ck"));

    // Act
    try (JdbcResumableScanner scanner = createScanner(MULTI_COLUMN_PARTITION_KEY_METADATA, 100)) {
      scanner.scan(NAMESPACE, TABLE, r -> {});
    }

    // Assert
    assertThat(issuedScans).hasSize(3);
    // ck is a DESC clustering key, so "after" means "less than"
    assertThat(conditionsOf(issuedScans.get(0)))
        .isEqualTo(
            ImmutableSet.of(
                ConditionBuilder.column("pk1").isEqualToText("a"),
                ConditionBuilder.column("pk2").isEqualToInt(1),
                ConditionBuilder.column("ck").isLessThanInt(5)));
    // Partition key columns are always in ascending order
    assertThat(conditionsOf(issuedScans.get(1)))
        .isEqualTo(
            ImmutableSet.of(
                ConditionBuilder.column("pk1").isEqualToText("a"),
                ConditionBuilder.column("pk2").isGreaterThanInt(1)));
    assertThat(conditionsOf(issuedScans.get(2)))
        .isEqualTo(ImmutableSet.of(ConditionBuilder.column("pk1").isGreaterThanText("a")));
    assertThat(issuedScans).allSatisfy(s -> assertPageScan(s, 100, orderings));
  }

  @Test
  void scan_multiColumnPartitionKeyGiven_shouldCheckpointAllPrimaryKeyColumns() throws Exception {
    // Arrange
    Result r1 =
        record(
            TextColumn.of("pk1", "a"),
            IntColumn.of("pk2", 1),
            IntColumn.of("ck", 5),
            TextColumn.of("val", "v"));
    Result r2 =
        record(
            TextColumn.of("pk1", "a"),
            IntColumn.of("pk2", 1),
            IntColumn.of("ck", 4),
            TextColumn.of("val", "v"));
    enqueuePages(Collections.singletonList(r1), Collections.singletonList(r2));
    Exception failure = new Exception("handler failure");
    RecordHandler handler =
        r -> {
          if (r.getColumns().get("ck").getIntValue() == 4) {
            throw failure;
          }
        };

    // Act & Assert
    try (JdbcResumableScanner scanner = createScanner(MULTI_COLUMN_PARTITION_KEY_METADATA, 1)) {
      assertThatThrownBy(() -> scanner.scan(NAMESPACE, TABLE, handler)).isSameAs(failure);
    }
    assertThat(persistedLastKey(MULTI_COLUMN_PARTITION_KEY_METADATA))
        .isEqualTo(
            Arrays.asList(
                TextColumn.of("pk1", "a"), IntColumn.of("pk2", 1), IntColumn.of("ck", 5)));
  }

  @Test
  void scan_allDescendingClusteringKeysGiven_shouldUseLessThanForEveryClusteringKey()
      throws Exception {
    // Arrange
    writeLastKey(compositeKey("p1", 5, "b"));
    List<Scan.Ordering> orderings =
        Arrays.asList(
            Scan.Ordering.asc("pk"), Scan.Ordering.desc("ck1"), Scan.Ordering.desc("ck2"));

    // Act
    try (JdbcResumableScanner scanner = createScanner(DESC_CLUSTERING_KEYS_METADATA, 100)) {
      scanner.scan(NAMESPACE, TABLE, r -> {});
    }

    // Assert
    assertThat(issuedScans).hasSize(3);
    // The boundary column of the narrowest level is also DESC
    assertThat(conditionsOf(issuedScans.get(0)))
        .isEqualTo(
            ImmutableSet.of(
                ConditionBuilder.column("pk").isEqualToText("p1"),
                ConditionBuilder.column("ck1").isEqualToInt(5),
                ConditionBuilder.column("ck2").isLessThanText("b")));
    assertThat(conditionsOf(issuedScans.get(1)))
        .isEqualTo(
            ImmutableSet.of(
                ConditionBuilder.column("pk").isEqualToText("p1"),
                ConditionBuilder.column("ck1").isLessThanInt(5)));
    assertThat(conditionsOf(issuedScans.get(2)))
        .isEqualTo(ImmutableSet.of(ConditionBuilder.column("pk").isGreaterThanText("p1")));
    assertThat(issuedScans).allSatisfy(s -> assertPageScan(s, 100, orderings));
  }

  @Test
  void scan_whenCompleted_shouldClearCheckpoint() throws Exception {
    // Arrange
    writeLastKey(compositeKey("p1", 5, "b"));
    enqueuePages(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

    // Act
    try (JdbcResumableScanner scanner = createScanner(COMPOSITE_KEY_METADATA, 100)) {
      scanner.scan(NAMESPACE, TABLE, r -> {});
    }

    // Assert
    assertThat(checkpointDir.resolve(QUALIFIED_TABLE)).doesNotExist();
    assertThat(checkpointDir).isDirectory();
  }

  @Test
  void scan_whenHandlerThrows_shouldPropagateAndKeepCheckpointOfLastCompletedPage()
      throws Exception {
    // Arrange
    enqueuePages(
        Arrays.asList(compositeRecord("p1", 9, "a"), compositeRecord("p1", 5, "a")),
        Arrays.asList(compositeRecord("p1", 5, "b"), compositeRecord("p1", 5, "c")));
    Exception failure = new Exception("handler failure");
    RecordHandler handler =
        r -> {
          if ("c".equals(r.getColumns().get("ck2").getTextValue())) {
            throw failure;
          }
        };

    // Act & Assert
    try (JdbcResumableScanner scanner = createScanner(COMPOSITE_KEY_METADATA, 2)) {
      assertThatThrownBy(() -> scanner.scan(NAMESPACE, TABLE, handler)).isSameAs(failure);
    }
    String persisted = new String(Files.readAllBytes(lastKeyFile()), StandardCharsets.UTF_8);
    assertThat(PrimaryKeySerializer.fromJson(persisted, COMPOSITE_KEY_METADATA))
        .isEqualTo(compositeKey("p1", 5, "a"));
  }

  @Test
  void scan_shouldCloseScannerBeforeHandlingRecords() throws Exception {
    // Arrange
    Result r = compositeRecord("p1", 9, "a");
    enqueuePages(Collections.singletonList(r));
    RecordHandler handler = mock(RecordHandler.class);

    // Act
    try (JdbcResumableScanner scanner = createScanner(COMPOSITE_KEY_METADATA, 100)) {
      scanner.scan(NAMESPACE, TABLE, handler);
    }

    // Assert
    Scanner firstScanner = issuedScanners.get(0);
    InOrder inOrder = inOrder(firstScanner, handler);
    inOrder.verify(firstScanner).close();
    inOrder.verify(handler).handle(r);
    assertThat(issuedScanners).allSatisfy(s -> verify(s).close());
  }

  @Test
  void scan_whenStorageScanThrows_shouldPropagateAndKeepCheckpoint() throws Exception {
    // Arrange
    writeLastKey(compositeKey("p1", 5, "b"));
    ExecutionException failure = new ExecutionException("scan failure");
    when(storage.scan(any())).thenThrow(failure);

    // Act & Assert
    try (JdbcResumableScanner scanner = createScanner(COMPOSITE_KEY_METADATA, 100)) {
      assertThatThrownBy(() -> scanner.scan(NAMESPACE, TABLE, r -> {})).isSameAs(failure);
    }
    assertThat(persistedLastKey(COMPOSITE_KEY_METADATA)).isEqualTo(compositeKey("p1", 5, "b"));
  }

  @Test
  void scan_whenReadingPageFails_shouldCloseScannerAndPropagate() throws Exception {
    // Arrange
    writeLastKey(compositeKey("p1", 5, "b"));
    ExecutionException failure = new ExecutionException("read failure");
    Scanner failingScanner = mock(Scanner.class);
    when(failingScanner.all()).thenThrow(failure);
    when(storage.scan(any())).thenReturn(failingScanner);
    RecordHandler handler = mock(RecordHandler.class);

    // Act & Assert
    try (JdbcResumableScanner scanner = createScanner(COMPOSITE_KEY_METADATA, 100)) {
      assertThatThrownBy(() -> scanner.scan(NAMESPACE, TABLE, handler)).isSameAs(failure);
    }
    verify(failingScanner).close();
    verify(handler, never()).handle(any());
    assertThat(persistedLastKey(COMPOSITE_KEY_METADATA)).isEqualTo(compositeKey("p1", 5, "b"));
  }

  @Test
  void scan_whenThreadInterruptedDuringHandling_shouldThrowInterruptedExceptionAfterPage()
      throws Exception {
    // Arrange
    enqueuePages(
        Arrays.asList(compositeRecord("p1", 9, "a"), compositeRecord("p1", 5, "a")),
        Collections.singletonList(compositeRecord("p1", 5, "b")));
    List<Result> handled = new ArrayList<>();
    RecordHandler handler =
        r -> {
          handled.add(r);
          Thread.currentThread().interrupt();
        };

    // Act & Assert
    try (JdbcResumableScanner scanner = createScanner(COMPOSITE_KEY_METADATA, 2)) {
      assertThatThrownBy(() -> scanner.scan(NAMESPACE, TABLE, handler))
          .isInstanceOf(InterruptedException.class);
    } finally {
      // Do not leak the interrupt status to other tests
      Thread.interrupted();
    }
    // The page being handled is completed and checkpointed, and no further page is read
    assertThat(handled).hasSize(2);
    assertThat(issuedScans).hasSize(1);
    assertThat(persistedLastKey(COMPOSITE_KEY_METADATA)).isEqualTo(compositeKey("p1", 5, "a"));
  }

  @Test
  void scan_whenTableMetadataNotFound_shouldThrowIllegalStateException() throws Exception {
    // Arrange
    when(storageAdmin.getTableMetadata(NAMESPACE, TABLE)).thenReturn(null);

    // Act & Assert
    try (JdbcResumableScanner scanner =
        new JdbcResumableScanner(storage, storageAdmin, checkpointManager, 100)) {
      assertThatThrownBy(() -> scanner.scan(NAMESPACE, TABLE, r -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(QUALIFIED_TABLE);
    }
    verify(storage, never()).scan(any());
  }

  @Test
  void scan_checkpointNotMatchingTableGiven_shouldThrowIllegalStateException() throws Exception {
    // Arrange
    writeLastKey(Collections.singletonList(TextColumn.of("id", "a")));

    // Act & Assert
    try (JdbcResumableScanner scanner = createScanner(COMPOSITE_KEY_METADATA, 100)) {
      assertThatThrownBy(() -> scanner.scan(NAMESPACE, TABLE, r -> {}))
          .isInstanceOf(IllegalStateException.class);
    }
    verify(storage, never()).scan(any());
  }

  @Test
  void scan_nullArgumentsGiven_shouldThrowNullPointerException() throws Exception {
    try (JdbcResumableScanner scanner = createScanner(COMPOSITE_KEY_METADATA, 100)) {
      assertThatThrownBy(() -> scanner.scan(null, TABLE, r -> {}))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> scanner.scan(NAMESPACE, null, r -> {}))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> scanner.scan(NAMESPACE, TABLE, null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Test
  void close_shouldCloseStorageAndAdmin() throws Exception {
    // Arrange
    JdbcResumableScanner scanner = createScanner(COMPOSITE_KEY_METADATA, 100);

    // Act
    scanner.close();

    // Assert
    verify(storage).close();
    verify(storageAdmin).close();
  }
}
