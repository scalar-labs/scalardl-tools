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
import com.scalar.db.transaction.consensuscommit.CoordinatorStateAccessor;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.common.CoordinatorStateDeleterError;
import com.scalar.dl.tools.common.CoordinatorStateDeleterException;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.file.Path;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;

@SuppressWarnings("resource")
class CoordinatorCleanupOrchestratorTest {

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

  static Stream<Arguments> deletableBeforeMsProvider() {
    return Stream.of(
        Arguments.of(3000L, 1000L, 1000L),
        Arguments.of(1000L, 3000L, 1000L),
        Arguments.of(2000L, 2000L, 2000L));
  }

  static Stream<Arguments> missingTokenProvider() {
    return Stream.of(
        Arguments.of(null, null),
        Arguments.of(createLedgerToken(1000L), null),
        Arguments.of(null, createAuditorToken(1000L)));
  }

  static Stream<Arguments> invalidTokenProvider() {
    String undecodableToken = "aW52YWxpZC10b2tlbg"; // base64url("invalid-token"), not valid JSON
    return Stream.of(
        // An undecodable Ledger token.
        Arguments.of(
            undecodableToken,
            createAuditorToken(1000L),
            CoordinatorStateDeleterError.COMPLETION_TOKEN_DECODE_FAILED),
        // An undecodable Auditor token.
        Arguments.of(
            createLedgerToken(1000L),
            undecodableToken,
            CoordinatorStateDeleterError.COMPLETION_TOKEN_DECODE_FAILED),
        // An Auditor token supplied in the Ledger slot.
        Arguments.of(
            createAuditorToken(1000L),
            createAuditorToken(2000L),
            CoordinatorStateDeleterError.LEDGER_TOKEN_WRONG_SERVER_TYPE),
        // A Ledger token supplied in the Auditor slot.
        Arguments.of(
            createLedgerToken(1000L),
            createLedgerToken(2000L),
            CoordinatorStateDeleterError.AUDITOR_TOKEN_WRONG_SERVER_TYPE));
  }

  @BeforeEach
  void setUp() throws Exception {
    storage = mock(DistributedStorage.class);
    scanner = mock(ResumableScanner.class);
    scannerFactory = mock(ResumableScannerFactory.class);
    when(scannerFactory.create(any())).thenReturn(scanner);
    when(scanner.scan(any(), any(), any())).thenReturn(new ScanResult(0));
  }

  private CoordinatorCleanupOrchestrator newOrchestrator(
      String coordinatorNamespace, String ledgerToken, String auditorToken) {
    return new CoordinatorCleanupOrchestrator(
        storage, scannerFactory, tempDir, coordinatorNamespace, ledgerToken, auditorToken);
  }

  @Test
  void create_nonCosmosStorageGiven_shouldThrowCoordinatorStateDeleterException() {
    // Arrange
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, "cassandra");

    // Act & Assert
    assertThatThrownBy(
            () ->
                CoordinatorCleanupOrchestrator.create(
                    props, tempDir, createLedgerToken(1000L), createAuditorToken(2000L)))
        .isInstanceOf(CoordinatorStateDeleterException.class)
        .hasMessageContaining("not supported");
  }

  @Test
  void create_jdbcTransactionManagerGiven_shouldThrowCoordinatorStateDeleterException() {
    // Arrange
    Properties props = new Properties();
    props.setProperty(DatabaseConfig.STORAGE, "cosmos");
    props.setProperty(DatabaseConfig.TRANSACTION_MANAGER, "jdbc");

    // Act & Assert
    assertThatThrownBy(
            () ->
                CoordinatorCleanupOrchestrator.create(
                    props, tempDir, createLedgerToken(1000L), createAuditorToken(2000L)))
        .isInstanceOf(CoordinatorStateDeleterException.class)
        .hasMessageContaining("jdbc")
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
                  CoordinatorCleanupOrchestrator.create(
                      props, tempDir, createLedgerToken(1000L), createAuditorToken(2000L)))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void execute_initialRunGiven_shouldComputeBoundaryScanAndComplete() throws Exception {
    // Arrange
    String ledgerToken = createLedgerToken(2000L);
    String auditorToken = createAuditorToken(3000L);
    CoordinatorCleanupOrchestrator orchestrator =
        newOrchestrator(CoordinatorStateAccessor.NAMESPACE, ledgerToken, auditorToken);

    // Act
    orchestrator.execute();

    // Assert
    verify(scanner)
        .scan(eq(CoordinatorStateAccessor.NAMESPACE), eq(CoordinatorStateAccessor.TABLE), any());

    // The boundary (earlier of the two token timestamps) is observable via the persisted state.
    CoordinatorCleanupState state = new CoordinatorCleanupStateManager(tempDir).load();
    assertThat(state).isNotNull();
    assertThat(state.getDeletableBeforeMs()).isEqualTo(2000L);
    assertThat(state.isCompleted()).isTrue();
  }

  @Test
  void execute_alreadyCompletedStateGiven_shouldSkipScanning() throws Exception {
    // Arrange
    CoordinatorCleanupStateManager stateManager = new CoordinatorCleanupStateManager(tempDir);
    CoordinatorCleanupState completedState = new CoordinatorCleanupState(500L);
    completedState.markCompleted();
    stateManager.persist(completedState);

    CoordinatorCleanupOrchestrator orchestrator =
        newOrchestrator(CoordinatorStateAccessor.NAMESPACE, null, null);

    // Act
    orchestrator.execute();

    // Assert
    verify(scannerFactory, never()).create(any());
    verify(scanner, never()).scan(any(), any(), any());
  }

  @ParameterizedTest
  @MethodSource("deletableBeforeMsProvider")
  void execute_shouldUseSmallerTimestampAsDeletableBeforeMs(
      long ledgerTimestamp, long auditorTimestamp, long expectedDeletableBeforeMs)
      throws Exception {
    // Arrange
    CoordinatorCleanupOrchestrator orchestrator =
        newOrchestrator(
            CoordinatorStateAccessor.NAMESPACE,
            createLedgerToken(ledgerTimestamp),
            createAuditorToken(auditorTimestamp));

    // Act
    orchestrator.execute();

    // Assert
    CoordinatorCleanupState state = new CoordinatorCleanupStateManager(tempDir).load();
    assertThat(state).isNotNull();
    assertThat(state.getDeletableBeforeMs()).isEqualTo(expectedDeletableBeforeMs);
  }

  @ParameterizedTest
  @MethodSource("invalidTokenProvider")
  void execute_invalidTokenGiven_shouldThrowException(
      String ledgerToken, String auditorToken, CoordinatorStateDeleterError expected) {
    // Arrange
    CoordinatorCleanupOrchestrator orchestrator =
        newOrchestrator(CoordinatorStateAccessor.NAMESPACE, ledgerToken, auditorToken);

    // Act & Assert
    assertThatThrownBy(orchestrator::execute)
        .isInstanceOf(CoordinatorStateDeleterException.class)
        .hasMessageContaining(expected.buildCode());
  }

  @Test
  void execute_scanFailureGiven_shouldPropagateException() throws Exception {
    // Arrange
    when(scanner.scan(
            eq(CoordinatorStateAccessor.NAMESPACE), eq(CoordinatorStateAccessor.TABLE), any()))
        .thenThrow(new RuntimeException("Cosmos DB unavailable"));
    CoordinatorCleanupOrchestrator orchestrator =
        newOrchestrator(
            CoordinatorStateAccessor.NAMESPACE,
            createLedgerToken(2000L),
            createAuditorToken(3000L));

    // Act & Assert
    assertThatThrownBy(orchestrator::execute)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Cosmos DB unavailable");
    verify(scanner).close();
  }

  @Test
  void execute_scanFailureGiven_shouldNotMarkCompleted() throws Exception {
    // Arrange
    when(scanner.scan(
            eq(CoordinatorStateAccessor.NAMESPACE), eq(CoordinatorStateAccessor.TABLE), any()))
        .thenThrow(new RuntimeException("Cosmos DB unavailable"));
    CoordinatorCleanupOrchestrator orchestrator =
        newOrchestrator(
            CoordinatorStateAccessor.NAMESPACE,
            createLedgerToken(2000L),
            createAuditorToken(3000L));

    // Act
    assertThatThrownBy(orchestrator::execute).isInstanceOf(RuntimeException.class);

    // Assert
    // The checkpoint must remain not-completed.
    CoordinatorCleanupState state = new CoordinatorCleanupStateManager(tempDir).load();
    assertThat(state).isNotNull();
    assertThat(state.isCompleted()).isFalse();
  }

  @Test
  void execute_resumedStateGiven_shouldUsePreviousDeletableBeforeMs() throws Exception {
    // Arrange
    // Pre-persist state to simulate a resumed run
    CoordinatorCleanupStateManager stateManager = new CoordinatorCleanupStateManager(tempDir);
    stateManager.persist(new CoordinatorCleanupState(500L));

    CoordinatorCleanupOrchestrator orchestrator =
        newOrchestrator(CoordinatorStateAccessor.NAMESPACE, null, null);

    // Act
    orchestrator.execute();

    // Assert
    // The persisted boundary is reused (not recomputed) and the scan runs
    verify(scanner)
        .scan(eq(CoordinatorStateAccessor.NAMESPACE), eq(CoordinatorStateAccessor.TABLE), any());
    CoordinatorCleanupState state = stateManager.load();
    assertThat(state).isNotNull();
    assertThat(state.getDeletableBeforeMs()).isEqualTo(500L);
  }

  @Test
  void execute_checkpointExistsAndTokensGiven_shouldResumeAndIgnoreTokens() throws Exception {
    // Arrange
    CoordinatorCleanupStateManager stateManager = new CoordinatorCleanupStateManager(tempDir);
    stateManager.persist(new CoordinatorCleanupState(500L));

    // Tokens that would compute a different boundary (2000) if they were not ignored
    CoordinatorCleanupOrchestrator orchestrator =
        newOrchestrator(
            CoordinatorStateAccessor.NAMESPACE,
            createLedgerToken(2000L),
            createAuditorToken(3000L));

    // Act
    orchestrator.execute();

    // Assert
    // The persisted boundary (500) is kept, not the token-derived boundary (2000)
    CoordinatorCleanupState state = stateManager.load();
    assertThat(state).isNotNull();
    assertThat(state.getDeletableBeforeMs()).isEqualTo(500L);
  }

  @ParameterizedTest
  @MethodSource("missingTokenProvider")
  void execute_noCheckpointAndMissingTokenGiven_shouldThrowException(
      String ledgerToken, String auditorToken) {
    // Arrange
    CoordinatorCleanupOrchestrator orchestrator =
        newOrchestrator(CoordinatorStateAccessor.NAMESPACE, ledgerToken, auditorToken);

    // Act & Assert
    assertThatThrownBy(orchestrator::execute)
        .isInstanceOf(CoordinatorStateDeleterException.class)
        .hasMessageContaining(
            CoordinatorStateDeleterError.BOTH_COMPLETION_TOKENS_REQUIRED.buildCode());
  }

  @Test
  void execute_customCoordinatorNamespaceGiven_shouldScanInThatNamespace() throws Exception {
    // Arrange
    String customNamespace = "my_coordinator";
    CoordinatorCleanupOrchestrator orchestrator =
        newOrchestrator(customNamespace, createLedgerToken(2000L), createAuditorToken(3000L));

    // Act
    orchestrator.execute();

    // Assert
    verify(scanner).scan(eq(customNamespace), eq(CoordinatorStateAccessor.TABLE), any());
  }

  @Test
  void close_shouldCloseStorage() {
    // Arrange
    CoordinatorCleanupOrchestrator orchestrator =
        newOrchestrator(
            CoordinatorStateAccessor.NAMESPACE,
            createLedgerToken(1000L),
            createAuditorToken(2000L));

    // Act
    orchestrator.close();

    // Assert
    verify(storage).close();
  }
}
