package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Insert;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.TransactionState;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.service.StorageFactory;
import com.scalar.db.service.TransactionFactory;
import com.scalar.dl.tools.cleanup.fixtures.NonTerminalRecordWriter;
import com.scalar.dl.tools.common.VerificationResult;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the verification against real storages. Each run is created in the same way as the command
 * creates it. The verification scans every transactional table that the configuration reaches, so
 * the tests expect that no other table has non-terminal records.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class LedgerVerifyOrchestratorIntegrationTestBase {

  protected static final String NAMESPACE = "it_ledger_verify";

  /**
   * Namespace of the tables that are not ScalarDL's, such as the tables of ScalarDB Cluster that
   * shares the Coordinator table with Ledger. With multi-storage, it can be mapped to another
   * storage than the one of ScalarDL's tables.
   */
  protected static final String OTHER_NAMESPACE = "it_ledger_verify_other";

  private static final String TABLE = "test_table";
  private static final String OTHER_TABLE = "test_table_other";

  private static final String PARTITION_KEY = "pk";
  private static final String CLUSTERING_KEY = "ck";
  private static final String VALUE_COLUMN = "val";

  private DistributedTransactionAdmin admin;
  private DistributedStorageAdmin storageAdmin;
  private DistributedTransactionManager txManager;
  private DistributedStorage storage;
  private NonTerminalRecordWriter nonTerminalRecordWriter;

  protected abstract Properties getProperties();

  @BeforeAll
  void setUpAll() throws Exception {
    Properties props = getProperties();

    StorageFactory storageFactory = StorageFactory.create(props);
    storageAdmin = storageFactory.getStorageAdmin();
    storage = storageFactory.getStorage();
    TransactionFactory factory = TransactionFactory.create(props);
    admin = factory.getTransactionAdmin();
    txManager = factory.getTransactionManager();
    nonTerminalRecordWriter = new NonTerminalRecordWriter(storage, storageAdmin);

    admin.createCoordinatorTables(true);
    admin.createNamespace(NAMESPACE);
    admin.createNamespace(OTHER_NAMESPACE);
    TableMetadata metadata =
        TableMetadata.newBuilder()
            .addColumn(PARTITION_KEY, DataType.TEXT)
            .addColumn(CLUSTERING_KEY, DataType.INT)
            .addColumn(VALUE_COLUMN, DataType.TEXT)
            .addPartitionKey(PARTITION_KEY)
            .addClusteringKey(CLUSTERING_KEY)
            .build();
    admin.createTable(NAMESPACE, TABLE, metadata);
    admin.createTable(OTHER_NAMESPACE, OTHER_TABLE, metadata);
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
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    admin.truncateTable(NAMESPACE, TABLE);
    admin.truncateTable(OTHER_NAMESPACE, OTHER_TABLE);
    for (int i = 0; i < 3; i++) {
      createCommittedRecord(NAMESPACE, TABLE, "committed-" + i, i);
      createCommittedRecord(OTHER_NAMESPACE, OTHER_TABLE, "committed-" + i, i);
    }
  }

  private void createCommittedRecord(String namespace, String table, String pk, int ck)
      throws Exception {
    DistributedTransaction tx = txManager.start();
    tx.insert(
        Insert.newBuilder()
            .namespace(namespace)
            .table(table)
            .partitionKey(Key.ofText(PARTITION_KEY, pk))
            .clusteringKey(Key.ofInt(CLUSTERING_KEY, ck))
            .textValue(VALUE_COLUMN, "value-" + ck)
            .build());
    tx.commit();
  }

  /** Writes a PREPARED record and returns the ID of its transaction. */
  private String createPreparedRecord(String namespace, String table, String pk, int ck)
      throws Exception {
    String txId = NonTerminalRecordWriter.newTransactionId();
    nonTerminalRecordWriter.writePreparedInsert(
        txId,
        Insert.newBuilder()
            .namespace(namespace)
            .table(table)
            .partitionKey(Key.ofText(PARTITION_KEY, pk))
            .clusteringKey(Key.ofInt(CLUSTERING_KEY, ck))
            .textValue(VALUE_COLUMN, "prepared-" + ck)
            .build());
    return txId;
  }

  /** Writes a DELETED record and returns the ID of its transaction. */
  private String createDeletedRecord(String namespace, String table, String pk, int ck)
      throws Exception {
    createCommittedRecord(namespace, table, pk, ck);
    String txId = NonTerminalRecordWriter.newTransactionId();
    nonTerminalRecordWriter.writePreparedDelete(
        txId,
        Delete.newBuilder()
            .namespace(namespace)
            .table(table)
            .partitionKey(Key.ofText(PARTITION_KEY, pk))
            .clusteringKey(Key.ofInt(CLUSTERING_KEY, ck))
            .build());
    return txId;
  }

  private VerificationResult verify() throws Exception {
    try (LedgerVerifyOrchestrator orchestrator = LedgerVerifyOrchestrator.create(getProperties())) {
      return orchestrator.execute();
    }
  }

  @Test
  public void execute_onlyTerminalRecordsGiven_shouldReturnPassed() throws Exception {
    // Act
    VerificationResult result = verify();

    // Assert
    assertThat(result).isEqualTo(VerificationResult.PASSED);
  }

  @Test
  public void execute_nonTerminalRecordsGivenAndThenFinalized_shouldReturnFailedAndThenPassed(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange
    // The transactions have been decided, so that the finalization does not have to wait for the
    // records to expire.
    String preparedTxId = createPreparedRecord(NAMESPACE, TABLE, "prepared", 0);
    String deletedTxId = createDeletedRecord(NAMESPACE, TABLE, "deleted", 0);
    nonTerminalRecordWriter.putCoordinatorState(preparedTxId, TransactionState.COMMITTED);
    nonTerminalRecordWriter.putCoordinatorState(deletedTxId, TransactionState.COMMITTED);

    // Act
    VerificationResult beforeFinalization = verify();
    // The finalization targets the records prepared before it started.
    Thread.sleep(10);
    try (LedgerFinalizeOrchestrator finalizeOrchestrator =
        LedgerFinalizeOrchestrator.create(getProperties(), checkpointDir)) {
      finalizeOrchestrator.execute();
    }
    VerificationResult afterFinalization = verify();

    // Assert
    assertThat(beforeFinalization).isEqualTo(VerificationResult.FAILED);
    assertThat(afterFinalization).isEqualTo(VerificationResult.PASSED);
  }

  @Test
  public void execute_nonTerminalRecordOnlyInOtherNamespaceGiven_shouldReturnFailed()
      throws Exception {
    // Arrange
    // With multi-storage, the table of this namespace is in the storage that is not the default.
    createPreparedRecord(OTHER_NAMESPACE, OTHER_TABLE, "prepared", 0);

    // Act
    VerificationResult result = verify();

    // Assert
    assertThat(result).isEqualTo(VerificationResult.FAILED);
  }

  @Test
  public void execute_runAgainAfterDetectionGiven_shouldDetectSameRecordAgain() throws Exception {
    // Arrange
    createDeletedRecord(NAMESPACE, TABLE, "deleted", 0);

    // Act
    VerificationResult first = verify();
    VerificationResult second = verify();

    // Assert
    assertThat(first).isEqualTo(VerificationResult.FAILED);
    assertThat(second).isEqualTo(VerificationResult.FAILED);
  }
}
