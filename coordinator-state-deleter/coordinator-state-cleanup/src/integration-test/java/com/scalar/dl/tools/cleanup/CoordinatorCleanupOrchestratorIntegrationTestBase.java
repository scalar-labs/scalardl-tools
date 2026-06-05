package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.Get;
import com.scalar.db.api.Put;
import com.scalar.db.api.Result;
import com.scalar.db.api.TransactionState;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.io.Key;
import com.scalar.db.service.StorageFactory;
import com.scalar.db.service.TransactionFactory;
import com.scalar.db.transaction.consensuscommit.Attribute;
import com.scalar.db.transaction.consensuscommit.Coordinator;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class CoordinatorCleanupOrchestratorIntegrationTestBase {

  private static final long T_MIN = 1_000_000_000L;
  private static final int RECORDS_PER_CATEGORY = 3;
  private static final int DEFAULT_WORKER_THREADS = 4;

  private DistributedTransactionAdmin admin;
  private DistributedStorage storage;
  private ResumableScannerFactory scannerFactory;

  protected abstract Properties getProperties();

  @BeforeAll
  void setUpAll() throws Exception {
    Properties props = getProperties();
    DatabaseConfig databaseConfig = new DatabaseConfig(props);
    StorageFactory storageFactory = StorageFactory.create(props);
    storage = storageFactory.getStorage();
    TransactionFactory factory = TransactionFactory.create(props);
    admin = factory.getTransactionAdmin();
    scannerFactory = new ResumableScannerFactory(databaseConfig);
    admin.createCoordinatorTables(true);
  }

  @AfterAll
  void tearDownAll() throws Exception {
    try {
      admin.dropCoordinatorTables(true);
    } finally {
      admin.close();
      storage.close();
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    admin.truncateCoordinatorTables();
    populateCoordinatorState();
  }

  private void putCoordinatorRecord(String txId, TransactionState state, long createdAt)
      throws Exception {
    Put put =
        Put.newBuilder()
            .namespace(Coordinator.NAMESPACE)
            .table(Coordinator.TABLE)
            .partitionKey(Key.ofText(Attribute.ID, txId))
            .intValue(Attribute.STATE, state.get())
            .bigIntValue(Attribute.CREATED_AT, createdAt)
            .build();
    storage.put(put);
  }

  private void populateCoordinatorState() throws Exception {
    // Category A: old COMMITTED (should be deleted)
    for (int i = 0; i < RECORDS_PER_CATEGORY; i++) {
      putCoordinatorRecord("old-committed-" + i, TransactionState.COMMITTED, T_MIN - 10000);
    }
    // Category B: old ABORTED (should be deleted)
    for (int i = 0; i < RECORDS_PER_CATEGORY; i++) {
      putCoordinatorRecord("old-aborted-" + i, TransactionState.ABORTED, T_MIN - 5000);
    }
    // Category C: boundary at T_MIN (should remain, < comparison)
    for (int i = 0; i < RECORDS_PER_CATEGORY; i++) {
      putCoordinatorRecord("boundary-" + i, TransactionState.COMMITTED, T_MIN);
    }
    // Category D: new COMMITTED (should remain)
    for (int i = 0; i < RECORDS_PER_CATEGORY; i++) {
      putCoordinatorRecord("new-committed-" + i, TransactionState.COMMITTED, T_MIN + 5000);
    }
    // Category E: new ABORTED (should remain)
    for (int i = 0; i < RECORDS_PER_CATEGORY; i++) {
      putCoordinatorRecord("new-aborted-" + i, TransactionState.ABORTED, T_MIN + 10000);
    }
  }

  private String createLedgerToken(long startedAtMs) {
    return CompletionToken.create(CompletionToken.ServerType.LEDGER, startedAtMs).encode();
  }

  private String createAuditorToken(long startedAtMs) {
    return CompletionToken.create(CompletionToken.ServerType.AUDITOR, startedAtMs).encode();
  }

  private void assertCoordinatorRecordPresent(String txId) throws Exception {
    Get get =
        Get.newBuilder()
            .namespace(Coordinator.NAMESPACE)
            .table(Coordinator.TABLE)
            .partitionKey(Key.ofText(Attribute.ID, txId))
            .build();
    Optional<Result> result = storage.get(get);
    assertThat(result).as("Expected coordinator record to be present: tx_id=%s", txId).isPresent();
  }

  private void assertCoordinatorRecordAbsent(String txId) throws Exception {
    Get get =
        Get.newBuilder()
            .namespace(Coordinator.NAMESPACE)
            .table(Coordinator.TABLE)
            .partitionKey(Key.ofText(Attribute.ID, txId))
            .build();
    Optional<Result> result = storage.get(get);
    assertThat(result).as("Expected coordinator record to be absent: tx_id=%s", txId).isEmpty();
  }

  private static final String[][] RECORD_CATEGORIES = {
    {"old-committed-", String.valueOf(T_MIN - 10000)},
    {"old-aborted-", String.valueOf(T_MIN - 5000)},
    {"boundary-", String.valueOf(T_MIN)},
    {"new-committed-", String.valueOf(T_MIN + 5000)},
    {"new-aborted-", String.valueOf(T_MIN + 10000)},
  };

  private void assertDeletionCorrect(long deletableBeforeMs) throws Exception {
    for (String[] category : RECORD_CATEGORIES) {
      String prefix = category[0];
      long createdAt = Long.parseLong(category[1]);
      for (int i = 0; i < RECORDS_PER_CATEGORY; i++) {
        String txId = prefix + i;
        if (createdAt < deletableBeforeMs) {
          assertCoordinatorRecordAbsent(txId);
        } else {
          assertCoordinatorRecordPresent(txId);
        }
      }
    }
  }

  static Stream<Arguments> deletableBeforeMsAndWorkerThreadsProvider() {
    return Stream.of(
        Arguments.of(T_MIN - 10000, 0, DEFAULT_WORKER_THREADS),
        Arguments.of(T_MIN, RECORDS_PER_CATEGORY * 2, DEFAULT_WORKER_THREADS),
        Arguments.of(T_MIN, RECORDS_PER_CATEGORY * 2, 1),
        Arguments.of(T_MIN + 10001, RECORDS_PER_CATEGORY * 5, DEFAULT_WORKER_THREADS));
  }

  @ParameterizedTest
  @MethodSource("deletableBeforeMsAndWorkerThreadsProvider")
  void execute_shouldDeleteOnlyDeletableRecords(
      long deletableBeforeMs,
      int expectedDeletedCount,
      int workerThreads,
      @TempDir Path checkpointDir)
      throws Exception {
    // Arrange
    String ledgerToken = createLedgerToken(deletableBeforeMs);
    String auditorToken = createAuditorToken(deletableBeforeMs);

    // Act
    CoordinatorCleanupOrchestrator orchestrator =
        new CoordinatorCleanupOrchestrator(
            storage, scannerFactory, checkpointDir, workerThreads, ledgerToken, auditorToken);
    long deletedCount = orchestrator.execute();

    // Assert
    assertThat(deletedCount).isEqualTo(expectedDeletedCount);
    assertDeletionCorrect(deletableBeforeMs);
  }

  static Stream<Arguments> tokenTimestampProvider() {
    return Stream.of(
        Arguments.of(T_MIN + 20000, T_MIN),
        Arguments.of(T_MIN, T_MIN + 20000),
        Arguments.of(T_MIN, T_MIN));
  }

  @ParameterizedTest
  @MethodSource("tokenTimestampProvider")
  void execute_shouldUseSmallerTimestampAsDeletableBeforeMs(
      long ledgerTimestamp, long auditorTimestamp, @TempDir Path checkpointDir) throws Exception {
    // Arrange
    String ledgerToken = createLedgerToken(ledgerTimestamp);
    String auditorToken = createAuditorToken(auditorTimestamp);

    // Act
    CoordinatorCleanupOrchestrator orchestrator =
        new CoordinatorCleanupOrchestrator(
            storage,
            scannerFactory,
            checkpointDir,
            DEFAULT_WORKER_THREADS,
            ledgerToken,
            auditorToken);
    long deletedCount = orchestrator.execute();

    // Assert
    assertThat(deletedCount).isEqualTo(RECORDS_PER_CATEGORY * 2);
    assertDeletionCorrect(T_MIN);

    CoordinatorCleanupStateManager stateManager = new CoordinatorCleanupStateManager(checkpointDir);
    CoordinatorCleanupState state = stateManager.load();
    assertThat(state).isNotNull();
    assertThat(state.getDeletableBeforeMs()).isEqualTo(T_MIN);
  }

  @Test
  void execute_resumedStateGiven_shouldResumeAndPreserveDeletableBeforeMs(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange: persist state with T_MIN, proving tokens won't be parsed during resume
    CoordinatorCleanupStateManager stateManager = new CoordinatorCleanupStateManager(checkpointDir);
    stateManager.persist(new CoordinatorCleanupState(T_MIN));

    String ledgerToken = createLedgerToken(T_MIN + 99999);
    String auditorToken = createAuditorToken(T_MIN + 99999);

    // Act
    CoordinatorCleanupOrchestrator orchestrator =
        new CoordinatorCleanupOrchestrator(
            storage,
            scannerFactory,
            checkpointDir,
            DEFAULT_WORKER_THREADS,
            ledgerToken,
            auditorToken);
    long deletedCount = orchestrator.execute();

    // Assert
    assertThat(deletedCount).isEqualTo(RECORDS_PER_CATEGORY * 2);
    assertDeletionCorrect(T_MIN);

    CoordinatorCleanupState updatedState = stateManager.load();
    assertThat(updatedState).isNotNull();
    assertThat(updatedState.getDeletableBeforeMs()).isEqualTo(T_MIN);
  }
}
