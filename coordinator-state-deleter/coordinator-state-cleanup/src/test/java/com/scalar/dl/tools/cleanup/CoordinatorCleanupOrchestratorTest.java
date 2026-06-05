package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.Result;
import com.scalar.db.transaction.consensuscommit.Attribute;
import com.scalar.db.transaction.consensuscommit.Coordinator;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

  static Stream<Arguments> invalidTokenProvider() {
    String invalidCrc = "aW52YWxpZC10b2tlbg"; // base64url("invalid-token")
    return Stream.of(
        Arguments.of(invalidCrc, createAuditorToken(1000L)),
        Arguments.of(createLedgerToken(1000L), invalidCrc),
        Arguments.of(createAuditorToken(1000L), createAuditorToken(2000L)),
        Arguments.of(createLedgerToken(1000L), createLedgerToken(2000L)));
  }

  @BeforeEach
  void setUp() {
    storage = mock(DistributedStorage.class);
    scanner = mock(ResumableScanner.class);
    scannerFactory = mock(ResumableScannerFactory.class);
    when(scannerFactory.create(any())).thenReturn(scanner);
  }

  private Result createMockResult(long createdAt) {
    Result result = mock(Result.class);
    when(result.isNull(Attribute.CREATED_AT)).thenReturn(false);
    when(result.getBigInt(Attribute.CREATED_AT)).thenReturn(createdAt);
    return result;
  }

  @Test
  void execute_shouldScanCoordinatorTableAndDeleteOnlyDeletableRecords() throws Exception {
    // Arrange
    String ledgerToken = createLedgerToken(2000L);
    String auditorToken = createAuditorToken(3000L);

    Result deletable1 = createMockResult(1000L);
    Result deletable2 = createMockResult(1999L);
    Result notDeletable1 = createMockResult(2000L);
    Result notDeletable2 = createMockResult(2500L);
    when(scanner.scan(eq(Coordinator.NAMESPACE), eq(Coordinator.TABLE), any()))
        .thenAnswer(
            invocation -> {
              Consumer<Result> consumer = invocation.getArgument(2);
              consumer.accept(deletable1);
              consumer.accept(notDeletable1);
              consumer.accept(deletable2);
              consumer.accept(notDeletable2);
              return new ScanResult(4);
            });

    RecordDeleter mockDeleter = mock(RecordDeleter.class);
    when(mockDeleter.getDeletedCount()).thenReturn(2L);

    CoordinatorCleanupOrchestrator orchestrator =
        new CoordinatorCleanupOrchestrator(
            storage,
            scannerFactory,
            tempDir,
            1,
            ledgerToken,
            auditorToken,
            (s, threads) -> mockDeleter);

    // Act
    long deletedCount = orchestrator.execute();

    // Assert
    assertThat(deletedCount).isEqualTo(2);
    verify(scanner).scan(eq(Coordinator.NAMESPACE), eq(Coordinator.TABLE), any());
    verify(mockDeleter).submit(deletable1);
    verify(mockDeleter).submit(deletable2);
    verify(mockDeleter, never()).submit(notDeletable1);
    verify(mockDeleter, never()).submit(notDeletable2);
    verify(mockDeleter).awaitCompletion();
    verify(mockDeleter).close();

    CoordinatorCleanupState state = new CoordinatorCleanupStateManager(tempDir).load();
    assertThat(state).isNotNull();
    assertThat(state.getDeletableBeforeMs()).isEqualTo(2000L);
  }

  @ParameterizedTest
  @MethodSource("deletableBeforeMsProvider")
  void execute_shouldUseSmallerTimestampAsDeletableBeforeMs(
      long ledgerTimestamp, long auditorTimestamp, long expectedDeletableBeforeMs)
      throws Exception {
    // Arrange
    String ledgerToken = createLedgerToken(ledgerTimestamp);
    String auditorToken = createAuditorToken(auditorTimestamp);
    when(scanner.scan(eq(Coordinator.NAMESPACE), eq(Coordinator.TABLE), any()))
        .thenReturn(new ScanResult(0));

    RecordDeleter mockDeleter = mock(RecordDeleter.class);
    when(mockDeleter.getDeletedCount()).thenReturn(0L);

    CoordinatorCleanupOrchestrator orchestrator =
        new CoordinatorCleanupOrchestrator(
            storage,
            scannerFactory,
            tempDir,
            1,
            ledgerToken,
            auditorToken,
            (s, threads) -> mockDeleter);

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
        new CoordinatorCleanupOrchestrator(
            storage, scannerFactory, tempDir, 1, ledgerToken, auditorToken);

    // Act & Assert
    assertThatThrownBy(orchestrator::execute).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void execute_deleteFailureGiven_shouldStopProcessingAndPropagateException() throws Exception {
    // Arrange
    String ledgerToken = createLedgerToken(2000L);
    String auditorToken = createAuditorToken(3000L);

    Result record1 = createMockResult(1000L);
    Result record2 = createMockResult(1500L);

    RecordDeleter mockDeleter = mock(RecordDeleter.class);
    doThrow(new RuntimeException("DB unavailable")).when(mockDeleter).submit(record1);

    when(scanner.scan(eq(Coordinator.NAMESPACE), eq(Coordinator.TABLE), any()))
        .thenAnswer(
            invocation -> {
              Consumer<Result> consumer = invocation.getArgument(2);
              consumer.accept(record1);
              consumer.accept(record2);
              return new ScanResult(2);
            });

    CoordinatorCleanupOrchestrator orchestrator =
        new CoordinatorCleanupOrchestrator(
            storage,
            scannerFactory,
            tempDir,
            1,
            ledgerToken,
            auditorToken,
            (s, threads) -> mockDeleter);

    // Act & Assert
    assertThatThrownBy(orchestrator::execute)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("DB unavailable");
    verify(mockDeleter, never()).submit(record2);
  }

  @Test
  void execute_scanFailureGiven_shouldPropagateException() throws Exception {
    // Arrange
    String ledgerToken = createLedgerToken(2000L);
    String auditorToken = createAuditorToken(3000L);
    when(scanner.scan(eq(Coordinator.NAMESPACE), eq(Coordinator.TABLE), any()))
        .thenThrow(new RuntimeException("Cosmos DB unavailable"));

    CoordinatorCleanupOrchestrator orchestrator =
        new CoordinatorCleanupOrchestrator(
            storage, scannerFactory, tempDir, 1, ledgerToken, auditorToken);

    // Act & Assert
    assertThatThrownBy(orchestrator::execute)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Cosmos DB unavailable");

    verify(scanner).close();
  }

  @Test
  void execute_resumedStateGiven_shouldUsePreviousDeletableBeforeMs() throws Exception {
    // Arrange
    // Pre-persist state to simulate a resumed run
    CoordinatorCleanupStateManager stateManager = new CoordinatorCleanupStateManager(tempDir);
    stateManager.persist(new CoordinatorCleanupState(500L));

    // Tokens don't matter for a resumed run since the deletable-before timestamp is loaded from
    // state
    String ledgerToken = createLedgerToken(2000L);
    String auditorToken = createAuditorToken(3000L);

    Result deletableRecord = createMockResult(400L);
    Result nonDeletableRecord = createMockResult(600L);

    when(scanner.scan(eq(Coordinator.NAMESPACE), eq(Coordinator.TABLE), any()))
        .thenAnswer(
            invocation -> {
              Consumer<Result> consumer = invocation.getArgument(2);
              consumer.accept(deletableRecord);
              consumer.accept(nonDeletableRecord);
              return new ScanResult(2);
            });

    RecordDeleter mockDeleter = mock(RecordDeleter.class);
    when(mockDeleter.getDeletedCount()).thenReturn(1L);

    CoordinatorCleanupOrchestrator orchestrator =
        new CoordinatorCleanupOrchestrator(
            storage,
            scannerFactory,
            tempDir,
            1,
            ledgerToken,
            auditorToken,
            (s, threads) -> mockDeleter);

    // Act
    long deletedCount = orchestrator.execute();

    // Assert
    // Should use the persisted deletable-before timestamp (500), not the token values
    assertThat(deletedCount).isEqualTo(1);
    verify(mockDeleter).submit(deletableRecord);
    verify(mockDeleter, never()).submit(nonDeletableRecord);
  }

  @Test
  void close_shouldCloseStorage() {
    // Arrange
    String ledgerToken = createLedgerToken(1000L);
    String auditorToken = createAuditorToken(2000L);
    CoordinatorCleanupOrchestrator orchestrator =
        new CoordinatorCleanupOrchestrator(
            storage, scannerFactory, tempDir, 1, ledgerToken, auditorToken);

    // Act
    orchestrator.close();

    // Assert
    verify(storage).close();
  }
}
