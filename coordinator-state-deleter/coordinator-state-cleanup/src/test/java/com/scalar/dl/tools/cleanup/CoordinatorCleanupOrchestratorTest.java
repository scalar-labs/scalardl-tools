package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.DistributedStorage;
import com.scalar.db.transaction.consensuscommit.Coordinator;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
    String invalidCrc = "aW52YWxpZC10b2tlbg"; // base64url("invalid-token")
    return Stream.of(
        Arguments.of(invalidCrc, createAuditorToken(1000L)),
        Arguments.of(createLedgerToken(1000L), invalidCrc),
        Arguments.of(createAuditorToken(1000L), createAuditorToken(2000L)),
        Arguments.of(createLedgerToken(1000L), createLedgerToken(2000L)));
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
  void execute_initialRunGiven_shouldComputeBoundaryScanAndComplete() throws Exception {
    // Arrange
    String ledgerToken = createLedgerToken(2000L);
    String auditorToken = createAuditorToken(3000L);
    CoordinatorCleanupOrchestrator orchestrator =
        newOrchestrator(Coordinator.NAMESPACE, ledgerToken, auditorToken);

    // Act
    orchestrator.execute();

    // Assert
    verify(scanner).scan(eq(Coordinator.NAMESPACE), eq(Coordinator.TABLE), any());

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
        newOrchestrator(Coordinator.NAMESPACE, null, null);

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
            Coordinator.NAMESPACE,
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
  void execute_invalidTokenGiven_shouldThrowIllegalArgumentException(
      String ledgerToken, String auditorToken) {
    // Arrange
    CoordinatorCleanupOrchestrator orchestrator =
        newOrchestrator(Coordinator.NAMESPACE, ledgerToken, auditorToken);

    // Act & Assert
    assertThatThrownBy(orchestrator::execute).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void execute_scanFailureGiven_shouldPropagateException() throws Exception {
    // Arrange
    when(scanner.scan(eq(Coordinator.NAMESPACE), eq(Coordinator.TABLE), any()))
        .thenThrow(new RuntimeException("Cosmos DB unavailable"));
    CoordinatorCleanupOrchestrator orchestrator =
        newOrchestrator(Coordinator.NAMESPACE, createLedgerToken(2000L), createAuditorToken(3000L));

    // Act & Assert
    assertThatThrownBy(orchestrator::execute)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Cosmos DB unavailable");
    verify(scanner).close();
  }

  @Test
  void execute_scanFailureGiven_shouldNotMarkCompleted() throws Exception {
    // Arrange
    when(scanner.scan(eq(Coordinator.NAMESPACE), eq(Coordinator.TABLE), any()))
        .thenThrow(new RuntimeException("Cosmos DB unavailable"));
    CoordinatorCleanupOrchestrator orchestrator =
        newOrchestrator(Coordinator.NAMESPACE, createLedgerToken(2000L), createAuditorToken(3000L));

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
        newOrchestrator(Coordinator.NAMESPACE, null, null);

    // Act
    orchestrator.execute();

    // Assert
    // The persisted boundary is reused (not recomputed) and the scan runs
    verify(scanner).scan(eq(Coordinator.NAMESPACE), eq(Coordinator.TABLE), any());
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
        newOrchestrator(Coordinator.NAMESPACE, createLedgerToken(2000L), createAuditorToken(3000L));

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
  void execute_noCheckpointAndMissingTokenGiven_shouldThrowIllegalArgumentException(
      String ledgerToken, String auditorToken) {
    // Arrange
    CoordinatorCleanupOrchestrator orchestrator =
        newOrchestrator(Coordinator.NAMESPACE, ledgerToken, auditorToken);

    // Act & Assert
    assertThatThrownBy(orchestrator::execute)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Both ledger and auditor completion tokens are required for the initial run");
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
    verify(scanner).scan(eq(customNamespace), eq(Coordinator.TABLE), any());
  }

  @Test
  void close_shouldCloseStorage() {
    // Arrange
    CoordinatorCleanupOrchestrator orchestrator =
        newOrchestrator(Coordinator.NAMESPACE, createLedgerToken(1000L), createAuditorToken(2000L));

    // Act
    orchestrator.close();

    // Assert
    verify(storage).close();
  }
}
