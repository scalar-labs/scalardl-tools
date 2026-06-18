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

import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedStorageAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.dl.tools.common.CompletionToken;
import com.scalar.dl.tools.scan.ResumableScanner;
import com.scalar.dl.tools.scan.ResumableScannerFactory;
import com.scalar.dl.tools.scan.ScanResult;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("resource")
class LedgerFinalizeOrchestratorTest {

  @TempDir Path tempDir;

  private DistributedStorageAdmin admin;
  private DistributedStorage storage;
  private DistributedTransactionManager txManager;
  private ResumableScanner scanner;
  private ResumableScannerFactory scannerFactory;

  @BeforeEach
  void setUp() {
    admin = mock(DistributedStorageAdmin.class);
    storage = mock(DistributedStorage.class);
    txManager = mock(DistributedTransactionManager.class);
    scanner = mock(ResumableScanner.class);
    scannerFactory = mock(ResumableScannerFactory.class);
    when(scannerFactory.create(any())).thenReturn(scanner);
  }

  private LedgerFinalizeOrchestrator newOrchestrator() {
    return new LedgerFinalizeOrchestrator(admin, storage, txManager, scannerFactory, tempDir);
  }

  @Test
  void execute_initialRunGiven_shouldDiscoverTablesScanAndReturnToken() throws Exception {
    // Arrange
    when(admin.getNamespaceNames()).thenReturn(new HashSet<>(Arrays.asList("ns1", "ns2")));
    when(admin.getNamespaceTableNames("ns1"))
        .thenReturn(new HashSet<>(Collections.singletonList("tbl1")));
    when(admin.getNamespaceTableNames("ns2"))
        .thenReturn(new HashSet<>(Collections.singletonList("tbl2")));
    when(scanner.scan(anyString(), anyString(), any())).thenReturn(new ScanResult(10));

    LedgerFinalizeOrchestrator orchestrator = newOrchestrator();

    // Act
    long before = System.currentTimeMillis();
    String completionToken = orchestrator.execute();
    long after = System.currentTimeMillis();

    // Assert
    CompletionToken token = CompletionToken.decode(completionToken);
    assertThat(token.getServerType()).isEqualTo(CompletionToken.ServerType.LEDGER);
    assertThat(token.getStartedAtMs()).isBetween(before, after);

    // Each discovered table is scanned and the scanner is closed per table.
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

    LedgerFinalizeOrchestrator orchestrator = newOrchestrator();

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

    LedgerFinalizeOrchestrator orchestrator = newOrchestrator();

    // Act & Assert
    assertThatThrownBy(orchestrator::execute)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Cosmos DB unavailable");

    verify(scanner).close();
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
    LedgerFinalizeOrchestrator orchestrator = newOrchestrator();
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
    LedgerFinalizeOrchestrator orchestrator2 = newOrchestrator();
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
  void close_shouldCloseAdminAndStorageAndTxManager() {
    // Arrange
    LedgerFinalizeOrchestrator orchestrator = newOrchestrator();

    // Act
    orchestrator.close();

    // Assert
    verify(admin).close();
    verify(storage).close();
    verify(txManager).close();
  }
}
