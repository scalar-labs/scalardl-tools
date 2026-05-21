package com.scalar.dl.tools.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Result;
import com.scalar.db.api.TransactionState;
import com.scalar.db.io.BigIntColumn;
import com.scalar.db.io.IntColumn;
import com.scalar.db.transaction.consensuscommit.Attribute;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LedgerFinalizeOrchestratorTest {

  @TempDir Path tempDir;

  private DistributedTransactionAdmin admin;
  private DistributedTransactionManager txManager;
  private ResumableScanner scanner;
  private ResumableScannerFactory scannerFactory;

  @BeforeEach
  void setUp() {
    admin = mock(DistributedTransactionAdmin.class);
    txManager = mock(DistributedTransactionManager.class);
    scanner = mock(ResumableScanner.class);
    scannerFactory = mock(ResumableScannerFactory.class);
    when(scannerFactory.create(any())).thenReturn(scanner);
  }

  /**
   * Creates a mock {@link Result} that {@link RecordStateChecker} can inspect. Follows the same
   * pattern as {@link RecordStateCheckerTest}.
   */
  private Result createMockResult(TransactionState txState, long txPreparedAt) {
    Result result = mock(Result.class);
    Map<String, com.scalar.db.io.Column<?>> columns = new LinkedHashMap<>();
    columns.put(Attribute.STATE, IntColumn.of(Attribute.STATE, txState.get()));
    columns.put(Attribute.PREPARED_AT, BigIntColumn.of(Attribute.PREPARED_AT, txPreparedAt));
    when(result.getColumns()).thenReturn(columns);
    when(result.isNull(Attribute.STATE)).thenReturn(false);
    when(result.getInt(Attribute.STATE)).thenReturn(txState.get());
    when(result.isNull(Attribute.PREPARED_AT)).thenReturn(false);
    when(result.getBigInt(Attribute.PREPARED_AT)).thenReturn(txPreparedAt);
    return result;
  }

  @Test
  void execute_initialRunGiven_shouldDiscoverTablesAndScanAndReturnToken() throws Exception {
    // Arrange
    when(admin.getNamespaceNames()).thenReturn(new HashSet<>(Arrays.asList("ns1", "ns2")));
    when(admin.getNamespaceTableNames("ns1"))
        .thenReturn(new HashSet<>(Collections.singletonList("tbl1")));
    when(admin.getNamespaceTableNames("ns2"))
        .thenReturn(new HashSet<>(Collections.singletonList("tbl2")));
    when(scanner.scan(anyString(), anyString(), any())).thenReturn(new ScanResult(10));

    LedgerFinalizeOrchestrator orchestrator =
        new LedgerFinalizeOrchestrator(admin, txManager, scannerFactory, tempDir, 1);

    // Act
    long before = System.currentTimeMillis();
    String completionToken = orchestrator.execute();
    long after = System.currentTimeMillis();

    // Assert
    assertThat(completionToken).isNotEmpty();
    CompletionToken token = CompletionToken.decode(completionToken);
    assertThat(token.getServerType()).isEqualTo(CompletionToken.ServerType.LEDGER);
    assertThat(token.getStartedAtMs()).isBetween(before, after);

    verify(scanner).scan(eq("ns1"), eq("tbl1"), any());
    verify(scanner).scan(eq("ns2"), eq("tbl2"), any());
    verify(scanner, times(2)).close();

    LedgerFinalizeState state = new LedgerFinalizeStateManager(tempDir).load();
    assertThat(state).isNotNull();
    assertThat(state.getCompletedTables()).containsExactlyInAnyOrder("ns1.tbl1", "ns2.tbl2");
    assertThat(state.getStartedAtMs()).isEqualTo(token.getStartedAtMs());
  }

  @Test
  void execute_noTablesGiven_shouldReturnTokenWithoutScanning() throws Exception {
    // Arrange
    when(admin.getNamespaceNames()).thenReturn(Collections.emptySet());

    LedgerFinalizeOrchestrator orchestrator =
        new LedgerFinalizeOrchestrator(admin, txManager, scannerFactory, tempDir, 1);

    // Act
    String completionToken = orchestrator.execute();

    // Assert
    assertThat(completionToken).isNotEmpty();
    verify(scanner, never()).scan(anyString(), anyString(), any());
  }

  @Test
  void execute_scanFailureGiven_shouldPropagateException() throws Exception {
    // Arrange
    when(admin.getNamespaceNames()).thenReturn(new HashSet<>(Collections.singletonList("ns1")));
    when(admin.getNamespaceTableNames("ns1"))
        .thenReturn(new HashSet<>(Collections.singletonList("tbl1")));
    when(scanner.scan(eq("ns1"), eq("tbl1"), any()))
        .thenThrow(new RuntimeException("Cosmos DB unavailable"));

    LedgerFinalizeOrchestrator orchestrator =
        new LedgerFinalizeOrchestrator(admin, txManager, scannerFactory, tempDir, 1);

    // Act & Assert
    assertThatThrownBy(orchestrator::execute)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Cosmos DB unavailable");

    verify(scanner).close();
  }

  @Test
  void execute_nonTerminalAndTerminalRecordsGiven_shouldProcessOnlyNonTerminalRecordsInWindow()
      throws Exception {
    // Arrange
    // Use pre-persisted state to specify guaranteeTimestamp
    long guaranteeTimestamp = 1000L;
    LedgerFinalizeStateManager stateManager = new LedgerFinalizeStateManager(tempDir);
    stateManager.persist(
        new LedgerFinalizeState(
            guaranteeTimestamp, Collections.singletonList("ns1.tbl1"), new ArrayList<>()));

    Result preparedInWindow = createMockResult(TransactionState.PREPARED, 500L);
    Result committedRecord = createMockResult(TransactionState.COMMITTED, 500L);
    Result preparedOutsideWindow = createMockResult(TransactionState.PREPARED, 2000L);

    when(scanner.scan(eq("ns1"), eq("tbl1"), any()))
        .thenAnswer(
            invocation -> {
              Consumer<Result> consumer = invocation.getArgument(2);
              consumer.accept(preparedInWindow);
              consumer.accept(committedRecord);
              consumer.accept(preparedOutsideWindow);
              return new ScanResult(3);
            });

    RecordFinalizer mockFinalizer = mock(RecordFinalizer.class);
    LedgerFinalizeOrchestrator orchestrator =
        new LedgerFinalizeOrchestrator(
            admin, txManager, scannerFactory, tempDir, 1, (mgr, threads) -> mockFinalizer);

    // Act
    orchestrator.execute();

    // Assert
    // Only the PREPARED record within the guarantee window should be submitted
    verify(mockFinalizer).submit("ns1", "tbl1", preparedInWindow);
    verify(mockFinalizer, never()).submit(anyString(), anyString(), eq(committedRecord));
    verify(mockFinalizer, never()).submit(anyString(), anyString(), eq(preparedOutsideWindow));
    verify(mockFinalizer).awaitCompletion();
    verify(mockFinalizer).close();
  }

  @Test
  void execute_partialTableFailureGiven_shouldPersistPartialCompletionAndResumeSuccessfully()
      throws Exception {
    // Arrange 1
    // First run discovers two tables, succeeds on the first, fails on the second
    when(admin.getNamespaceNames()).thenReturn(new LinkedHashSet<>(Arrays.asList("ns1", "ns2")));
    when(admin.getNamespaceTableNames("ns1"))
        .thenReturn(new HashSet<>(Collections.singletonList("tbl1")));
    when(admin.getNamespaceTableNames("ns2"))
        .thenReturn(new HashSet<>(Collections.singletonList("tbl2")));

    when(scanner.scan(eq("ns1"), eq("tbl1"), any())).thenReturn(new ScanResult(10));
    when(scanner.scan(eq("ns2"), eq("tbl2"), any()))
        .thenThrow(new RuntimeException("Cosmos DB unavailable"));

    // Act & Assert 1
    LedgerFinalizeOrchestrator orchestrator =
        new LedgerFinalizeOrchestrator(admin, txManager, scannerFactory, tempDir, 1);
    assertThatThrownBy(orchestrator::execute).isInstanceOf(RuntimeException.class);

    // Verify first table's completion was persisted
    LedgerFinalizeStateManager stateManager = new LedgerFinalizeStateManager(tempDir);
    LedgerFinalizeState state = stateManager.load();
    assertThat(state).isNotNull();
    assertThat(state.getCompletedTables()).containsExactly("ns1.tbl1");
    long startedAtMs = state.getStartedAtMs();

    // Arrange 2
    // Resume — create a fresh scanner for the second run
    ResumableScanner scanner2 = mock(ResumableScanner.class);
    when(scannerFactory.create(any())).thenReturn(scanner2);
    when(scanner2.scan(anyString(), anyString(), any())).thenReturn(new ScanResult(5));

    // Act 2
    LedgerFinalizeOrchestrator orchestrator2 =
        new LedgerFinalizeOrchestrator(admin, txManager, scannerFactory, tempDir, 1);
    String token = orchestrator2.execute();

    // Assert 2
    assertThat(token).isNotEmpty();
    CompletionToken decoded = CompletionToken.decode(token);
    assertThat(decoded.getStartedAtMs()).isEqualTo(startedAtMs);

    // Second table was processed, first was skipped
    verify(scanner2, never()).scan(eq("ns1"), eq("tbl1"), any());
    verify(scanner2).scan(eq("ns2"), eq("tbl2"), any());
  }

  @Test
  void close_shouldCloseAdminAndTxManager() {
    // Arrange
    LedgerFinalizeOrchestrator orchestrator =
        new LedgerFinalizeOrchestrator(admin, txManager, scannerFactory, tempDir, 1);

    // Act
    orchestrator.close();

    // Assert
    verify(admin).close();
    verify(txManager).close();
  }
}
