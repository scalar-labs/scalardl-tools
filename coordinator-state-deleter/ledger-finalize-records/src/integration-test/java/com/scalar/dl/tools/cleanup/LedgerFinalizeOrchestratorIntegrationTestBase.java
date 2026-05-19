package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedStorage;
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
import com.scalar.db.transaction.consensuscommit.Coordinator;
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
  private static final String TABLE_1 = "test_table_1";
  private static final String TABLE_2 = "test_table_2";
  private static final String TABLE_EMPTY = "test_table_empty";
  private static final int RECORDS_PER_TABLE = 10; // must be divisible by 5

  private static final int DEFAULT_WORKER_THREADS = 4;

  private static final String PARTITION_KEY = "pk";
  private static final String CLUSTERING_KEY = "ck";
  private static final String VALUE_COLUMN = "val";

  private DatabaseConfig databaseConfig;
  private DistributedTransactionAdmin admin;
  private DistributedTransactionManager txManager;
  private DistributedStorage storage;
  private TwoPhaseCommitTransactionManager twoPhaseCommitTxManager;

  protected abstract Properties getProperties();

  @BeforeAll
  void setUpAll() throws Exception {
    Properties props = getProperties();

    databaseConfig = new DatabaseConfig(props);
    TransactionFactory factory = TransactionFactory.create(props);
    admin = factory.getTransactionAdmin();
    txManager = factory.getTransactionManager();
    storage = StorageFactory.create(props).getStorage();
    twoPhaseCommitTxManager = factory.getTwoPhaseCommitTransactionManager();
    assertThat(twoPhaseCommitTxManager).isNotNull();

    admin.createCoordinatorTables(true);
    admin.createNamespace(NAMESPACE);
    TableMetadata metadata = createMetadataWithClusteringKey();
    admin.createTable(NAMESPACE, TABLE_1, metadata);
    admin.createTable(NAMESPACE, TABLE_2, metadata);
    admin.createTable(NAMESPACE, TABLE_EMPTY, metadata);
  }

  @AfterAll
  void tearDownAll() throws Exception {
    try {
      for (String table : admin.getNamespaceTableNames(NAMESPACE)) {
        admin.dropTable(NAMESPACE, table);
      }
      admin.dropNamespace(NAMESPACE, true);
      admin.dropCoordinatorTables(true);
    } finally {
      admin.close();
      txManager.close();
      storage.close();
      twoPhaseCommitTxManager.close();
    }
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
            .namespace(NAMESPACE)
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
            .namespace(NAMESPACE)
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
            .namespace(NAMESPACE)
            .table(table)
            .partitionKey(Key.ofText(PARTITION_KEY, pk))
            .clusteringKey(Key.ofInt(CLUSTERING_KEY, ck))
            .build());
    tx.delete(
        Delete.newBuilder()
            .namespace(NAMESPACE)
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
            .namespace(Coordinator.NAMESPACE)
            .table(Coordinator.TABLE)
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
        .namespace(NAMESPACE)
        .table(table)
        .partitionKey(Key.ofText(PARTITION_KEY, pk))
        .clusteringKey(Key.ofInt(CLUSTERING_KEY, ck))
        .build();
  }

  @BeforeEach
  void setUp() throws Exception {
    admin.truncateTable(NAMESPACE, TABLE_1);
    admin.truncateTable(NAMESPACE, TABLE_2);
    admin.truncateTable(NAMESPACE, TABLE_EMPTY);

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
            admin, txManager, realScannerFactory(), checkpointDir, DEFAULT_WORKER_THREADS);
    String completionToken = orchestrator.execute();

    // Assert
    assertThat(completionToken).isNotEmpty();
    CompletionToken token = CompletionToken.decode(completionToken);
    assertThat(token.getServer()).isEqualTo(CompletionToken.Server.LEDGER);
    assertThat(token.getStartedAtMs()).isGreaterThan(0);
    assertRecordsFinalized(TABLE_1, TABLE_2);
  }

  @Test
  public void execute_singleWorkerThreadGiven_shouldFinalizeAllNonTerminalRecords(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange

    // Act
    LedgerFinalizeOrchestrator orchestrator =
        new LedgerFinalizeOrchestrator(admin, txManager, realScannerFactory(), checkpointDir, 1);
    String completionToken = orchestrator.execute();

    // Assert
    assertThat(completionToken).isNotEmpty();
    CompletionToken token = CompletionToken.decode(completionToken);
    assertThat(token.getServer()).isEqualTo(CompletionToken.Server.LEDGER);
    assertThat(token.getStartedAtMs()).isGreaterThan(0);
    assertRecordsFinalized(TABLE_1, TABLE_2);
  }

  @Test
  public void execute_mixedTimestampRecordsGiven_shouldFinalizeOnlyRecordsBeforeGuaranteeTimestamp(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange
    Thread.sleep(10);
    long tL = System.currentTimeMillis();
    Thread.sleep(10);
    // Create out-of-window records
    for (int i = 0; i < 3; i++) {
      createPreparedRecord(TABLE_1, "out-prep-" + i, i);
      createDeletedRecord(TABLE_1, "out-del-" + i, i);
    }

    // Persist state with t_L as the guarantee timestamp
    List<String> allTables = discoverAllTables();
    LedgerFinalizeStateManager stateManager = new LedgerFinalizeStateManager(checkpointDir);
    LedgerFinalizeState state = new LedgerFinalizeState(tL, allTables, new ArrayList<>());
    stateManager.persist(state);

    // Act
    LedgerFinalizeOrchestrator orchestrator =
        new LedgerFinalizeOrchestrator(
            admin, txManager, realScannerFactory(), checkpointDir, DEFAULT_WORKER_THREADS);
    String completionToken = orchestrator.execute();

    // Assert
    CompletionToken token = CompletionToken.decode(completionToken);
    assertThat(token.getStartedAtMs()).isEqualTo(tL);

    // In-window records → finalized
    assertRecordsFinalized(TABLE_1);

    // Out-of-window records are not finalized
    for (int i = 0; i < 3; i++) {
      assertRecordNotCommitted(TABLE_1, "out-prep-" + i, i);
      assertRecordNotCommitted(TABLE_1, "out-del-" + i, i);
    }
  }

  @Test
  public void execute_partiallyCompletedStateGiven_shouldResumeAndPreserveTimestamp(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange
    String qualifiedTable1 = NAMESPACE + "." + TABLE_1;
    String qualifiedTable2 = NAMESPACE + "." + TABLE_2;
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
            admin, txManager, realScannerFactory(), checkpointDir, DEFAULT_WORKER_THREADS);
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
