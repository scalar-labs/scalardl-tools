package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.Insert;
import com.scalar.db.api.Put;
import com.scalar.db.api.Result;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.TransactionState;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.service.StorageFactory;
import com.scalar.db.service.TransactionFactory;
import com.scalar.db.transaction.consensuscommit.Attribute;
import com.scalar.db.transaction.consensuscommit.CoordinatorStateAccessor;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class LedgerFinalizeOrchestratorIntegrationTestBase {

  private static final String NAMESPACE = "it_ledger_orch";

  /**
   * Namespace of the tables that are not ScalarDL's, such as the tables of ScalarDB Cluster that
   * shares the Coordinator table with Ledger. With multi-storage, it can be mapped to another
   * storage than the one of ScalarDL's tables.
   */
  protected static final String OTHER_NAMESPACE = "it_ledger_orch_other";

  private static final String TABLE_1 = "test_table_1";
  private static final String TABLE_2 = "test_table_2";
  private static final String TABLE_EMPTY = "test_table_empty";
  private static final int RECORDS_PER_TABLE = 10; // must be divisible by 5

  private static final String PARTITION_KEY = "pk";
  private static final String CLUSTERING_KEY = "ck";
  private static final String VALUE_COLUMN = "val";

  private DatabaseConfig databaseConfig;
  private DistributedTransactionAdmin admin;
  private DistributedStorageAdmin storageAdmin;
  private DistributedTransactionManager txManager;
  private DistributedStorage storage;
  private TwoPhaseCommitTransactionManager twoPhaseCommitTxManager;

  protected abstract Properties getProperties();

  @BeforeAll
  void setUpAll() throws Exception {
    Properties props = getProperties();

    databaseConfig = new DatabaseConfig(props);
    StorageFactory storageFactory = StorageFactory.create(props);
    storageAdmin = storageFactory.getStorageAdmin();
    storage = storageFactory.getStorage();
    TransactionFactory factory = TransactionFactory.create(props);
    admin = factory.getTransactionAdmin();
    txManager = factory.getTransactionManager();
    twoPhaseCommitTxManager = factory.getTwoPhaseCommitTransactionManager();
    assertThat(twoPhaseCommitTxManager).isNotNull();

    admin.createCoordinatorTables(true);
    admin.createNamespace(NAMESPACE);
    admin.createNamespace(OTHER_NAMESPACE);
    TableMetadata metadata = createMetadataWithClusteringKey();
    admin.createTable(namespaceOf(TABLE_1), TABLE_1, metadata);
    admin.createTable(namespaceOf(TABLE_2), TABLE_2, metadata);
    admin.createTable(namespaceOf(TABLE_EMPTY), TABLE_EMPTY, metadata);
  }

  @AfterAll
  void tearDownAll() throws Exception {
    try {
      for (String namespace : new String[] {NAMESPACE, OTHER_NAMESPACE}) {
        for (String table : admin.getNamespaceTableNames(namespace)) {
          admin.dropTable(namespace, table);
        }
        admin.dropNamespace(namespace, true);
      }
      admin.dropCoordinatorTables(true);
    } finally {
      admin.close();
      storageAdmin.close();
      txManager.close();
      storage.close();
      twoPhaseCommitTxManager.close();
    }
  }

  /**
   * Returns the namespace of the given table. {@code TABLE_2} is in {@code OTHER_NAMESPACE}, so
   * that the tests cover tables in two storages with multi-storage.
   */
  private static String namespaceOf(String table) {
    return TABLE_2.equals(table) ? OTHER_NAMESPACE : NAMESPACE;
  }

  private ResumableScannerFactory realScannerFactory() {
    return new ResumableScannerFactory(databaseConfig);
  }

  private TableMetadata createMetadataWithClusteringKey() {
    return TableMetadata.newBuilder()
        .addColumn(PARTITION_KEY, DataType.TEXT)
        .addColumn(CLUSTERING_KEY, DataType.INT)
        .addColumn(VALUE_COLUMN, DataType.TEXT)
        .addPartitionKey(PARTITION_KEY)
        .addClusteringKey(CLUSTERING_KEY)
        .build();
  }

  private void createCommittedRecord(String table, String pk, int ck) throws Exception {
    DistributedTransaction tx = txManager.start();
    tx.insert(
        Insert.newBuilder()
            .namespace(namespaceOf(table))
            .table(table)
            .partitionKey(Key.ofText(PARTITION_KEY, pk))
            .clusteringKey(Key.ofInt(CLUSTERING_KEY, ck))
            .textValue(VALUE_COLUMN, "value-" + ck)
            .build());
    tx.commit();
  }

  private String createPreparedRecord(String table, String pk, int ck) throws Exception {
    TwoPhaseCommitTransaction tx = twoPhaseCommitTxManager.start();
    tx.insert(
        Insert.newBuilder()
            .namespace(namespaceOf(table))
            .table(table)
            .partitionKey(Key.ofText(PARTITION_KEY, pk))
            .clusteringKey(Key.ofInt(CLUSTERING_KEY, ck))
            .textValue(VALUE_COLUMN, "prepared-" + ck)
            .build());
    tx.prepare();
    return tx.getId();
  }

  private String createDeletedRecord(String table, String pk, int ck) throws Exception {
    createCommittedRecord(table, pk, ck);

    TwoPhaseCommitTransaction tx = twoPhaseCommitTxManager.start();
    tx.get(
        Get.newBuilder()
            .namespace(namespaceOf(table))
            .table(table)
            .partitionKey(Key.ofText(PARTITION_KEY, pk))
            .clusteringKey(Key.ofInt(CLUSTERING_KEY, ck))
            .build());
    tx.delete(
        Delete.newBuilder()
            .namespace(namespaceOf(table))
            .table(table)
            .partitionKey(Key.ofText(PARTITION_KEY, pk))
            .clusteringKey(Key.ofInt(CLUSTERING_KEY, ck))
            .build());
    tx.prepare();
    return tx.getId();
  }

  private void updateCoordinatorStateToCommitted(String txId) throws Exception {
    Put put =
        Put.newBuilder()
            .namespace(CoordinatorStateAccessor.NAMESPACE)
            .table(CoordinatorStateAccessor.TABLE)
            .partitionKey(Key.ofText(Attribute.ID, txId))
            .intValue(Attribute.STATE, TransactionState.COMMITTED.get())
            .bigIntValue(Attribute.CREATED_AT, System.currentTimeMillis())
            .build();
    storage.put(put);
  }

  private void assertRecordAbsent(String table, String pk, int ck) throws Exception {
    Optional<Result> result = storage.get(buildGet(table, pk, ck));
    assertThat(result)
        .as("Expected record to be absent: table=%s, pk=%s, ck=%d", table, pk, ck)
        .isEmpty();
  }

  private void assertRecordCommitted(String table, String pk, int ck) throws Exception {
    Optional<Result> result = storage.get(buildGet(table, pk, ck));
    assertThat(result)
        .as("Expected record to exist: table=%s, pk=%s, ck=%d", table, pk, ck)
        .isPresent();
    assertThat(result.get().getInt(Attribute.STATE))
        .as("Expected COMMITTED state: table=%s, pk=%s, ck=%d", table, pk, ck)
        .isEqualTo(TransactionState.COMMITTED.get());
  }

  private void assertRecordNotCommitted(String table, String pk, int ck) throws Exception {
    Optional<Result> result = storage.get(buildGet(table, pk, ck));
    assertThat(result)
        .as("Expected record to exist: table=%s, pk=%s, ck=%d", table, pk, ck)
        .isPresent();
    assertThat(result.get().getInt(Attribute.STATE))
        .as("Expected record not to be in COMMITTED state: table=%s, pk=%s, ck=%d", table, pk, ck)
        .isNotEqualTo(TransactionState.COMMITTED.get());
  }

  private void assertRecordsFinalized(String... tables) throws Exception {
    for (String table : tables) {
      for (int i = 0; i < RECORDS_PER_TABLE; i++) {
        int mod = i % 5;
        if (mod == 1 || mod == 4) {
          assertRecordAbsent(table, "pk-" + i, i);
        } else {
          assertRecordCommitted(table, "pk-" + i, i);
        }
      }
    }
  }

  private Get buildGet(String table, String pk, int ck) {
    return Get.newBuilder()
        .namespace(namespaceOf(table))
        .table(table)
        .partitionKey(Key.ofText(PARTITION_KEY, pk))
        .clusteringKey(Key.ofInt(CLUSTERING_KEY, ck))
        .build();
  }

  @BeforeEach
  void setUp() throws Exception {
    for (String table : new String[] {TABLE_1, TABLE_2, TABLE_EMPTY}) {
      admin.truncateTable(namespaceOf(table), table);
    }

    for (String table : new String[] {TABLE_1, TABLE_2}) {
      for (int i = 0; i < RECORDS_PER_TABLE; i++) {
        switch (i % 5) {
          case 0:
            createCommittedRecord(table, "pk-" + i, i);
            break;
          case 1:
            createPreparedRecord(table, "pk-" + i, i);
            break;
          case 2:
            createDeletedRecord(table, "pk-" + i, i);
            break;
          case 3:
            updateCoordinatorStateToCommitted(createPreparedRecord(table, "pk-" + i, i));
            break;
          case 4:
            updateCoordinatorStateToCommitted(createDeletedRecord(table, "pk-" + i, i));
            break;
          default:
            throw new AssertionError("unreachable");
        }
      }
    }

    // Wait for PREPARED/DELETED state is expired (15s~).
    Thread.sleep(16_000);
  }

  private List<String> discoverAllTables() throws Exception {
    List<String> allTables = new ArrayList<>();
    for (String namespace : admin.getNamespaceNames()) {
      for (String table : admin.getNamespaceTableNames(namespace)) {
        allTables.add(namespace + "." + table);
      }
    }
    return allTables;
  }

  @Test
  public void execute_shouldFinalizeAllNonTerminalRecords(@TempDir Path checkpointDir)
      throws Exception {
    // Arrange

    // Act
    LedgerFinalizeOrchestrator orchestrator =
        new LedgerFinalizeOrchestrator(
            storageAdmin,
            txManager,
            realScannerFactory(),
            checkpointDir,
            CoordinatorStateAccessor.NAMESPACE);
    String completionToken = orchestrator.execute();

    // Assert
    assertThat(completionToken).isNotEmpty();
    CompletionToken token = CompletionToken.decode(completionToken);
    assertThat(token.getServerType()).isEqualTo(CompletionToken.ServerType.LEDGER);
    assertThat(token.getStartedAtMs()).isGreaterThan(0);
    assertRecordsFinalized(TABLE_1, TABLE_2);
  }

  @Test
  public void execute_mixedTimestampRecordsGiven_shouldFinalizeOnlyRecordsBeforeGuaranteeTimestamp(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange
    Thread.sleep(10);
    long guaranteeTimestamp = System.currentTimeMillis();
    Thread.sleep(10);
    // Create out-of-window records
    for (int i = 0; i < 3; i++) {
      createPreparedRecord(TABLE_1, "out-prep-" + i, i);
      createDeletedRecord(TABLE_1, "out-del-" + i, i);
    }

    // Persist state with the known start timestamp
    List<String> allTables = discoverAllTables();
    LedgerFinalizeStateManager stateManager = new LedgerFinalizeStateManager(checkpointDir);
    LedgerFinalizeState state =
        new LedgerFinalizeState(guaranteeTimestamp, allTables, new ArrayList<>());
    stateManager.persist(state);

    // Act
    LedgerFinalizeOrchestrator orchestrator =
        new LedgerFinalizeOrchestrator(
            storageAdmin,
            txManager,
            realScannerFactory(),
            checkpointDir,
            CoordinatorStateAccessor.NAMESPACE);
    String completionToken = orchestrator.execute();

    // Assert
    CompletionToken token = CompletionToken.decode(completionToken);
    assertThat(token.getStartedAtMs()).isEqualTo(guaranteeTimestamp);

    // In-window records → finalized
    assertRecordsFinalized(TABLE_1);

    // Out-of-window records are not finalized
    for (int i = 0; i < 3; i++) {
      assertRecordNotCommitted(TABLE_1, "out-prep-" + i, i);
      assertRecordNotCommitted(TABLE_1, "out-del-" + i, i);
    }
  }

  @Test
  public void execute_nonExpiredPreparedRecordGiven_shouldSkipItAndCompleteNormally(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange: a PREPARED record whose writer is still in flight
    createPreparedRecord(TABLE_1, "in-flight", 0);
    // The record was prepared before now, so now + 1 is strictly greater than its prepared_at and
    // keeps it in window.
    long tL = System.currentTimeMillis() + 1;

    List<String> allTables = discoverAllTables();
    LedgerFinalizeStateManager stateManager = new LedgerFinalizeStateManager(checkpointDir);
    LedgerFinalizeState state = new LedgerFinalizeState(tL, allTables, new ArrayList<>());
    stateManager.persist(state);

    // Act
    LedgerFinalizeOrchestrator orchestrator =
        new LedgerFinalizeOrchestrator(
            storageAdmin,
            txManager,
            realScannerFactory(),
            checkpointDir,
            CoordinatorStateAccessor.NAMESPACE);
    String completionToken = orchestrator.execute();

    // Assert: a false from recoverRecord must not abort the table; the sweep completes normally.
    assertThat(completionToken).isNotEmpty();
    CompletionToken token = CompletionToken.decode(completionToken);
    assertThat(token.getStartedAtMs()).isEqualTo(tL);

    // The not-yet-recoverable record is left untouched (still non-terminal).
    assertRecordNotCommitted(TABLE_1, "in-flight", 0);

    // The expired records were still finalized, confirming the scan did real work.
    assertRecordsFinalized(TABLE_1, TABLE_2);
  }

  @Test
  public void execute_partiallyCompletedStateGiven_shouldResumeAndPreserveTimestamp(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange
    String qualifiedTable1 = namespaceOf(TABLE_1) + "." + TABLE_1;
    String qualifiedTable2 = namespaceOf(TABLE_2) + "." + TABLE_2;
    List<String> allTables = discoverAllTables();

    Thread.sleep(10);
    long fixedTimestamp = System.currentTimeMillis();
    Thread.sleep(10);

    // Persist state with TABLE_1 already completed
    LedgerFinalizeStateManager stateManager = new LedgerFinalizeStateManager(checkpointDir);
    LedgerFinalizeState state =
        new LedgerFinalizeState(
            fixedTimestamp, allTables, Collections.singletonList(qualifiedTable1));
    stateManager.persist(state);

    // Act
    LedgerFinalizeOrchestrator orchestrator =
        new LedgerFinalizeOrchestrator(
            storageAdmin,
            txManager,
            realScannerFactory(),
            checkpointDir,
            CoordinatorStateAccessor.NAMESPACE);
    String completionToken = orchestrator.execute();

    // Assert
    CompletionToken token = CompletionToken.decode(completionToken);
    assertThat(token.getStartedAtMs()).isEqualTo(fixedTimestamp);

    // TABLE_2 → finalized
    assertRecordsFinalized(TABLE_2);

    // TABLE_1 → skipped: non-terminal records should still exist
    for (int i = 0; i < RECORDS_PER_TABLE; i++) {
      int mod = i % 5;
      if (mod == 1 || mod == 3) {
        assertRecordNotCommitted(TABLE_1, "pk-" + i, i);
      }
    }

    LedgerFinalizeState updatedState = stateManager.load();
    assertThat(updatedState).isNotNull();
    assertThat(updatedState.getCompletedTables()).contains(qualifiedTable1, qualifiedTable2);
    assertThat(updatedState.getCompletedTables()).containsAll(allTables);
  }
}
