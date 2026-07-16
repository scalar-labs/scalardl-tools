package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Get;
import com.scalar.db.api.Put;
import com.scalar.db.api.Result;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.service.StorageFactory;
import com.scalar.dl.tools.common.AuditorInternalValues;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
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
public abstract class RequestProofCleanupOrchestratorIntegrationTestBase {

  private static final long DELETABLE_BEFORE_MS = 1_000_000_000L;

  private static final int RECORDS_PER_CATEGORY = 3;

  private static final String NAMESPACE = AuditorInternalValues.DEFAULT_BASE_NAMESPACE;
  private static final String TABLE = AuditorInternalValues.REQUEST_PROOF_TABLE_NAME;
  private static final String NONCE = "nonce";
  private static final String REGISTERED_AT =
      AuditorInternalValues.REQUEST_PROOF_TABLE_REGISTERED_AT_COLUMN_NAME;

  private static final TableMetadata REQUEST_PROOF_METADATA =
      TableMetadata.newBuilder()
          .addColumn(NONCE, DataType.TEXT)
          .addColumn(REGISTERED_AT, DataType.BIGINT)
          .addPartitionKey(NONCE)
          .build();

  private DistributedStorageAdmin admin;
  private DistributedStorage storage;
  private ResumableScannerFactory scannerFactory;

  protected abstract Properties getProperties();

  @BeforeAll
  void setUpAll() throws Exception {
    Properties props = getProperties();
    DatabaseConfig databaseConfig = new DatabaseConfig(props);
    StorageFactory storageFactory = StorageFactory.create(props);
    storage = storageFactory.getStorage();
    admin = storageFactory.getStorageAdmin();
    scannerFactory = new ResumableScannerFactory(databaseConfig);
    admin.createNamespace(NAMESPACE, true);
    admin.createTable(NAMESPACE, TABLE, REQUEST_PROOF_METADATA, true);
  }

  @AfterAll
  void tearDownAll() throws Exception {
    try {
      admin.dropTable(NAMESPACE, TABLE, true);
      admin.dropNamespace(NAMESPACE, true);
    } finally {
      admin.close();
      storage.close();
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    admin.truncateTable(NAMESPACE, TABLE);
    populateRequestProof();
  }

  private void putRequestProofRecord(String nonce, long registeredAt) throws Exception {
    Put put =
        Put.newBuilder()
            .namespace(NAMESPACE)
            .table(TABLE)
            .partitionKey(Key.ofText(NONCE, nonce))
            .bigIntValue(REGISTERED_AT, registeredAt)
            .build();
    storage.put(put);
  }

  private void populateRequestProof() throws Exception {
    // Category A: old (should be deleted)
    for (int i = 0; i < RECORDS_PER_CATEGORY; i++) {
      putRequestProofRecord("old-" + i, DELETABLE_BEFORE_MS - 10000);
    }
    // Category B: boundary at DELETABLE_BEFORE_MS (should remain, < comparison)
    for (int i = 0; i < RECORDS_PER_CATEGORY; i++) {
      putRequestProofRecord("boundary-" + i, DELETABLE_BEFORE_MS);
    }
    // Category C: new (should remain)
    for (int i = 0; i < RECORDS_PER_CATEGORY; i++) {
      putRequestProofRecord("new-" + i, DELETABLE_BEFORE_MS + 10000);
    }
  }

  private String createAuditorToken(long startedAtMs) {
    return CompletionToken.create(CompletionToken.ServerType.AUDITOR, startedAtMs).encode();
  }

  private void assertRequestProofRecordPresent(String nonce) throws Exception {
    Get get =
        Get.newBuilder()
            .namespace(NAMESPACE)
            .table(TABLE)
            .partitionKey(Key.ofText(NONCE, nonce))
            .build();
    Optional<Result> result = storage.get(get);
    assertThat(result)
        .as("Expected request_proof record to be present: nonce=%s", nonce)
        .isPresent();
  }

  private void assertRequestProofRecordAbsent(String nonce) throws Exception {
    Get get =
        Get.newBuilder()
            .namespace(NAMESPACE)
            .table(TABLE)
            .partitionKey(Key.ofText(NONCE, nonce))
            .build();
    Optional<Result> result = storage.get(get);
    assertThat(result).as("Expected request_proof record to be absent: nonce=%s", nonce).isEmpty();
  }

  private static final String[][] RECORD_CATEGORIES = {
    {"old-", String.valueOf(DELETABLE_BEFORE_MS - 10000)},
    {"boundary-", String.valueOf(DELETABLE_BEFORE_MS)},
    {"new-", String.valueOf(DELETABLE_BEFORE_MS + 10000)},
  };

  private void assertDeletionCorrect(long deletableBeforeMs) throws Exception {
    for (String[] category : RECORD_CATEGORIES) {
      String prefix = category[0];
      long registeredAt = Long.parseLong(category[1]);
      for (int i = 0; i < RECORDS_PER_CATEGORY; i++) {
        String nonce = prefix + i;
        if (registeredAt < deletableBeforeMs) {
          assertRequestProofRecordAbsent(nonce);
        } else {
          assertRequestProofRecordPresent(nonce);
        }
      }
    }
  }

  static Stream<Arguments> deletableBeforeMsProvider() {
    // The records span [DELETABLE_BEFORE_MS - 10000, DELETABLE_BEFORE_MS + 10000]. These boundaries
    // exercise the three regimes: delete nothing, delete the old records only, and delete all.
    return Stream.of(
        // at the oldest record's timestamp: strict "<" comparison deletes nothing
        Arguments.of(DELETABLE_BEFORE_MS - 10000),
        // deletes only the old records; the boundary and newer records are kept
        Arguments.of(DELETABLE_BEFORE_MS),
        // one past the newest record (DELETABLE_BEFORE_MS + 10000): deletes everything
        Arguments.of(DELETABLE_BEFORE_MS + 10001));
  }

  @ParameterizedTest
  @MethodSource("deletableBeforeMsProvider")
  void execute_shouldDeleteOnlyDeletableRecords(long deletableBeforeMs, @TempDir Path checkpointDir)
      throws Exception {
    // Arrange
    String auditorToken = createAuditorToken(deletableBeforeMs);

    // Act
    RequestProofCleanupOrchestrator orchestrator =
        new RequestProofCleanupOrchestrator(
            storage, scannerFactory, checkpointDir, NAMESPACE, auditorToken);
    orchestrator.execute();

    // Assert
    assertDeletionCorrect(deletableBeforeMs);
  }

  @Test
  void execute_initialRunGiven_shouldPersistTokenTimestampAsBoundary(@TempDir Path checkpointDir)
      throws Exception {
    // Arrange
    String auditorToken = createAuditorToken(DELETABLE_BEFORE_MS);

    // Act
    RequestProofCleanupOrchestrator orchestrator =
        new RequestProofCleanupOrchestrator(
            storage, scannerFactory, checkpointDir, NAMESPACE, auditorToken);
    orchestrator.execute();

    // Assert
    assertDeletionCorrect(DELETABLE_BEFORE_MS);

    RequestProofCleanupStateManager stateManager =
        new RequestProofCleanupStateManager(checkpointDir);
    RequestProofCleanupState state = stateManager.load();
    assertThat(state).isNotNull();
    assertThat(state.getDeletableBeforeMs()).isEqualTo(DELETABLE_BEFORE_MS);
  }

  @Test
  void execute_resumedStateGiven_shouldResumeAndPreserveDeletableBeforeMs(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange: persist state with DELETABLE_BEFORE_MS, proving the token won't be parsed on resume
    RequestProofCleanupStateManager stateManager =
        new RequestProofCleanupStateManager(checkpointDir);
    stateManager.persist(new RequestProofCleanupState(DELETABLE_BEFORE_MS));

    // Act
    RequestProofCleanupOrchestrator orchestrator =
        new RequestProofCleanupOrchestrator(
            storage, scannerFactory, checkpointDir, NAMESPACE, null);
    orchestrator.execute();

    // Assert
    assertDeletionCorrect(DELETABLE_BEFORE_MS);

    RequestProofCleanupState updatedState = stateManager.load();
    assertThat(updatedState).isNotNull();
    assertThat(updatedState.getDeletableBeforeMs()).isEqualTo(DELETABLE_BEFORE_MS);
    assertThat(updatedState.isCompleted()).isTrue();
  }

  @Test
  void execute_alreadyCompletedStateGiven_shouldDoNothing(@TempDir Path checkpointDir)
      throws Exception {
    // Arrange
    String auditorToken = createAuditorToken(DELETABLE_BEFORE_MS);

    // Act: the first run performs the cleanup and marks the checkpoint completed
    new RequestProofCleanupOrchestrator(
            storage, scannerFactory, checkpointDir, NAMESPACE, auditorToken)
        .execute();
    // The second run against the completed checkpoint must be a safe no-op
    new RequestProofCleanupOrchestrator(storage, scannerFactory, checkpointDir, NAMESPACE, null)
        .execute();

    // Assert
    assertDeletionCorrect(DELETABLE_BEFORE_MS);
    RequestProofCleanupState state = new RequestProofCleanupStateManager(checkpointDir).load();
    assertThat(state).isNotNull();
    assertThat(state.isCompleted()).isTrue();
  }

  @Test
  void execute_resumedAfterMidScanFailureGiven_shouldDeleteOnlyDeletableRecordsAndComplete(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange
    String auditorToken = createAuditorToken(DELETABLE_BEFORE_MS);

    // A spy that throws on the 3rd call, simulating a crash partway through the scan.
    RequestProofDeleter interruptingDeleter = spy(new RequestProofDeleter(storage, NAMESPACE));
    AtomicLong deleteCount = new AtomicLong();
    doAnswer(
            invocation -> {
              if (deleteCount.incrementAndGet() >= 3) {
                throw new RuntimeException("Simulated interruption");
              }
              return invocation.callRealMethod();
            })
        .when(interruptingDeleter)
        .execute(any(Result.class));

    // Act 1: the first run fails partway through the scan.
    RequestProofCleanupOrchestrator failingRun =
        new RequestProofCleanupOrchestrator(
            storage, scannerFactory, checkpointDir, NAMESPACE, auditorToken, interruptingDeleter);
    assertThatThrownBy(failingRun::execute).isInstanceOf(RuntimeException.class);

    // The checkpoint must be left resumable: the boundary is persisted but the run is not
    // completed.
    RequestProofCleanupStateManager stateManager =
        new RequestProofCleanupStateManager(checkpointDir);
    RequestProofCleanupState interruptedState = stateManager.load();
    assertThat(interruptedState).isNotNull();
    assertThat(interruptedState.getDeletableBeforeMs()).isEqualTo(DELETABLE_BEFORE_MS);
    assertThat(interruptedState.isCompleted()).isFalse();

    // Act 2: re-run against the same checkpoint without a token, resuming the previous run.
    new RequestProofCleanupOrchestrator(storage, scannerFactory, checkpointDir, NAMESPACE, null)
        .execute();

    // Assert: exactly the deletable records are gone, the rest remain, and the run completed.
    assertDeletionCorrect(DELETABLE_BEFORE_MS);
    RequestProofCleanupState finalState = stateManager.load();
    assertThat(finalState).isNotNull();
    assertThat(finalState.isCompleted()).isTrue();
  }
}
