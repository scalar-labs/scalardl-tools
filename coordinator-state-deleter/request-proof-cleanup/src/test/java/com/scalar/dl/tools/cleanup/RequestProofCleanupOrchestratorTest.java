package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.DistributedStorage;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.service.StorageFactory;
import com.scalar.dl.tools.common.AuditorInternalValues;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.common.CoordinatorStateDeleterError;
import com.scalar.dl.tools.common.CoordinatorStateDeleterException;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

@SuppressWarnings("resource")
class RequestProofCleanupOrchestratorTest {

  private static final String NAMESPACE = "auditor";
  private static final String TABLE = AuditorInternalValues.REQUEST_PROOF_TABLE_NAME;

  @TempDir Path tempDir;

  private DistributedStorage storage;
  private ResumableScanner scanner;
  private ResumableScannerFactory scannerFactory;

  private static String createLedgerToken(long startedAtMs) {
    return CompletionToken.create(CompletionToken.ServerType.LEDGER, startedAtMs).encode();
  }

  private static String createAuditorToken(long startedAtMs) {
    return CompletionToken.create(CompletionToken.ServerType.AUDITOR, startedAtMs).encode();
  }

  @BeforeEach
  void setUp() throws Exception {
    storage = mock(DistributedStorage.class);
    scanner = mock(ResumableScanner.class);
    scannerFactory = mock(ResumableScannerFactory.class);
    when(scannerFactory.create(any())).thenReturn(scanner);
    when(scanner.scan(any(), any(), any())).thenReturn(new ScanResult(0));
  }

  private RequestProofCleanupOrchestrator newOrchestrator(String namespace, String auditorToken) {
    return new RequestProofCleanupOrchestrator(
        storage, scannerFactory, tempDir, namespace, auditorToken);
  }

  @Test
  void create_nonCosmosStorageGiven_shouldThrowCoordinatorStateDeleterException() {
    // Arrange
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, "cassandra");

    // Act & Assert
    assertThatThrownBy(
            () -> RequestProofCleanupOrchestrator.create(props, tempDir, createAuditorToken(3000L)))
        .isInstanceOf(CoordinatorStateDeleterException.class)
        .hasMessageContaining("not supported");
  }

  @Test
  void create_cosmosStorageGiven_shouldNotThrow() {
    // Arrange
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, "cosmos");

    StorageFactory storageFactory = mock(StorageFactory.class);
    when(storageFactory.getStorage()).thenReturn(mock(DistributedStorage.class));

    try (MockedStatic<StorageFactory> storageFactoryStatic = mockStatic(StorageFactory.class)) {
      storageFactoryStatic
          .when(() -> StorageFactory.create(any(Properties.class)))
          .thenReturn(storageFactory);

      // Act & Assert
      assertThatCode(
              () ->
                  RequestProofCleanupOrchestrator.create(props, tempDir, createAuditorToken(3000L)))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void execute_initialRunGiven_shouldUseTokenTimestampScanAndComplete() throws Exception {
    // Arrange
    RequestProofCleanupOrchestrator orchestrator =
        newOrchestrator(NAMESPACE, createAuditorToken(3000L));

    // Act
    orchestrator.execute();

    // Assert
    verify(scanner).scan(eq(NAMESPACE), eq(TABLE), any());

    // The boundary (the Auditor token timestamp) is observable via the persisted state.
    RequestProofCleanupState state = new RequestProofCleanupStateManager(tempDir).load();
    assertThat(state).isNotNull();
    assertThat(state.getDeletableBeforeMs()).isEqualTo(3000L);
    assertThat(state.isCompleted()).isTrue();
  }

  @Test
  void execute_alreadyCompletedStateGiven_shouldSkipScanning() throws Exception {
    // Arrange
    RequestProofCleanupStateManager stateManager = new RequestProofCleanupStateManager(tempDir);
    RequestProofCleanupState completedState = new RequestProofCleanupState(500L);
    completedState.markCompleted();
    stateManager.persist(completedState);

    RequestProofCleanupOrchestrator orchestrator = newOrchestrator(NAMESPACE, null);

    // Act
    orchestrator.execute();

    // Assert
    verify(scannerFactory, never()).create(any());
    verify(scanner, never()).scan(any(), any(), any());
  }

  @Test
  void execute_corruptedTokenGiven_shouldThrowException() {
    // Arrange: a token whose payload is not valid JSON, so decoding fails.
    String corruptedToken = "aW52YWxpZC10b2tlbg"; // base64url("invalid-token")
    RequestProofCleanupOrchestrator orchestrator = newOrchestrator(NAMESPACE, corruptedToken);

    // Act & Assert
    assertThatThrownBy(orchestrator::execute)
        .isInstanceOf(CoordinatorStateDeleterException.class)
        .hasMessageContaining(
            CoordinatorStateDeleterError.COMPLETION_TOKEN_DECODE_FAILED.buildCode());
  }

  @Test
  void execute_ledgerTokenGiven_shouldThrowWrongServerType() {
    // Arrange: a structurally valid token (correct CRC) but from the wrong server type.
    RequestProofCleanupOrchestrator orchestrator =
        newOrchestrator(NAMESPACE, createLedgerToken(3000L));

    // Act & Assert
    assertThatThrownBy(orchestrator::execute)
        .isInstanceOf(CoordinatorStateDeleterException.class)
        .hasMessageContaining(
            CoordinatorStateDeleterError.AUDITOR_TOKEN_WRONG_SERVER_TYPE.buildCode());

    // The boundary must not be persisted when the token is rejected.
    assertThat(new RequestProofCleanupStateManager(tempDir).load()).isNull();
  }

  @Test
  void execute_scanFailureGiven_shouldPropagateException() throws Exception {
    // Arrange
    when(scanner.scan(eq(NAMESPACE), eq(TABLE), any()))
        .thenThrow(new RuntimeException("Cosmos DB unavailable"));
    RequestProofCleanupOrchestrator orchestrator =
        newOrchestrator(NAMESPACE, createAuditorToken(3000L));

    // Act & Assert
    assertThatThrownBy(orchestrator::execute)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Cosmos DB unavailable");
    verify(scanner).close();
  }

  @Test
  void execute_scanFailureGiven_shouldNotMarkCompleted() throws Exception {
    // Arrange
    when(scanner.scan(eq(NAMESPACE), eq(TABLE), any()))
        .thenThrow(new RuntimeException("Cosmos DB unavailable"));
    RequestProofCleanupOrchestrator orchestrator =
        newOrchestrator(NAMESPACE, createAuditorToken(3000L));

    // Act
    assertThatThrownBy(orchestrator::execute).isInstanceOf(RuntimeException.class);

    // Assert
    // The checkpoint must remain not-completed.
    RequestProofCleanupState state = new RequestProofCleanupStateManager(tempDir).load();
    assertThat(state).isNotNull();
    assertThat(state.isCompleted()).isFalse();
  }

  @Test
  void execute_resumedStateGiven_shouldUsePreviousDeletableBeforeMs() throws Exception {
    // Arrange
    // Pre-persist state to simulate a resumed run
    RequestProofCleanupStateManager stateManager = new RequestProofCleanupStateManager(tempDir);
    stateManager.persist(new RequestProofCleanupState(500L));

    RequestProofCleanupOrchestrator orchestrator = newOrchestrator(NAMESPACE, null);

    // Act
    orchestrator.execute();

    // Assert
    // The persisted boundary is reused (not recomputed) and the scan runs
    verify(scanner).scan(eq(NAMESPACE), eq(TABLE), any());
    RequestProofCleanupState state = stateManager.load();
    assertThat(state).isNotNull();
    assertThat(state.getDeletableBeforeMs()).isEqualTo(500L);
  }

  @Test
  void execute_checkpointExistsAndTokenGiven_shouldResumeAndIgnoreToken() throws Exception {
    // Arrange
    RequestProofCleanupStateManager stateManager = new RequestProofCleanupStateManager(tempDir);
    stateManager.persist(new RequestProofCleanupState(500L));

    // A token that would compute a different boundary (3000) if it were not ignored
    RequestProofCleanupOrchestrator orchestrator =
        newOrchestrator(NAMESPACE, createAuditorToken(3000L));

    // Act
    orchestrator.execute();

    // Assert
    // The persisted boundary (500) is kept, not the token-derived boundary (3000)
    RequestProofCleanupState state = stateManager.load();
    assertThat(state).isNotNull();
    assertThat(state.getDeletableBeforeMs()).isEqualTo(500L);
  }

  @Test
  void execute_noCheckpointAndMissingTokenGiven_shouldThrowException() {
    // Arrange
    RequestProofCleanupOrchestrator orchestrator = newOrchestrator(NAMESPACE, null);

    // Act & Assert
    assertThatThrownBy(orchestrator::execute)
        .isInstanceOf(CoordinatorStateDeleterException.class)
        .hasMessageContaining(
            CoordinatorStateDeleterError.AUDITOR_COMPLETION_TOKEN_REQUIRED.buildCode());
  }

  @Test
  void execute_customNamespaceGiven_shouldScanInThatNamespace() throws Exception {
    // Arrange
    String customNamespace = "my_auditor";
    RequestProofCleanupOrchestrator orchestrator =
        newOrchestrator(customNamespace, createAuditorToken(3000L));

    // Act
    orchestrator.execute();

    // Assert
    verify(scanner).scan(eq(customNamespace), eq(TABLE), any());
  }

  @Test
  void close_shouldCloseStorage() {
    // Arrange
    RequestProofCleanupOrchestrator orchestrator =
        newOrchestrator(NAMESPACE, createAuditorToken(1000L));

    // Act
    orchestrator.close();

    // Assert
    verify(storage).close();
  }
}
