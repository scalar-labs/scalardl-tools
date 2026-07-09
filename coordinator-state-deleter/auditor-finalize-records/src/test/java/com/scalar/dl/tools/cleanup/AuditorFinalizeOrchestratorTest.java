package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.Scanner;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.service.StorageFactory;
import com.scalar.dl.auditor.ordering.LockRecoveryResult;
import com.scalar.dl.client.service.AuditorClient;
import com.scalar.dl.rpc.AssetLockRecoveryRequest;
import com.scalar.dl.tools.common.AuditorInternalValues;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.common.CoordinatorStateDeleterError;
import com.scalar.dl.tools.common.CoordinatorStateDeleterException;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

@SuppressWarnings("resource")
class AuditorFinalizeOrchestratorTest {

  private static final String NAMESPACE = "auditor";

  @TempDir Path tempDir;

  private DistributedStorageAdmin admin;
  private DistributedStorage storage;
  private AuditorClient auditorClient;
  private ResumableScanner scanner;
  private ResumableScannerFactory scannerFactory;

  @BeforeEach
  void setUp() {
    admin = mock(DistributedStorageAdmin.class);
    storage = mock(DistributedStorage.class);
    auditorClient = mock(AuditorClient.class);
    scanner = mock(ResumableScanner.class);
    scannerFactory = mock(ResumableScannerFactory.class);
    when(scannerFactory.create(any())).thenReturn(scanner);
  }

  /** Creates a mock namespace {@link Result} with the given name. */
  private Result createMockNamespaceResult(String name) {
    Result result = mock(Result.class);
    when(result.getText(AuditorInternalValues.NAMESPACE_TABLE_NAME_COLUMN_NAME)).thenReturn(name);
    return result;
  }

  /** Creates a mock {@link Scanner} that iterates over the given results. */
  private Scanner createMockScanner(Result... results) {
    Scanner mockScanner = mock(Scanner.class);
    Iterator<Result> iterator = Arrays.asList(results).iterator();
    when(mockScanner.iterator()).thenReturn(iterator);
    return mockScanner;
  }

  private AuditorFinalizeOrchestrator orchestrator() {
    return new AuditorFinalizeOrchestrator(
        admin, storage, auditorClient, scannerFactory, tempDir, NAMESPACE);
  }

  @Test
  void create_nonCosmosStorageGiven_shouldThrowCoordinatorStateDeleterException() {
    // Arrange
    Properties auditorProps = new Properties();
    auditorProps.setProperty(DatabaseConfig.STORAGE, "cassandra");

    // Act & Assert
    assertThatThrownBy(
            () -> AuditorFinalizeOrchestrator.create(auditorProps, new Properties(), tempDir))
        .isInstanceOf(CoordinatorStateDeleterException.class)
        .hasMessageContaining("not supported");
  }

  @Test
  void create_cosmosStorageGiven_shouldNotThrow() {
    // Arrange
    Properties auditorProps = new Properties();
    auditorProps.setProperty(DatabaseConfig.STORAGE, "cosmos");
    Properties clientProps = new Properties();
    clientProps.setProperty("scalar.dl.client.mode", "client");
    clientProps.setProperty("scalar.dl.client.entity.id", "test-entity");
    clientProps.setProperty("scalar.dl.client.authentication.method", "hmac");
    clientProps.setProperty("scalar.dl.client.entity.identity.hmac.secret_key", "test-secret");
    clientProps.setProperty("scalar.dl.client.auditor.enabled", "true");

    StorageFactory storageFactory = mock(StorageFactory.class);
    when(storageFactory.getStorageAdmin()).thenReturn(mock(DistributedStorageAdmin.class));
    when(storageFactory.getStorage()).thenReturn(mock(DistributedStorage.class));

    try (MockedStatic<StorageFactory> storageFactoryStatic = mockStatic(StorageFactory.class);
        MockedConstruction<AuditorClient> ignored = mockConstruction(AuditorClient.class)) {
      storageFactoryStatic
          .when(() -> StorageFactory.create(any(Properties.class)))
          .thenReturn(storageFactory);

      // Act & Assert
      assertThatCode(() -> AuditorFinalizeOrchestrator.create(auditorProps, clientProps, tempDir))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void execute_initialRunGiven_shouldScanReturnTokenAndPersistState() throws Exception {
    // Arrange — no namespace registry table, so only the default namespace is swept.
    when(admin.tableExists(NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE_NAME))
        .thenReturn(false);
    when(scanner.scan(anyString(), anyString(), any())).thenReturn(new ScanResult(10));

    // Act — the start timestamp is captured as now() - the lock validity period.
    long before = System.currentTimeMillis() - AuditorInternalValues.LOCK_VALID_PERIOD_MS;
    String completionToken = orchestrator().execute();
    long after = System.currentTimeMillis() - AuditorInternalValues.LOCK_VALID_PERIOD_MS;

    // Assert
    CompletionToken token = CompletionToken.decode(completionToken);
    assertThat(token.getServerType()).isEqualTo(CompletionToken.ServerType.AUDITOR);
    assertThat(token.getStartedAtMs()).isBetween(before, after);

    verify(scanner).scan(eq(NAMESPACE), eq("asset_lock"), any());
    verify(scanner).close();

    // State is persisted with the default namespace completed and the token's start timestamp.
    AuditorFinalizeState state = new AuditorFinalizeStateManager(tempDir).load();
    assertThat(state).isNotNull();
    assertThat(state.getCompletedNamespaces()).containsExactly("default");
    assertThat(state.getStartedAtMs()).isEqualTo(token.getStartedAtMs());
  }

  @Test
  void execute_namespaceTableExistsGiven_shouldScanAllRegisteredNamespaces() throws Exception {
    // Arrange
    when(admin.tableExists(NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE_NAME)).thenReturn(true);
    Scanner namespaceScanner =
        createMockScanner(createMockNamespaceResult("ns1"), createMockNamespaceResult("ns2"));
    when(storage.scan(any(Scan.class))).thenReturn(namespaceScanner);
    when(scanner.scan(anyString(), anyString(), any())).thenReturn(new ScanResult(0));

    // Act
    orchestrator().execute();

    // Assert — default namespace (physical = base) + two registered namespaces, each resolved to
    // its physical namespace (<base>_<ns>), should be scanned, and nothing else.
    verify(scanner).scan(eq(NAMESPACE), eq("asset_lock"), any());
    verify(scanner).scan(eq("auditor_ns1"), eq("asset_lock"), any());
    verify(scanner).scan(eq("auditor_ns2"), eq("asset_lock"), any());
    verify(scanner, times(3)).scan(anyString(), anyString(), any());
  }

  @Test
  void execute_scanFailureGiven_shouldPropagateException() throws Exception {
    // Arrange — only the default namespace, whose scan fails.
    when(admin.tableExists(NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE_NAME))
        .thenReturn(false);
    when(scanner.scan(eq(NAMESPACE), eq("asset_lock"), any()))
        .thenThrow(new RuntimeException("Cosmos DB unavailable"));

    // Act & Assert
    assertThatThrownBy(() -> orchestrator().execute())
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Cosmos DB unavailable");

    verify(scanner).close();
  }

  @Test
  void execute_scanFailureGiven_shouldNotMarkCompleted() throws Exception {
    // Arrange — default + ns1; the default succeeds, ns1 fails.
    when(admin.tableExists(NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE_NAME)).thenReturn(true);
    Scanner namespaceScanner = createMockScanner(createMockNamespaceResult("ns1"));
    when(storage.scan(any(com.scalar.db.api.Scan.class))).thenReturn(namespaceScanner);
    when(scanner.scan(eq(NAMESPACE), eq("asset_lock"), any())).thenReturn(new ScanResult(10));
    when(scanner.scan(eq("auditor_ns1"), eq("asset_lock"), any()))
        .thenThrow(new RuntimeException("Cosmos DB unavailable"));

    // Act
    assertThatThrownBy(() -> orchestrator().execute()).isInstanceOf(RuntimeException.class);

    // Assert — only the finished namespace is marked completed; the failed one is left out.
    AuditorFinalizeState state = new AuditorFinalizeStateManager(tempDir).load();
    assertThat(state).isNotNull();
    assertThat(state.getCompletedNamespaces()).containsExactly("default");
  }

  @Test
  void execute_resumedStateGiven_shouldSkipCompletedNamespacesAndProcessRemaining()
      throws Exception {
    // Arrange — pre-persist a checkpoint where the default namespace is already completed.
    long startedAtMs = 1000L;
    AuditorFinalizeStateManager stateManager = new AuditorFinalizeStateManager(tempDir);
    stateManager.persist(
        new AuditorFinalizeState(
            startedAtMs, Arrays.asList("default", "ns1"), Collections.singletonList("default")));
    when(scanner.scan(anyString(), anyString(), any())).thenReturn(new ScanResult(5));

    // Act
    String token = orchestrator().execute();

    // Assert — the completed default namespace is skipped, only ns1 is scanned, and the original
    // start timestamp is carried over across the resume.
    verify(scanner, never()).scan(eq(NAMESPACE), eq("asset_lock"), any());
    verify(scanner).scan(eq("auditor_ns1"), eq("asset_lock"), any());
    assertThat(CompletionToken.decode(token).getStartedAtMs()).isEqualTo(startedAtMs);

    // The newly processed namespace is persisted alongside the pre-completed one.
    AuditorFinalizeState finalState = new AuditorFinalizeStateManager(tempDir).load();
    assertThat(finalState).isNotNull();
    assertThat(finalState.getCompletedNamespaces()).containsExactlyInAnyOrder("default", "ns1");
  }

  /** Path of the deferred-finalizations file the orchestrator uses for the default namespace. */
  private Path defaultDeferredFinalizationsPath() {
    return AuditorFinalizeOrchestrator.deferredFinalizationsPath(
        new AuditorFinalizeStateManager(tempDir), NAMESPACE);
  }

  @Test
  void execute_finalizationDeferredDuringGiven_shouldCompleteAndClearLog() throws Exception {
    // Arrange - during the scan a lock is deferred (its recovery returned NOT_RECOVERABLE),
    // simulated here by the scanner appending to the deferred log.
    when(admin.tableExists(NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE_NAME))
        .thenReturn(false);
    AppendOnlyLog seededLog = new AppendOnlyLog(defaultDeferredFinalizationsPath());
    when(scanner.scan(eq(NAMESPACE), eq("asset_lock"), any()))
        .thenAnswer(
            invocation -> {
              seededLog.append("active-asset");
              return new ScanResult(1);
            });
    // The deferred lock is releasable by the time the post-scan retry runs.
    when(auditorClient.recover(any(AssetLockRecoveryRequest.class)))
        .thenReturn(LockRecoveryResult.SUCCEEDED);

    // Act
    String token = orchestrator().execute();

    // Assert - the deferred lock is retried with its logical namespace, the log is cleared, the
    // namespace is marked completed, and a token is emitted.
    ArgumentCaptor<AssetLockRecoveryRequest> captor =
        ArgumentCaptor.forClass(AssetLockRecoveryRequest.class);
    verify(auditorClient).recover(captor.capture());
    assertThat(captor.getValue().getNamespace()).isEqualTo("default");
    assertThat(captor.getValue().getAssetId()).isEqualTo("active-asset");
    assertThat(seededLog.load()).isEmpty();
    assertThat(CompletionToken.decode(token).getServerType())
        .isEqualTo(CompletionToken.ServerType.AUDITOR);

    AuditorFinalizeState state = new AuditorFinalizeStateManager(tempDir).load();
    assertThat(state).isNotNull();
    assertThat(state.getCompletedNamespaces()).containsExactly("default");
  }

  @Test
  void execute_deferredLockStillActiveOnRetryGiven_shouldAbortAndKeepLogAndNotComplete()
      throws Exception {
    // Arrange — the deferred lock is still active when the post-scan retry runs.
    when(admin.tableExists(NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE_NAME))
        .thenReturn(false);
    AppendOnlyLog seededLog = new AppendOnlyLog(defaultDeferredFinalizationsPath());
    when(scanner.scan(eq(NAMESPACE), eq("asset_lock"), any()))
        .thenAnswer(
            invocation -> {
              seededLog.append("active-asset");
              return new ScanResult(1);
            });
    when(auditorClient.recover(any(AssetLockRecoveryRequest.class)))
        .thenReturn(LockRecoveryResult.NOT_RECOVERABLE);

    // Act & Assert — the run aborts with a descriptive error and emits no token.
    assertThatThrownBy(() -> orchestrator().execute())
        .isInstanceOf(CoordinatorStateDeleterException.class)
        .hasMessageContaining(CoordinatorStateDeleterError.ASSET_LOCK_STILL_ACTIVE.buildCode())
        .hasMessageContaining("active-asset");

    // The still-active lock is kept in the log so it is retried, and the namespace is not
    // completed.
    assertThat(seededLog.load()).containsExactly("active-asset");
    AuditorFinalizeState state = new AuditorFinalizeStateManager(tempDir).load();
    assertThat(state).isNotNull();
    assertThat(state.getCompletedNamespaces()).isEmpty();
  }

  @Test
  void execute_someDeferredLocksFinalizedOnRetryGiven_shouldKeepOnlyStillActiveInLog()
      throws Exception {
    // Arrange — the scan defers three locks; on retry two finalize and one is still active.
    when(admin.tableExists(NAMESPACE, AuditorInternalValues.NAMESPACE_TABLE_NAME))
        .thenReturn(false);
    AppendOnlyLog seededLog = new AppendOnlyLog(defaultDeferredFinalizationsPath());
    when(scanner.scan(eq(NAMESPACE), eq("asset_lock"), any()))
        .thenAnswer(
            invocation -> {
              seededLog.append("finalized-1");
              seededLog.append("still-active");
              seededLog.append("finalized-2");
              return new ScanResult(3);
            });
    when(auditorClient.recover(any(AssetLockRecoveryRequest.class)))
        .thenAnswer(
            invocation -> {
              AssetLockRecoveryRequest request = invocation.getArgument(0);
              return request.getAssetId().equals("still-active")
                  ? LockRecoveryResult.NOT_RECOVERABLE
                  : LockRecoveryResult.SUCCEEDED;
            });

    // Act & Assert — the run aborts because one lock is still active.
    assertThatThrownBy(() -> orchestrator().execute())
        .isInstanceOf(CoordinatorStateDeleterException.class)
        .hasMessageContaining(CoordinatorStateDeleterError.ASSET_LOCK_STILL_ACTIVE.buildCode())
        .hasMessageContaining("still-active");

    // The log is rewritten to hold only the still-active lock; the finalized ones are dropped so it
    // cannot grow across re-runs. The namespace is not marked completed.
    assertThat(seededLog.load()).containsExactly("still-active");
    AuditorFinalizeState state = new AuditorFinalizeStateManager(tempDir).load();
    assertThat(state).isNotNull();
    assertThat(state.getCompletedNamespaces()).isEmpty();
  }

  @Test
  void close_shouldCloseAdminAndStorageAndAuditorClient() {
    // Arrange
    AuditorFinalizeOrchestrator orchestrator = orchestrator();

    // Act
    orchestrator.close();

    // Assert
    verify(admin).close();
    verify(storage).close();
    verify(auditorClient).shutdown();
  }
}
