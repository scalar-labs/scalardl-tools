package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Put;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.service.StorageFactory;
import com.scalar.dl.auditor.ordering.LockRecoveryResult;
import com.scalar.dl.client.service.AuditorClient;
import com.scalar.dl.rpc.AssetLockRecoveryRequest;
import com.scalar.dl.tools.common.AuditorInternalValues;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.common.CoordinatorStateDeleterException;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test for {@link AuditorFinalizeOrchestrator} against real ScalarDB storage.
 *
 * <p>Scope: the tool's responsibility is to scan the real {@code asset_lock} tables, decide which
 * locks need finalization, and call {@code RecoverAssetLock} with the correct arguments. The
 * <em>effect</em> of recovery (releasing the lock) is ScalarDL Auditor's responsibility, not this
 * tool's, and is covered by the real-server e2e on the {@code add-real-server-e2e-infra} branch.
 *
 * <p>Therefore the {@link AuditorClient} is mocked with no side effect, and assertions verify the
 * set of {@code recover} invocations (logical namespace + asset id) rather than reading the table
 * back — which would only reflect what the mock wrote, not what the tool did.
 */
@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AuditorFinalizeOrchestratorIntegrationTestBase {

  private static final String NAMESPACE = "it_auditor_orch";
  private static final int RECORDS_COUNT = 8; // must be divisible by 4
  private static final long OLD_TIMESTAMP = 1L;
  // Far-future timestamp, capped at 2^53 — the max value ScalarDB allows for a BIGINT in Cosmos.
  private static final long FUTURE_TIMESTAMP = 9_007_199_254_740_992L;

  private DatabaseConfig databaseConfig;
  private DistributedStorageAdmin storageAdmin;
  private DistributedStorage storage;
  private AuditorClient auditorClient;
  private Set<String> recoverKeys;

  protected abstract Properties getProperties();

  @BeforeAll
  void setUpAll() throws Exception {
    Properties props = getProperties();
    databaseConfig = new DatabaseConfig(props);
    StorageFactory storageFactory = StorageFactory.create(props);
    storageAdmin = storageFactory.getStorageAdmin();
    storage = storageFactory.getStorage();

    storageAdmin.createNamespace(NAMESPACE, true);
    TableMetadata metadata =
        TableMetadata.newBuilder()
            .addColumn(AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME, DataType.TEXT)
            .addColumn(AuditorInternalValues.ASSET_LOCK_TABLE_LOCK_TYPE_COLUMN_NAME, DataType.INT)
            .addColumn(
                AuditorInternalValues.ASSET_LOCK_TABLE_LAST_UPDATED_AT_COLUMN_NAME, DataType.BIGINT)
            .addPartitionKey(AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME)
            .build();
    storageAdmin.createTable(
        NAMESPACE, AuditorInternalValues.ASSET_LOCK_TABLE_NAME, metadata, true);
  }

  @AfterAll
  void tearDownAll() throws Exception {
    try {
      storageAdmin.dropTable(NAMESPACE, AuditorInternalValues.ASSET_LOCK_TABLE_NAME, true);
      storageAdmin.dropNamespace(NAMESPACE, true);
    } finally {
      storageAdmin.close();
      storage.close();
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    // The mock records the recovered key and returns SUCCEEDED so the tool treats the lock as
    // finalized. A concurrent set is used because recovery runs on the scan threads.
    auditorClient = mock(AuditorClient.class);
    recoverKeys = ConcurrentHashMap.newKeySet();
    when(auditorClient.recover(any()))
        .thenAnswer(
            invocation -> {
              AssetLockRecoveryRequest request = invocation.getArgument(0);
              recoverKeys.add(recoverKey(request.getNamespace(), request.getAssetId()));
              return LockRecoveryResult.SUCCEEDED;
            });

    storageAdmin.truncateTable(NAMESPACE, AuditorInternalValues.ASSET_LOCK_TABLE_NAME);

    for (int i = 0; i < RECORDS_COUNT; i++) {
      int lockType;
      long lastUpdatedAt;
      switch (i % 4) {
        case 0:
          lockType = AuditorInternalValues.LOCK_TYPE_NONE;
          lastUpdatedAt = OLD_TIMESTAMP;
          break;
        case 1:
          lockType = AuditorInternalValues.LOCK_TYPE_WRITE;
          lastUpdatedAt = OLD_TIMESTAMP;
          break;
        case 2:
          lockType = AuditorInternalValues.LOCK_TYPE_READ;
          lastUpdatedAt = OLD_TIMESTAMP;
          break;
        case 3:
          lockType = AuditorInternalValues.LOCK_TYPE_WRITE;
          lastUpdatedAt = FUTURE_TIMESTAMP;
          break;
        default:
          throw new AssertionError("unreachable");
      }
      putLockRecord("asset-" + i, lockType, lastUpdatedAt);
    }
  }

  private void putLockRecord(String assetId, int lockType, long lastUpdatedAt) throws Exception {
    putLockRecord(NAMESPACE, assetId, lockType, lastUpdatedAt);
  }

  private void putLockRecord(String namespace, String assetId, int lockType, long lastUpdatedAt)
      throws Exception {
    Put put =
        Put.newBuilder()
            .namespace(namespace)
            .table(AuditorInternalValues.ASSET_LOCK_TABLE_NAME)
            .partitionKey(
                Key.ofText(AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME, assetId))
            .intValue(AuditorInternalValues.ASSET_LOCK_TABLE_LOCK_TYPE_COLUMN_NAME, lockType)
            .bigIntValue(
                AuditorInternalValues.ASSET_LOCK_TABLE_LAST_UPDATED_AT_COLUMN_NAME, lastUpdatedAt)
            .build();
    storage.put(put);
  }

  private ResumableScannerFactory realScannerFactory() {
    return new ResumableScannerFactory(databaseConfig);
  }

  private AuditorFinalizeOrchestrator createOrchestrator(Path checkpointDir) {
    return new AuditorFinalizeOrchestrator(
        storageAdmin, storage, auditorClient, realScannerFactory(), checkpointDir, NAMESPACE);
  }

  /** Returns the set of {@code namespace::assetId} keys for which {@code recover} was invoked. */
  private Set<String> capturedRecoverKeys() {
    return new HashSet<>(recoverKeys);
  }

  private static String recoverKey(String logicalNamespace, String assetId) {
    return logicalNamespace + "::" + assetId;
  }

  @Test
  public void execute_mixedLockRecordsGiven_shouldRecoverOnlyUnreleasedLocksBeforeGuarantee(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange — seeded by setUp(); the default logical namespace is swept.
    // i%4: 0=NONE/old (skip), 1=WRITE/old (recover), 2=READ/old (recover, always),
    //      3=WRITE/future (skip, after guarantee).
    // Add a READ lock AFTER the guarantee: it must still be recovered, because a refreshed
    // last_updated_at can mask a stranded read owner (see LockStateChecker).
    putLockRecord("read-future", AuditorInternalValues.LOCK_TYPE_READ, FUTURE_TIMESTAMP);

    // Act
    AuditorFinalizeOrchestrator orchestrator = createOrchestrator(checkpointDir);
    String completionToken = orchestrator.execute();

    // Assert — token shape
    assertThat(completionToken).isNotEmpty();
    CompletionToken token = CompletionToken.decode(completionToken);
    assertThat(token.getServerType()).isEqualTo(CompletionToken.ServerType.AUDITOR);
    assertThat(token.getStartedAtMs()).isGreaterThan(0);

    // Assert — recover invoked exactly for the WRITE-old, READ-old, and READ-future records
    // (READ is recovered regardless of timestamp; WRITE-future and NONE are skipped).
    Set<String> expected =
        new HashSet<>(
            Arrays.asList(
                recoverKey("default", "asset-1"),
                recoverKey("default", "asset-2"),
                recoverKey("default", "asset-5"),
                recoverKey("default", "asset-6"),
                recoverKey("default", "read-future")));
    assertThat(capturedRecoverKeys()).isEqualTo(expected);
  }

  @Test
  public void execute_lockNotRecoverableThenRecoverableGiven_shouldDeferAndRecoverOnRetry(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange — a single READ lock. Its first recovery attempt (during the scan) reports the lock
    // as still active (NOT_RECOVERABLE); by the time of the post-scan retry it can be released.
    storageAdmin.truncateTable(NAMESPACE, AuditorInternalValues.ASSET_LOCK_TABLE_NAME);
    putLockRecord("read-active", AuditorInternalValues.LOCK_TYPE_READ, OLD_TIMESTAMP);

    AtomicInteger attempts = new AtomicInteger();
    when(auditorClient.recover(any()))
        .thenAnswer(
            invocation -> {
              AssetLockRecoveryRequest request = invocation.getArgument(0);
              recoverKeys.add(recoverKey(request.getNamespace(), request.getAssetId()));
              return attempts.getAndIncrement() == 0
                  ? LockRecoveryResult.NOT_RECOVERABLE
                  : LockRecoveryResult.SUCCEEDED;
            });

    // Act
    AuditorFinalizeOrchestrator orchestrator = createOrchestrator(checkpointDir);
    String completionToken = orchestrator.execute();

    // Assert — the lock was recovered on the post-scan retry (attempted once during the scan and
    // once on retry) and the run completed with a token.
    assertThat(attempts.get()).isEqualTo(2);
    assertThat(capturedRecoverKeys()).containsExactly(recoverKey("default", "read-active"));
    assertThat(completionToken).isNotEmpty();

    // The deferred log is cleared and the namespace is marked completed.
    Path deferredFinalizationsFile =
        checkpointDir
            .resolve(AuditorFinalizeStateManager.SUBDIRECTORY)
            .resolve(NAMESPACE + ".deferred");
    assertThat(Files.exists(deferredFinalizationsFile)).isFalse();
    AuditorFinalizeState state = new AuditorFinalizeStateManager(checkpointDir).load();
    assertThat(state).isNotNull();
    assertThat(state.getCompletedNamespaces()).containsExactly("default");
  }

  @Test
  public void execute_lockRemainsNotRecoverableGiven_shouldAbortAndKeepDeferredLog(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange — a single READ lock whose recovery always reports it as still active.
    storageAdmin.truncateTable(NAMESPACE, AuditorInternalValues.ASSET_LOCK_TABLE_NAME);
    putLockRecord("read-active", AuditorInternalValues.LOCK_TYPE_READ, OLD_TIMESTAMP);
    when(auditorClient.recover(any())).thenReturn(LockRecoveryResult.NOT_RECOVERABLE);

    // Act & Assert — the run aborts and emits no token.
    AuditorFinalizeOrchestrator orchestrator = createOrchestrator(checkpointDir);
    assertThatThrownBy(orchestrator::execute)
        .isInstanceOf(CoordinatorStateDeleterException.class)
        .hasMessageContaining("read-active");

    // The deferred log is kept and the namespace is not marked completed, so a resume retries it.
    Path deferredFinalizationsFile =
        checkpointDir
            .resolve(AuditorFinalizeStateManager.SUBDIRECTORY)
            .resolve(NAMESPACE + ".deferred");
    assertThat(Files.exists(deferredFinalizationsFile)).isTrue();
    AuditorFinalizeState state = new AuditorFinalizeStateManager(checkpointDir).load();
    assertThat(state).isNotNull();
    assertThat(state.getCompletedNamespaces()).isEmpty();
  }

  @Test
  public void execute_mixedTimestampRecordsGiven_shouldRecoverOnlyLocksBeforeGuaranteeTimestamp(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange — pin the guarantee timestamp and add one WRITE lock on each side of it.
    long guaranteeTimestamp = System.currentTimeMillis();
    putLockRecord("before-ts", AuditorInternalValues.LOCK_TYPE_WRITE, guaranteeTimestamp - 100);
    putLockRecord("after-ts", AuditorInternalValues.LOCK_TYPE_WRITE, guaranteeTimestamp + 100);

    AuditorFinalizeStateManager stateManager = new AuditorFinalizeStateManager(checkpointDir);
    stateManager.persist(
        new AuditorFinalizeState(guaranteeTimestamp, Collections.singletonList("default")));

    // Act
    AuditorFinalizeOrchestrator orchestrator = createOrchestrator(checkpointDir);
    String completionToken = orchestrator.execute();

    // Assert
    CompletionToken token = CompletionToken.decode(completionToken);
    assertThat(token.getStartedAtMs()).isEqualTo(guaranteeTimestamp);

    // Exact set: the seeded WRITE-old (asset-1, asset-5) and READ-old (asset-2, asset-6) records
    // plus before-ts are recovered; after-ts (WRITE after the guarantee), the WRITE-future records
    // (asset-3, asset-7), and the NONE records (asset-0, asset-4) are all skipped.
    Set<String> expected =
        new HashSet<>(
            Arrays.asList(
                recoverKey("default", "asset-1"),
                recoverKey("default", "asset-2"),
                recoverKey("default", "asset-5"),
                recoverKey("default", "asset-6"),
                recoverKey("default", "before-ts")));
    assertThat(capturedRecoverKeys()).isEqualTo(expected);
  }

  @Test
  public void execute_resumeGiven_shouldReusePersistedTimestamp(@TempDir Path checkpointDir)
      throws Exception {
    // Arrange
    long fixedTimestamp = System.currentTimeMillis();
    AuditorFinalizeStateManager stateManager = new AuditorFinalizeStateManager(checkpointDir);
    stateManager.persist(
        new AuditorFinalizeState(fixedTimestamp, Collections.singletonList("default")));

    // Act
    AuditorFinalizeOrchestrator orchestrator = createOrchestrator(checkpointDir);
    String completionToken = orchestrator.execute();

    // Assert
    CompletionToken token = CompletionToken.decode(completionToken);
    assertThat(token.getStartedAtMs()).isEqualTo(fixedTimestamp);

    // The swept namespace is persisted as completed, so a further resume would skip it.
    AuditorFinalizeState finalState = stateManager.load();
    assertThat(finalState).isNotNull();
    assertThat(finalState.getCompletedNamespaces()).containsExactly("default");
  }

  @Test
  public void execute_completedNamespaceGiven_shouldSkipScanningThatTable(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange — the checkpoint marks the (only) namespace as already finalized.
    AuditorFinalizeStateManager stateManager = new AuditorFinalizeStateManager(checkpointDir);
    stateManager.persist(
        new AuditorFinalizeState(
            System.currentTimeMillis(),
            Collections.singletonList("default"),
            Collections.singletonList("default")));

    // Act
    AuditorFinalizeOrchestrator orchestrator = createOrchestrator(checkpointDir);
    orchestrator.execute();

    // Assert — the namespace is skipped, so no lock is recovered.
    assertThat(capturedRecoverKeys()).isEmpty();
  }

  @Test
  public void execute_multipleNamespacesGiven_shouldRecoverLocksInEveryNamespace(
      @TempDir Path checkpointDir) throws Exception {
    // Arrange — register "ns1" and seed a held WRITE lock in both the base namespace and ns1.
    String ns1Physical = AuditorInternalValues.resolveNamespace(NAMESPACE, "ns1");
    createNamespaceRegistryTable();
    createAssetLockTableIn(ns1Physical);
    try {
      putRegisteredNamespace();
      putLockRecord(NAMESPACE, "base-held", AuditorInternalValues.LOCK_TYPE_WRITE, OLD_TIMESTAMP);
      putLockRecord(ns1Physical, "ns1-held", AuditorInternalValues.LOCK_TYPE_WRITE, OLD_TIMESTAMP);

      // Act
      AuditorFinalizeOrchestrator orchestrator = createOrchestrator(checkpointDir);
      orchestrator.execute();

      // Assert — recover invoked with each namespace's LOGICAL name and the held asset id, for
      // exactly the held WRITE lock in each namespace plus the recoverable records seeded by
      // setUp() in the base namespace (WRITE-old asset-1/asset-5, READ-old asset-2/asset-6).
      Set<String> expected =
          new HashSet<>(
              Arrays.asList(
                  recoverKey("default", "asset-1"),
                  recoverKey("default", "asset-2"),
                  recoverKey("default", "asset-5"),
                  recoverKey("default", "asset-6"),
                  recoverKey("default", "base-held"),
                  recoverKey("ns1", "ns1-held")));
      assertThat(capturedRecoverKeys()).isEqualTo(expected);
    } finally {
      storageAdmin.dropTable(ns1Physical, AuditorInternalValues.ASSET_LOCK_TABLE_NAME, true);
      storageAdmin.dropNamespace(ns1Physical, true);
      storageAdmin.dropTable(NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE_NAME, true);
    }
  }

  private void createNamespaceRegistryTable() throws Exception {
    TableMetadata metadata =
        TableMetadata.newBuilder()
            .addColumn(AuditorInternalValues.NAMESPACE_TABLE_PARTITION_ID_COLUMN_NAME, DataType.INT)
            .addColumn(AuditorInternalValues.NAMESPACE_TABLE_NAME_COLUMN_NAME, DataType.TEXT)
            .addPartitionKey(AuditorInternalValues.NAMESPACE_TABLE_PARTITION_ID_COLUMN_NAME)
            .addClusteringKey(AuditorInternalValues.NAMESPACE_TABLE_NAME_COLUMN_NAME)
            .build();
    storageAdmin.createTable(NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE_NAME, metadata, true);
  }

  private void createAssetLockTableIn(String namespace) throws Exception {
    storageAdmin.createNamespace(namespace, true);
    TableMetadata metadata =
        TableMetadata.newBuilder()
            .addColumn(AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME, DataType.TEXT)
            .addColumn(AuditorInternalValues.ASSET_LOCK_TABLE_LOCK_TYPE_COLUMN_NAME, DataType.INT)
            .addColumn(
                AuditorInternalValues.ASSET_LOCK_TABLE_LAST_UPDATED_AT_COLUMN_NAME, DataType.BIGINT)
            .addPartitionKey(AuditorInternalValues.ASSET_LOCK_TABLE_ID_COLUMN_NAME)
            .build();
    storageAdmin.createTable(
        namespace, AuditorInternalValues.ASSET_LOCK_TABLE_NAME, metadata, true);
  }

  private void putRegisteredNamespace() throws Exception {
    Put put =
        Put.newBuilder()
            .namespace(NAMESPACE)
            .table(AuditorInternalValues.NAMESPACE_TABLE_NAME)
            .partitionKey(
                Key.ofInt(
                    AuditorInternalValues.NAMESPACE_TABLE_PARTITION_ID_COLUMN_NAME,
                    AuditorInternalValues.NAMESPACE_TABLE_DEFAULT_PARTITION_ID))
            .clusteringKey(
                Key.ofText(AuditorInternalValues.NAMESPACE_TABLE_NAME_COLUMN_NAME, "ns1"))
            .build();
    storage.put(put);
  }
}
